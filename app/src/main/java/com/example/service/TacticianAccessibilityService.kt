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
import android.os.SystemClock
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
import com.example.engine.AutoLayoutCalibrator
import com.example.engine.AutoplayMoveVerifier
import com.example.engine.BitboardSolver
import com.example.engine.DropVerificationResult
import com.example.engine.IntArrayPixelFrame
import com.example.engine.MoveRecommendation
import com.example.engine.OperatingMode
import com.example.engine.ParsedFrame
import com.example.engine.PixelFrame
import com.example.engine.PreviewCalibrationSample
import com.example.engine.PreviewHoverDetector
import com.example.engine.TraySlotState
import com.example.overlay.OverlayWindowHost
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max
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
 * 1. Hold-open multi-segment GestureDescription piece dragging for Autoplay + live Preview-Snap correction.
 * 2. Non-destructive Guided 3-Point Preview Calibration solving 4-parameter affine screen/game kinematics.
 * 3. Zero-prompt AccessibilityService.takeScreenshot() board reading on Android 11-15+
 *    with automatic fallback signaling to MediaProjection when unsupported or restricted.
 */
@SuppressLint("AccessibilityPolicy")
class TacticianAccessibilityService : AccessibilityService() {

    class ActiveHoldDrag internal constructor(
        internal var currentStroke: GestureDescription.StrokeDescription,
        internal var currentX: Float,
        internal var currentY: Float
    )

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

        suspend fun executeAutoplayDrag(
            config: AdaptiveRoiConfig,
            rec: MoveRecommendation,
            screenW: Int,
            screenH: Int
        ): Boolean {
            val svc = activeInstance ?: return false
            return svc.performPieceDrag(config, rec, screenW, screenH)
        }

        suspend fun beginHoldDrag(
            startX: Float,
            startY: Float,
            toX: Float,
            toY: Float,
            pressMs: Long,
            glideMs: Long
        ): ActiveHoldDrag? {
            val svc = activeInstance ?: return null
            return svc.performBeginHoldDrag(startX, startY, toX, toY, pressMs, glideMs)
        }

        suspend fun nudgeHoldDrag(
            handle: ActiveHoldDrag,
            toX: Float,
            toY: Float,
            durationMs: Long
        ): Boolean {
            val svc = activeInstance ?: return false
            return svc.performNudgeHoldDrag(handle, toX, toY, durationMs)
        }

        suspend fun releaseHoldDrag(
            handle: ActiveHoldDrag,
            settleMs: Long
        ): Boolean {
            val svc = activeInstance ?: return false
            return svc.performReleaseHoldDrag(handle, settleMs)
        }
    }

    private sealed interface CaptureAttempt {
        data class Frame(
            val pixelFrame: PixelFrame,
            val width: Int,
            val height: Int,
            val capturedAtMs: Long
        ) : CaptureAttempt

        data object Throttled : CaptureAttempt
        data object UnsupportedOrError : CaptureAttempt
    }

    private data class SettledBoardResult(
        val activeBoard: ParsedFrame.ActiveBoard,
        val frame: CaptureAttempt.Frame
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val parser = AdaptiveScreenParser()

    private var prefs: PreferencesRepository? = null
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

        val repo = PreferencesRepository(applicationContext)
        prefs = repo
        serviceScope.launch {
            repo.configFlow.collectLatest { cfg ->
                OverlayStateStore.updateRoiConfig(cfg)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Frame-driven vision pipeline does not depend on UI node trees.
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
            var hasVerifiedLayoutForSession = false
            var carryoverFrame: CaptureAttempt.Frame? = null
            var carryoverParsed: ParsedFrame.ActiveBoard? = null

            while (isActive) {
                val state = OverlayStateStore.state.value
                if (state.status == ServiceStatus.PAUSED || state.status == ServiceStatus.STOPPED) {
                    carryoverFrame = null
                    carryoverParsed = null
                    delay(350L)
                    continue
                }

                val currentFrame: CaptureAttempt.Frame
                var preParsedBoard: ParsedFrame? = null

                if (carryoverFrame != null && carryoverParsed != null) {
                    currentFrame = carryoverFrame
                    preParsedBoard = carryoverParsed
                    carryoverFrame = null
                    carryoverParsed = null
                } else {
                    when (val cap = captureFrameViaAccessibility()) {
                        is CaptureAttempt.Throttled -> {
                            delay(340L)
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
                                delay(380L)
                            }
                            continue
                        }
                        is CaptureAttempt.Frame -> {
                            consecutiveErrors = 0
                            currentFrame = cap
                        }
                    }
                }

                updateDisplayMetrics()
                var activeConfig = state.roiConfig
                var parsed = preParsedBoard ?: parser.parse(
                    frame = currentFrame.pixelFrame,
                    width = currentFrame.width,
                    height = currentFrame.height,
                    config = activeConfig,
                    requireStability = true
                )

                val screenChanged =
                    activeConfig.calibratedScreenWidthPx != screenWidthPx ||
                        activeConfig.calibratedScreenHeightPx != screenHeightPx
                val needsAutoLayout =
                    screenChanged || (!hasVerifiedLayoutForSession && parsed is ParsedFrame.IdleOrMenu)

                if (needsAutoLayout) {
                    val detected = withContext(Dispatchers.Default) {
                        AutoLayoutCalibrator.detectBoardAndTrayLayout(
                            frame = currentFrame.pixelFrame,
                            width = currentFrame.width,
                            height = currentFrame.height,
                            baseConfig = activeConfig,
                            fullScreenWidthPx = screenWidthPx,
                            fullScreenHeightPx = screenHeightPx
                        )
                    }
                    if (detected != null) {
                        activeConfig = detected
                        hasVerifiedLayoutForSession = true
                        OverlayStateStore.updateRoiConfig(detected)
                        persistLayoutCalibration(detected)
                        parser.resetStability()
                        parsed = parser.parse(
                            frame = currentFrame.pixelFrame,
                            width = currentFrame.width,
                            height = currentFrame.height,
                            config = detected,
                            requireStability = true
                        )
                    }
                } else if (parsed is ParsedFrame.ActiveBoard) {
                    hasVerifiedLayoutForSession = true
                }

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
                            OverlayStateStore.resetComboOnNewGame()
                            OverlayStateStore.setSearching()
                        }
                        delay(420L)
                    }
                    is ParsedFrame.Animating -> {
                        consecutiveIdleFrames = 0
                        delay(340L)
                    }
                    is ParsedFrame.ActiveBoard -> {
                        consecutiveIdleFrames = 0

                        // Check if user triggered Guided 3-Point Preview Calibration
                        if (OverlayStateStore.state.value.pendingGuidedCalibration) {
                            runGuidedPreviewCalibration(activeConfig, currentFrame, parsed)
                            lockedBoardMask = -1L
                            lockedTraySig = ""
                            lockedRec = null
                            lockedActive = null
                            parser.resetStability()
                            delay(220L)
                            continue
                        }

                        val traySig = traySignature(parsed.tray)
                        val profile = activeConfig.globalProfile

                        val isSameBoard = lockedRec != null &&
                            lockedConfig != null &&
                            parsed.boardMask == lockedBoardMask &&
                            traySig == lockedTraySig &&
                            activeConfig.globalProfile == lockedConfig.globalProfile &&
                            activeConfig.solverMode == lockedConfig.solverMode &&
                            activeConfig.gridTop == lockedConfig.gridTop

                        val recToUse: MoveRecommendation? = if (isSameBoard) {
                            lockedRec
                        } else {
                            OverlayStateStore.setThinking(parsed.boardMask, parsed.emptyCellColorRgb)
                            val combo = OverlayStateStore.state.value.comboState
                            val solved = withContext(Dispatchers.Default) {
                                BitboardSolver.solve(
                                    boardMask = parsed.boardMask,
                                    tray = parsed.tray,
                                    mode = profile.solverMode,
                                    profile = profile,
                                    comboStreak = combo.comboStreak,
                                    placementsSinceClear = combo.placementsSinceClear
                                )
                            }
                            lockedBoardMask = parsed.boardMask
                            lockedTraySig = traySig
                            lockedConfig = activeConfig
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
                            if (profile.preDragPreviewMs > 0L) {
                                delay(profile.preDragPreviewMs)
                            }
                            if (OverlayStateStore.state.value.status == ServiceStatus.PAUSED ||
                                OverlayStateStore.state.value.status == ServiceStatus.STOPPED
                            ) {
                                continue
                            }

                            OverlayStateStore.setDragging(recToUse)
                            var dragConfig = OverlayStateStore.state.value.roiConfig
                            val useLiveSnap = profile.enableLivePreviewSnap || !dragConfig.isPreviewLocked

                            val dragOk = if (useLiveSnap) {
                                performLivePreviewSnapDrop(
                                    config = dragConfig,
                                    rec = recToUse,
                                    preFrame = currentFrame,
                                    preBoardMask = parsed.boardMask,
                                    onConfigRefined = { refined ->
                                        dragConfig = refined
                                        OverlayStateStore.updateRoiConfig(refined)
                                        persistMovementCalibration(refined)
                                    }
                                )
                            } else {
                                performPieceDrag(
                                    config = dragConfig,
                                    rec = recToUse,
                                    screenW = screenWidthPx,
                                    screenH = screenHeightPx
                                )
                            }

                            if (!dragOk) {
                                failedDropStreak++
                                if (failedDropStreak >= 3) {
                                    OverlayStateStore.setPaused(
                                        true,
                                        "Autoplay paused — touch gesture was interrupted."
                                    )
                                }
                                delay(380L)
                                continue
                            }

                            // Verify-After-Drop closed-loop check + post-drop frame carryover
                            OverlayStateStore.setVerifying()
                            delay(profile.postDropWaitMs)
                            parser.resetStability()

                            val settled = awaitSettledPostDropBoard(dragConfig)
                            val preActive = lockedActive
                            if (settled != null && preActive != null) {
                                when (
                                    val verdict = AutoplayMoveVerifier.verifyDrop(
                                        config = dragConfig,
                                        screenWidthPx = screenWidthPx,
                                        screenHeightPx = screenHeightPx,
                                        preBoardMask = preActive.boardMask,
                                        preTray = preActive.tray,
                                        executed = recToUse,
                                        postBoardMask = settled.activeBoard.boardMask,
                                        postTray = settled.activeBoard.tray
                                    )
                                ) {
                                    is DropVerificationResult.Verified -> {
                                        failedDropStreak = 0
                                        val calUpdate = verdict.calibrationUpdate
                                        if (calUpdate != null) {
                                            OverlayStateStore.updateRoiConfig(calUpdate.updatedConfig)
                                            persistMovementCalibration(calUpdate.updatedConfig)
                                            OverlayStateStore.recordVerifiedMove(
                                                calibrationSummary = calUpdate.summary,
                                                linesCleared = verdict.linesCleared
                                            )
                                        } else {
                                            OverlayStateStore.recordVerifiedMove(
                                                calibrationSummary = null,
                                                linesCleared = verdict.linesCleared
                                            )
                                        }
                                        // Carry settled frame straight into next solve cycle (0ms wait!)
                                        carryoverFrame = settled.frame
                                        carryoverParsed = settled.activeBoard
                                    }
                                    is DropVerificationResult.NewTurnSpawned -> {
                                        failedDropStreak = 0
                                        OverlayStateStore.recordVerifiedMove(
                                            calibrationSummary = null,
                                            linesCleared = verdict.linesCleared
                                        )
                                        carryoverFrame = settled.frame
                                        carryoverParsed = settled.activeBoard
                                    }
                                    is DropVerificationResult.NotApplied -> {
                                        failedDropStreak++
                                        val recovered = AutoplayMoveVerifier.recoverFromFailedDrop(
                                            dragConfig,
                                            recToUse
                                        )
                                        OverlayStateStore.updateRoiConfig(recovered)
                                        persistMovementCalibration(recovered)
                                        val maxFailures = if (dragConfig.dragCalibrationSamples < 2) 4 else 3
                                        if (failedDropStreak >= maxFailures) {
                                            OverlayStateStore.setPaused(
                                                true,
                                                "Autoplay paused — run Guided 3-Point Calibration in Calibrate."
                                            )
                                        }
                                    }
                                }
                            }
                            lockedBoardMask = -1L
                            lockedTraySig = ""
                            lockedRec = null
                            lockedActive = null
                        } else {
                            delay(340L)
                        }
                    }
                }
            }
        }
    }

    /**
     * Picks a piece, hovers over its target with touch held down, reads the game's live ghost preview,
     * nudges to exact cell alignment before lifting the finger, and refines saved offsets.
     */
    private suspend fun performLivePreviewSnapDrop(
        config: AdaptiveRoiConfig,
        rec: MoveRecommendation,
        preFrame: CaptureAttempt.Frame,
        preBoardMask: Long,
        onConfigRefined: suspend (AdaptiveRoiConfig) -> Unit
    ): Boolean {
        val profile = config.globalProfile
        val endpoints = config.computeDragEndpoints(
            slotIndex = rec.slotIndex,
            targetRow = rec.targetRow,
            targetCol = rec.targetCol,
            pieceWidth = rec.piece.width,
            pieceHeight = rec.piece.height,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val preCellColors = withContext(Dispatchers.Default) {
            PreviewHoverDetector.captureCellBaselines(
                preFrame.pixelFrame,
                preFrame.width,
                preFrame.height,
                config
            )
        }

        val hold = performBeginHoldDrag(
            startX = endpoints.startX,
            startY = endpoints.startY,
            toX = endpoints.endX,
            toY = endpoints.endY,
            pressMs = profile.pressDurationMs,
            glideMs = profile.glideDurationMs
        ) ?: return false

        // Respect Android Accessibility screenshot interval while holding piece suspended
        val elapsedSincePre = SystemClock.elapsedRealtime() - preFrame.capturedAtMs
        if (elapsedSincePre < 355L) {
            delay(355L - elapsedSincePre)
        }

        val hoverAttempt = captureFrameViaAccessibility()
        if (hoverAttempt is CaptureAttempt.Frame) {
            val match = withContext(Dispatchers.Default) {
                PreviewHoverDetector.detectHoveredPreview(
                    hoverFrame = hoverAttempt.pixelFrame,
                    width = hoverAttempt.width,
                    height = hoverAttempt.height,
                    config = config,
                    preBoardMask = preBoardMask,
                    preCellColors = preCellColors,
                    piece = rec.piece
                )
            }
            if (match != null && (match.row != rec.targetRow || match.col != rec.targetCol)) {
                val safeGainX = config.dragHorizontalGain.coerceIn(0.65f, 1.50f)
                val safeGainY = config.dragVerticalGain.coerceIn(0.65f, 2.00f)
                val errRow = match.row - rec.targetRow
                val errCol = match.col - rec.targetCol

                val correctedX = (hold.currentX - (errCol * endpoints.cellWidthPx) / safeGainX)
                    .coerceIn(endpoints.cellWidthPx * 0.3f, screenWidthPx - endpoints.cellWidthPx * 0.3f)
                val correctedY = (hold.currentY - (errRow * endpoints.cellHeightPx) / safeGainY)
                    .coerceIn(endpoints.cellHeightPx * 0.35f, screenHeightPx - 32f)

                performNudgeHoldDrag(hold, correctedX, correctedY, durationMs = 75L)
                val refined = AutoplayMoveVerifier.adjustFromSingleHoverObservation(
                    config = config,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    targetRow = rec.targetRow,
                    targetCol = rec.targetCol,
                    observedRow = match.row,
                    observedCol = match.col
                )
                onConfigRefined(refined)
            }
        }

        return performReleaseHoldDrag(hold, profile.settleDurationMs)
    }

    /**
     * Non-destructive Guided 3-Point Preview Calibration:
     * Picks up a tray piece, visits up to 3 spread-out board anchors with touch held open, reads the
     * actual translucent ghost preview at each probe point, then returns cleanly to the tray start
     * slot before lifting — fitting exact 4-parameter affine screen-space movement without dropping!
     */
    private suspend fun runGuidedPreviewCalibration(
        config: AdaptiveRoiConfig,
        startFrame: CaptureAttempt.Frame,
        parsed: ParsedFrame.ActiveBoard
    ) {
        val plan = PreviewHoverDetector.selectGuidedCalibrationPlan(parsed.boardMask, parsed.tray)
        if (plan == null) {
            OverlayStateStore.finishGuidedCalibration("Need open cells on board for Guided Calibration.")
            return
        }

        val preCellColors = withContext(Dispatchers.Default) {
            PreviewHoverDetector.captureCellBaselines(
                startFrame.pixelFrame,
                startFrame.width,
                startFrame.height,
                config
            )
        }

        val firstAnchor = plan.anchors.first()
        val firstEndpoints = config.computeDragEndpoints(
            slotIndex = plan.slotIndex,
            targetRow = firstAnchor.first,
            targetCol = firstAnchor.second,
            pieceWidth = plan.piece.width,
            pieceHeight = plan.piece.height,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )
        val startX = firstEndpoints.startX
        val startY = firstEndpoints.startY

        OverlayStateStore.setCalibratingStep(1, "Calibrating Point 1/${plan.anchors.size} (reading preview)…")
        val hold = performBeginHoldDrag(
            startX = startX,
            startY = startY,
            toX = firstEndpoints.endX,
            toY = firstEndpoints.endY,
            pressMs = 45L,
            glideMs = 240L
        )
        if (hold == null) {
            OverlayStateStore.finishGuidedCalibration("Guided Calibration canceled — touch interrupted.")
            return
        }

        val collectedSamples = ArrayList<PreviewCalibrationSample>(plan.anchors.size)
        var lastCaptureMs = startFrame.capturedAtMs

        try {
            for ((idx, anchor) in plan.anchors.withIndex()) {
                if (idx > 0) {
                    OverlayStateStore.setCalibratingStep(
                        idx + 1,
                        "Calibrating Point ${idx + 1}/${plan.anchors.size} (measuring offset)…"
                    )
                    val ep = config.computeDragEndpoints(
                        slotIndex = plan.slotIndex,
                        targetRow = anchor.first,
                        targetCol = anchor.second,
                        pieceWidth = plan.piece.width,
                        pieceHeight = plan.piece.height,
                        screenWidthPx = screenWidthPx,
                        screenHeightPx = screenHeightPx
                    )
                    if (!performNudgeHoldDrag(hold, ep.endX, ep.endY, durationMs = 200L)) break
                }

                // Wait out Android Accessibility screenshot rate-limit (~350ms) while holding piece open
                val elapsed = SystemClock.elapsedRealtime() - lastCaptureMs
                if (elapsed < 355L) delay(355L - elapsed)

                var hoverAttempt = captureFrameViaAccessibility()
                if (hoverAttempt is CaptureAttempt.Throttled) {
                    delay(260L)
                    hoverAttempt = captureFrameViaAccessibility()
                }
                if (hoverAttempt is CaptureAttempt.Frame) {
                    lastCaptureMs = hoverAttempt.capturedAtMs
                    val match = withContext(Dispatchers.Default) {
                        PreviewHoverDetector.detectHoveredPreview(
                            hoverFrame = hoverAttempt.pixelFrame,
                            width = hoverAttempt.width,
                            height = hoverAttempt.height,
                            config = config,
                            preBoardMask = parsed.boardMask,
                            preCellColors = preCellColors,
                            piece = plan.piece
                        )
                    }
                    if (match != null) {
                        collectedSamples.add(
                            PreviewCalibrationSample(
                                slotIndex = plan.slotIndex,
                                startX = startX,
                                startY = startY,
                                fingerX = hold.currentX,
                                fingerY = hold.currentY,
                                observedRow = match.row,
                                observedCol = match.col,
                                pieceWidth = plan.piece.width,
                                pieceHeight = plan.piece.height
                            )
                        )
                    }
                }
            }
        } finally {
            // Slide safely back into the tray slot BEFORE releasing so Block Blast cancels without placing
            performNudgeHoldDrag(hold, startX, startY, durationMs = 210L)
            performReleaseHoldDrag(hold, settleMs = 45L)
        }

        val calUpdate = if (collectedSamples.size >= 2) {
            AutoplayMoveVerifier.fitAffineFromPreviewSamples(
                config = config,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                samples = collectedSamples
            )
        } else {
            null
        }

        if (calUpdate != null) {
            OverlayStateStore.updateRoiConfig(calUpdate.updatedConfig)
            persistMovementCalibration(calUpdate.updatedConfig)
            OverlayStateStore.finishGuidedCalibration(calUpdate.summary)
        } else {
            OverlayStateStore.finishGuidedCalibration(
                "Need 2+ open preview spots to calibrate — pause & retry on cleaner board."
            )
        }
    }

    private suspend fun persistLayoutCalibration(detected: AdaptiveRoiConfig) {
        val repo = prefs ?: return
        repo.updateConfig { current ->
            current.copy(
                gridLeft = detected.gridLeft,
                gridTop = detected.gridTop,
                gridRight = detected.gridRight,
                gridBottom = detected.gridBottom,
                trayTop = detected.trayTop,
                trayBottom = detected.trayBottom,
                trayLeftMargin = detected.trayLeftMargin,
                trayRightMargin = detected.trayRightMargin,
                expectedMiniTileWidthFraction = detected.expectedMiniTileWidthFraction,
                calibratedScreenWidthPx = detected.calibratedScreenWidthPx,
                calibratedScreenHeightPx = detected.calibratedScreenHeightPx
            )
        }
    }

    private suspend fun persistMovementCalibration(calibrated: AdaptiveRoiConfig) {
        val repo = prefs ?: return
        repo.updateConfig { current ->
            current.copy(
                dragLiftOffsetCells = calibrated.dragLiftOffsetCells,
                dragVerticalGain = calibrated.dragVerticalGain,
                dragHorizontalOffsetCells = calibrated.dragHorizontalOffsetCells,
                dragHorizontalGain = calibrated.dragHorizontalGain,
                calibratedScreenWidthPx = calibrated.calibratedScreenWidthPx,
                calibratedScreenHeightPx = calibrated.calibratedScreenHeightPx,
                dragCalibrationSamples = calibrated.dragCalibrationSamples,
                isPreviewLocked = calibrated.isPreviewLocked
            )
        }
    }

    private suspend fun awaitSettledPostDropBoard(config: AdaptiveRoiConfig): SettledBoardResult? {
        for (attempt in 0 until 4) {
            when (val cap = captureFrameViaAccessibility()) {
                is CaptureAttempt.Throttled -> delay(350L)
                is CaptureAttempt.UnsupportedOrError -> return null
                is CaptureAttempt.Frame -> {
                    val parsed = parser.parse(
                        frame = cap.pixelFrame,
                        width = cap.width,
                        height = cap.height,
                        config = config,
                        requireStability = false
                    )
                    if (parsed is ParsedFrame.ActiveBoard) {
                        return SettledBoardResult(parsed, cap)
                    }
                    delay(220L)
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
                                        height = h,
                                        capturedAtMs = SystemClock.elapsedRealtime()
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

    private suspend fun performBeginHoldDrag(
        startX: Float,
        startY: Float,
        toX: Float,
        toY: Float,
        pressMs: Long,
        glideMs: Long
    ): ActiveHoldDrag? {
        val pressEndY = startY + 1f
        val pressPath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, pressEndY)
        }
        val pressStroke = GestureDescription.StrokeDescription(pressPath, 0L, pressMs.coerceAtLeast(18L), true)
        if (!dispatchStroke(pressStroke)) return null

        val glidePath = Path().apply {
            moveTo(startX, pressEndY)
            val steps = 14
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val eased = t * t * (3f - 2f * t)
                lineTo(startX + (toX - startX) * eased, pressEndY + (toY - pressEndY) * eased)
            }
        }
        val glideStroke = pressStroke.continueStroke(glidePath, 0L, glideMs.coerceAtLeast(80L), true)
        if (!dispatchStroke(glideStroke)) {
            val cancelPath = Path().apply { moveTo(startX, pressEndY); lineTo(startX, pressEndY) }
            dispatchStroke(glideStroke.continueStroke(cancelPath, 0L, 16L, false))
            return null
        }
        return ActiveHoldDrag(glideStroke, toX, toY)
    }

    private suspend fun performNudgeHoldDrag(
        handle: ActiveHoldDrag,
        toX: Float,
        toY: Float,
        durationMs: Long
    ): Boolean {
        val fromX = handle.currentX
        val fromY = handle.currentY
        val path = Path().apply {
            moveTo(fromX, fromY)
            val steps = 8
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val eased = t * t * (3f - 2f * t)
                lineTo(fromX + (toX - fromX) * eased, fromY + (toY - fromY) * eased)
            }
        }
        val nextStroke = handle.currentStroke.continueStroke(path, 0L, durationMs.coerceAtLeast(24L), true)
        val ok = dispatchStroke(nextStroke)
        handle.currentStroke = nextStroke
        if (ok) {
            handle.currentX = toX
            handle.currentY = toY
        }
        return ok
    }

    private suspend fun performReleaseHoldDrag(
        handle: ActiveHoldDrag,
        settleMs: Long
    ): Boolean {
        val endX = handle.currentX
        val endY = handle.currentY
        val releasePath = Path().apply {
            moveTo(endX, endY)
            lineTo(endX, endY)
        }
        val releaseStroke = handle.currentStroke.continueStroke(
            releasePath,
            0L,
            settleMs.coerceAtLeast(20L),
            false
        )
        return dispatchStroke(releaseStroke)
    }

    private suspend fun performPieceDrag(
        config: AdaptiveRoiConfig,
        rec: MoveRecommendation,
        screenW: Int,
        screenH: Int
    ): Boolean {
        updateDisplayMetrics()
        val effectiveW = if (screenWidthPx > 0) screenWidthPx else screenW
        val effectiveH = if (screenHeightPx > 0) screenHeightPx else screenH
        val profile = config.globalProfile

        val endpoints = config.computeDragEndpoints(
            slotIndex = rec.slotIndex,
            targetRow = rec.targetRow,
            targetCol = rec.targetCol,
            pieceWidth = rec.piece.width,
            pieceHeight = rec.piece.height,
            screenWidthPx = effectiveW,
            screenHeightPx = effectiveH
        )

        val hold = performBeginHoldDrag(
            startX = endpoints.startX,
            startY = endpoints.startY,
            toX = endpoints.endX,
            toY = (endpoints.endY - 1f).coerceAtLeast(8f),
            pressMs = profile.pressDurationMs,
            glideMs = profile.glideDurationMs
        ) ?: return false

        return performReleaseHoldDrag(hold, profile.settleDurationMs)
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
