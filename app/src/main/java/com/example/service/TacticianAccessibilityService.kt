package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.example.data.CaptureSource
import com.example.data.OverlayStateStore
import com.example.data.PreferencesRepository
import com.example.data.ServiceStatus
import com.example.engine.AdaptiveRoiConfig
import com.example.engine.AdaptiveScreenParser
import com.example.engine.AutoplayMoveVerifier
import com.example.engine.BitboardSolver
import com.example.engine.DropVerificationResult
import com.example.engine.IntArrayPixelFrame
import com.example.engine.MoveRecommendation
import com.example.engine.OperatingMode
import com.example.engine.ParsedFrame
import com.example.engine.PixelFrame
import com.example.engine.TraySlotState
import com.example.overlay.OverlayWindowHost
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Android 15+ compliant AccessibilityService powering:
 * 1. Smooth multi-segment GestureDescription piece dragging for Autoplay.
 * 2. Zero-prompt AccessibilityService.takeScreenshot() board reading on Android 11-15+
 *    with seamless fallback signaling to MediaProjection when unsupported or restricted.
 * 3. Closed-loop Verify-After-Drop checks with automatic vertical lift calibration.
 */
@SuppressLint("AccessibilityPolicy")
class TacticianAccessibilityService : AccessibilityService() {

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        @Volatile
        private var activeInstance: TacticianAccessibilityService? = null

        fun canTakeScreenshots(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activeInstance != null

        fun startNativeSession(): Boolean {
            val svc = activeInstance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            svc.startNativeLoop()
            return true
        }

        fun stopNativeSession() {
            activeInstance?.stopNativeLoop()
        }

        fun togglePauseNativeSession() {
            val state = OverlayStateStore.state.value
            if (state.status == ServiceStatus.STOPPED) return
            OverlayStateStore.setPaused(state.status != ServiceStatus.PAUSED)
        }

        /**
         * Dispatches a smooth human-paced drag from tray slot center to target cell center
         * (compensated for Block Blast Lite's finger lift offset).
         * Callable from both Accessibility capture loop and MediaProjection fallback service.
         */
        suspend fun executeAutoplayDrag(
            config: AdaptiveRoiConfig,
            rec: MoveRecommendation,
            screenW: Int,
            screenH: Int,
            learnedLiftDeltaCells: Float
        ): Boolean {
            val svc = activeInstance ?: return false
            return svc.performPieceDrag(config, rec, screenW, screenH, learnedLiftDeltaCells)
        }
    }

    private sealed interface CaptureAttempt {
        data class Frame(val pixelFrame: PixelFrame, val width: Int, val height: Int) : CaptureAttempt
        data object Throttled : CaptureAttempt
        data object UnsupportedOrError : CaptureAttempt
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val parser = AdaptiveScreenParser()

    private var overlayHost: OverlayWindowHost? = null
    private var nativeLoopJob: Job? = null

    private var screenWidthPx = 1080
    private var screenHeightPx = 2400

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        _isConnected.value = true
        updateDisplayMetrics()
        overlayHost = OverlayWindowHost(context = this, useAccessibilityOverlayType = true)

        val prefs = PreferencesRepository(applicationContext)
        serviceScope.launch {
            prefs.configFlow.collectLatest { cfg ->
                OverlayStateStore.updateRoiConfig(cfg)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Frame-driven vision pipeline does not depend on noisy UI node trees.
    }

    override fun onInterrupt() {
        stopNativeLoop()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanupAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupAll()
        serviceScope.cancel()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun cleanupAll() {
        stopNativeLoop()
        if (activeInstance === this) {
            activeInstance = null
        }
        _isConnected.value = false
    }

    private fun startNativeLoop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        updateDisplayMetrics()
        parser.resetStability()

        mainHandler.post {
            overlayHost?.attach(
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                onTogglePause = { togglePauseNativeSession() },
                onStop = { stopNativeLoop() }
            )
        }

        OverlayStateStore.setServiceStarted(
            widthPx = screenWidthPx,
            heightPx = screenHeightPx,
            source = CaptureSource.ACCESSIBILITY
        )

        nativeLoopJob?.cancel()
        nativeLoopJob = serviceScope.launch {
            var lockedBoardMask = -1L
            var lockedTraySig = ""
            var lockedConfig: AdaptiveRoiConfig? = null
            var lockedRec: MoveRecommendation? = null
            var lockedActive: ParsedFrame.ActiveBoard? = null
            var consecutiveIdleFrames = 0
            var consecutiveErrors = 0
            var failedDropStreak = 0

            while (isActive) {
                val state = OverlayStateStore.state.value
                if (state.status == ServiceStatus.PAUSED || state.status == ServiceStatus.STOPPED) {
                    delay(350L)
                    continue
                }

                when (val cap = captureFrameViaAccessibility()) {
                    is CaptureAttempt.Throttled -> {
                        delay(420L)
                        continue
                    }
                    is CaptureAttempt.UnsupportedOrError -> {
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) {
                            OverlayStateStore.setNotice(
                                "Accessibility screenshot unavailable — switch to MediaProjection fallback."
                            )
                            OverlayStateStore.setPaused(true)
                            delay(500L)
                        } else {
                            delay(450L)
                        }
                        continue
                    }
                    is CaptureAttempt.Frame -> {
                        consecutiveErrors = 0
                        val parsed = parser.parse(
                            frame = cap.pixelFrame,
                            width = cap.width,
                            height = cap.height,
                            config = state.roiConfig,
                            requireStability = true
                        )

                        when (parsed) {
                            is ParsedFrame.IdleOrMenu -> {
                                consecutiveIdleFrames++
                                if (lockedRec == null || consecutiveIdleFrames >= 3) {
                                    lockedBoardMask = -1L
                                    lockedTraySig = ""
                                    lockedConfig = null
                                    lockedRec = null
                                    lockedActive = null
                                    failedDropStreak = 0
                                    OverlayStateStore.setSearching()
                                }
                                delay(450L)
                            }
                            is ParsedFrame.Animating -> {
                                consecutiveIdleFrames = 0
                                delay(360L)
                            }
                            is ParsedFrame.ActiveBoard -> {
                                consecutiveIdleFrames = 0
                                val traySig = traySignature(parsed.tray)

                                val isSameBoard = lockedRec != null &&
                                    parsed.boardMask == lockedBoardMask &&
                                    traySig == lockedTraySig &&
                                    state.roiConfig == lockedConfig

                                val recToUse: MoveRecommendation? = if (isSameBoard) {
                                    lockedRec
                                } else {
                                    OverlayStateStore.setThinking(parsed.boardMask, parsed.emptyCellColorRgb)
                                    val solved = withContext(Dispatchers.Default) {
                                        BitboardSolver.solve(
                                            boardMask = parsed.boardMask,
                                            tray = parsed.tray,
                                            mode = state.roiConfig.solverMode,
                                            depth = state.roiConfig.thinkingDepth
                                        )
                                    }
                                    lockedBoardMask = parsed.boardMask
                                    lockedTraySig = traySig
                                    lockedConfig = state.roiConfig
                                    lockedRec = solved
                                    lockedActive = parsed
                                    OverlayStateStore.updateGuidance(
                                        boardMask = parsed.boardMask,
                                        themeRgb = parsed.emptyCellColorRgb,
                                        recommendation = solved
                                    )
                                    solved
                                }

                                val currentState = OverlayStateStore.state.value
                                if (currentState.roiConfig.operatingMode == OperatingMode.AUTOPLAY &&
                                    recToUse != null &&
                                    currentState.status != ServiceStatus.PAUSED &&
                                    currentState.status != ServiceStatus.STOPPED
                                ) {
                                    // Brief visual preview before executing gesture
                                    delay(140L)
                                    if (OverlayStateStore.state.value.status == ServiceStatus.PAUSED ||
                                        OverlayStateStore.state.value.status == ServiceStatus.STOPPED
                                    ) {
                                        continue
                                    }

                                    OverlayStateStore.setDragging(recToUse)
                                    val dragOk = performPieceDrag(
                                        config = currentState.roiConfig,
                                        rec = recToUse,
                                        screenW = screenWidthPx,
                                        screenH = screenHeightPx,
                                        learnedLiftDeltaCells = currentState.learnedLiftDeltaCells
                                    )

                                    if (!dragOk) {
                                        failedDropStreak++
                                        if (failedDropStreak >= 3) {
                                            OverlayStateStore.setPaused(
                                                true,
                                                "Autoplay paused — touch gesture was interrupted."
                                            )
                                        }
                                        delay(420L)
                                        continue
                                    }

                                    // Verify-After-Drop closed-loop check
                                    OverlayStateStore.setVerifying()
                                    delay(300L)
                                    parser.resetStability()

                                    val postFrame = awaitSettledPostDropBoard(currentState.roiConfig)
                                    val preActive = lockedActive
                                    if (postFrame != null && preActive != null) {
                                        when (
                                            val verdict = AutoplayMoveVerifier.verifyDrop(
                                                preBoardMask = preActive.boardMask,
                                                preTray = preActive.tray,
                                                executed = recToUse,
                                                postBoardMask = postFrame.boardMask,
                                                postTray = postFrame.tray
                                            )
                                        ) {
                                            is DropVerificationResult.Verified -> {
                                                failedDropStreak = 0
                                                OverlayStateStore.recordVerifiedMove(verdict.liftOffsetCorrectionCells)
                                                lockedBoardMask = -1L
                                                lockedTraySig = ""
                                                lockedRec = null
                                                lockedActive = null
                                            }
                                            is DropVerificationResult.NewTurnSpawned -> {
                                                failedDropStreak = 0
                                                OverlayStateStore.recordVerifiedMove(null)
                                                lockedBoardMask = -1L
                                                lockedTraySig = ""
                                                lockedRec = null
                                                lockedActive = null
                                            }
                                            is DropVerificationResult.NotApplied -> {
                                                failedDropStreak++
                                                lockedBoardMask = -1L
                                                lockedTraySig = ""
                                                lockedRec = null
                                                lockedActive = null
                                                if (failedDropStreak >= 3) {
                                                    OverlayStateStore.setPaused(
                                                        true,
                                                        "Autoplay paused — drop did not register. Adjust Drag Lift in Calibrate."
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // Force fresh re-parse next cycle
                                        lockedBoardMask = -1L
                                        lockedTraySig = ""
                                        lockedRec = null
                                        lockedActive = null
                                    }
                                } else {
                                    delay(360L)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun awaitSettledPostDropBoard(config: AdaptiveRoiConfig): ParsedFrame.ActiveBoard? {
        for (attempt in 0 until 4) {
            when (val cap = captureFrameViaAccessibility()) {
                is CaptureAttempt.Throttled -> delay(380L)
                is CaptureAttempt.UnsupportedOrError -> return null
                is CaptureAttempt.Frame -> {
                    val parsed = parser.parse(
                        frame = cap.pixelFrame,
                        width = cap.width,
                        height = cap.height,
                        config = config,
                        requireStability = false
                    )
                    if (parsed is ParsedFrame.ActiveBoard) return parsed
                    delay(260L)
                }
            }
        }
        return null
    }

    private fun stopNativeLoop() {
        nativeLoopJob?.cancel()
        nativeLoopJob = null
        mainHandler.post {
            overlayHost?.detach()
        }
        if (OverlayStateStore.state.value.captureSource == CaptureSource.ACCESSIBILITY) {
            OverlayStateStore.setStopped()
        }
    }

    private suspend fun captureFrameViaAccessibility(): CaptureAttempt {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CaptureAttempt.UnsupportedOrError
        }
        return captureFrameApi30()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureFrameApi30(): CaptureAttempt =
        suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            var hwBitmap: Bitmap? = null
                            var softBitmap: Bitmap? = null
                            try {
                                val buffer = screenshot.hardwareBuffer
                                hwBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                if (hwBitmap == null) {
                                    cont.resume(CaptureAttempt.UnsupportedOrError)
                                    return
                                }
                                val targetW = max(160, hwBitmap.width / 2)
                                val targetH = max(320, hwBitmap.height / 2)
                                val argb = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                if (argb == null) {
                                    cont.resume(CaptureAttempt.UnsupportedOrError)
                                    return
                                }
                                softBitmap = if (argb.width != targetW || argb.height != targetH) {
                                    val scaled = Bitmap.createScaledBitmap(argb, targetW, targetH, false)
                                    argb.recycle()
                                    scaled
                                } else {
                                    argb
                                }
                                val w = softBitmap.width
                                val h = softBitmap.height
                                val pixels = IntArray(w * h)
                                softBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                                cont.resume(
                                    CaptureAttempt.Frame(
                                        pixelFrame = IntArrayPixelFrame(pixels, w, h),
                                        width = w,
                                        height = h
                                    )
                                )
                            } catch (_: Throwable) {
                                if (cont.isActive) cont.resume(CaptureAttempt.UnsupportedOrError)
                            } finally {
                                softBitmap?.recycle()
                                hwBitmap?.recycle()
                                try {
                                    screenshot.hardwareBuffer.close()
                                } catch (_: Throwable) {
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (!cont.isActive) return
                            if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                                cont.resume(CaptureAttempt.Throttled)
                            } else {
                                cont.resume(CaptureAttempt.UnsupportedOrError)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(CaptureAttempt.UnsupportedOrError)
            }
        }

    private suspend fun performPieceDrag(
        config: AdaptiveRoiConfig,
        rec: MoveRecommendation,
        screenW: Int,
        screenH: Int,
        learnedLiftDeltaCells: Float
    ): Boolean {
        val slotBounds = config.traySlotBounds(rec.slotIndex)
        val startX = ((slotBounds.left + slotBounds.right) * 0.5f) * screenW
        val startY = ((slotBounds.top + slotBounds.bottom) * 0.5f) * screenH

        val cellHPx = config.cellHeight() * screenH
        val targetCenterCol = rec.targetCol + rec.piece.width * 0.5f
        val targetCenterRow = rec.targetRow + rec.piece.height * 0.5f
        val endX = ((config.gridLeft + targetCenterCol * config.cellWidth()) * screenW)
            .coerceIn(16f, screenW - 16f)
        val liftCells = (config.dragLiftOffsetCells + learnedLiftDeltaCells).coerceIn(-1.5f, 3.0f)
        val endY = ((config.gridTop + targetCenterRow * config.cellHeight()) * screenH + liftCells * cellHPx)
            .coerceIn(cellHPx * 0.5f, screenH - 48f)

        // Segment 1: Touch down and hold 45ms so the tray picks up the piece cleanly
        val touchX = startX
        val touchY = startY
        val pressEndY = startY + 1f
        val pressPath = Path().apply {
            moveTo(touchX, touchY)
            lineTo(touchX, pressEndY)
        }
        val pressStroke = GestureDescription.StrokeDescription(pressPath, 0L, 45L, true)
        if (!dispatchStroke(pressStroke)) return false

        // Segment 2: Smooth ease-in-out glide over 330ms to destination
        val glideEndY = endY - 1f
        val glidePath = Path().apply {
            moveTo(touchX, pressEndY)
            val steps = 16
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val eased = t * t * (3f - 2f * t)
                val px = touchX + (endX - touchX) * eased
                val py = pressEndY + (glideEndY - pressEndY) * eased
                lineTo(px, py)
            }
        }
        val glideStroke = pressStroke.continueStroke(glidePath, 0L, 330L, true)
        if (!dispatchStroke(glideStroke)) {
            // Release safely if interrupted mid-flight
            val releasePath = Path().apply {
                moveTo(endX, glideEndY)
                lineTo(endX, glideEndY)
            }
            dispatchStroke(glideStroke.continueStroke(releasePath, 0L, 20L, false))
            return false
        }

        // Segment 3: Hold steady for 75ms over target cell before lifting finger
        val settlePath = Path().apply {
            moveTo(endX, glideEndY)
            lineTo(endX, endY)
        }
        val settleStroke = glideStroke.continueStroke(settlePath, 0L, 75L, false)
        return dispatchStroke(settleStroke)
    }

    private suspend fun dispatchStroke(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                mainHandler
            )
            if (!accepted && cont.isActive) {
                cont.resume(false)
            }
        }

    private fun updateDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            screenWidthPx = bounds.width()
            screenHeightPx = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            screenWidthPx = dm.widthPixels
            screenHeightPx = dm.heightPixels
        }
    }

    private fun traySignature(tray: List<TraySlotState>): String =
        tray.joinToString("|") { slot ->
            when (slot) {
                is TraySlotState.Empty -> "E"
                is TraySlotState.Recognized -> "${slot.slotIndex}:${slot.piece.id}"
            }
        }
}
