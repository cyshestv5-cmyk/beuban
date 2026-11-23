package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.CaptureSource
import com.example.data.OverlayStateStore
import com.example.data.PreferencesRepository
import com.example.data.ServiceStatus
import com.example.engine.AdaptiveRoiConfig
import com.example.engine.AdaptiveScreenParser
import com.example.engine.AutoLayoutCalibrator
import com.example.engine.AutoplayMoveVerifier
import com.example.engine.BitboardSolver
import com.example.engine.ByteBufferPixelFrame
import com.example.engine.DropVerificationResult
import com.example.engine.IntArrayPixelFrame
import com.example.engine.MoveRecommendation
import com.example.engine.OperatingMode
import com.example.engine.ParsedFrame
import com.example.engine.PreviewCalibrationSample
import com.example.engine.PreviewHoverDetector
import com.example.engine.TraySlotState
import com.example.overlay.OverlayWindowHost
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android 15+ compliant MediaProjection Foreground Service.
 * Serves as the high-frequency 60Hz fallback screen reader on Android 8-10 or whenever
 * Accessibility screenshot capture is unavailable/throttled, coordinating with
 * TacticianAccessibilityService for Live Preview-Snap Autoplay and Guided 3-Point Calibration.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_CAPTURE"
        const val ACTION_STOP = "com.example.action.STOP_CAPTURE"
        const val ACTION_TOGGLE_PAUSE = "com.example.action.TOGGLE_PAUSE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "tactician_capture_channel"
        private const val NOTIFICATION_ID = 4107
        private const val ACTIVE_POLL_MS = 160L
        private const val IDLE_POLL_MS = 400L
    }

    private data class SnapshotFrame(
        val pixelFrame: IntArrayPixelFrame,
        val width: Int,
        val height: Int
    )

    private data class AcquiredParsedFrame(
        val parsed: ParsedFrame,
        val updatedConfig: AdaptiveRoiConfig?,
        val preCellColors: IntArray?
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val parser = AdaptiveScreenParser()

    private var prefs: PreferencesRepository? = null
    private var windowManager: WindowManager? = null
    private var overlayHost: OverlayWindowHost? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var pollJob: Job? = null

    private var screenWidthPx = 1080
    private var screenHeightPx = 2400
    private var screenDensityDpi = 420
    private var isContentVisible = true

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            shutdownService()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0 && (width != screenWidthPx || height != screenHeightPx)) {
                screenWidthPx = width
                screenHeightPx = height
                recreateVirtualDisplay()
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            isContentVisible = isVisible
            if (!isVisible) {
                OverlayStateStore.setSearching()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayHost = OverlayWindowHost(context = this, useAccessibilityOverlayType = false)
        updateDisplayMetrics()
        createNotificationChannel()

        val repo = PreferencesRepository(applicationContext)
        prefs = repo
        serviceScope.launch {
            repo.configFlow.collectLatest { cfg ->
                OverlayStateStore.updateRoiConfig(cfg)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownService()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                val currentlyPaused = OverlayStateStore.state.value.status == ServiceStatus.PAUSED
                OverlayStateStore.setPaused(!currentlyPaused)
                updateNotification()
                return START_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                TacticianAccessibilityService.stopNativeSession()

                startForegroundWithType(isPaused = false)
                startCaptureSession(resultCode, resultData)
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithType(isPaused: Boolean) {
        val notification = buildNotification(isPaused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCaptureSession(resultCode: Int, resultData: Intent) {
        releaseCaptureResources()
        updateDisplayMetrics()
        parser.resetStability()

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            shutdownService()
            return
        }
        mediaProjection = projection

        val thread = HandlerThread("TacticianCaptureThread").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        projection.registerCallback(projectionCallback, mainHandler)

        recreateVirtualDisplay()
        overlayHost?.attach(
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            onTogglePause = {
                val paused = OverlayStateStore.state.value.status == ServiceStatus.PAUSED
                OverlayStateStore.setPaused(!paused)
                updateNotification()
            },
            onStop = { shutdownService() }
        )
        OverlayStateStore.setServiceStarted(
            widthPx = screenWidthPx,
            heightPx = screenHeightPx,
            source = CaptureSource.MEDIA_PROJECTION
        )
        startPollingLoop()
    }

    private fun recreateVirtualDisplay() {
        val projection = mediaProjection ?: return
        virtualDisplay?.release()
        imageReader?.close()

        val capW = max(160, screenWidthPx / 2)
        val capH = max(320, screenHeightPx / 2)

        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "BlockBlastTacticianDisplay",
            capW,
            capH,
            screenDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
    }

    private fun startPollingLoop() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            var lockedBoardMask = -1L
            var lockedTraySig = ""
            var lockedConfig: AdaptiveRoiConfig? = null
            var lockedRec: MoveRecommendation? = null
            var lockedActive: ParsedFrame.ActiveBoard? = null
            var consecutiveIdleFrames = 0
            var failedDropStreak = 0
            var hasVerifiedLayoutForSession = false
            var carryoverBoard: ParsedFrame.ActiveBoard? = null
            var carryoverBaselines: IntArray? = null

            while (isActive) {
                val state = OverlayStateStore.state.value
                if (state.status == ServiceStatus.PAUSED || !isContentVisible) {
                    carryoverBoard = null
                    carryoverBaselines = null
                    delay(IDLE_POLL_MS)
                    continue
                }

                updateDisplayMetrics()
                var activeConfig = state.roiConfig
                val screenChanged =
                    activeConfig.calibratedScreenWidthPx != screenWidthPx ||
                        activeConfig.calibratedScreenHeightPx != screenHeightPx

                val parsed: ParsedFrame
                var preCellColors: IntArray? = null

                if (carryoverBoard != null && !screenChanged) {
                    parsed = carryoverBoard
                    preCellColors = carryoverBaselines
                    carryoverBoard = null
                    carryoverBaselines = null
                } else {
                    val acquired = acquireParsedFrameWithAutoLayout(
                        config = activeConfig,
                        requireStability = true,
                        allowAutoLayout = screenChanged || !hasVerifiedLayoutForSession
                    )
                    if (acquired == null) {
                        delay(ACTIVE_POLL_MS)
                        continue
                    }
                    parsed = acquired.parsed
                    preCellColors = acquired.preCellColors
                    if (acquired.updatedConfig != null) {
                        activeConfig = acquired.updatedConfig
                        hasVerifiedLayoutForSession = true
                        OverlayStateStore.updateRoiConfig(acquired.updatedConfig)
                        persistLayoutCalibration(acquired.updatedConfig)
                    } else if (parsed is ParsedFrame.ActiveBoard) {
                        hasVerifiedLayoutForSession = true
                    }
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
                        delay(IDLE_POLL_MS)
                    }
                    is ParsedFrame.Animating -> {
                        consecutiveIdleFrames = 0
                        delay(ACTIVE_POLL_MS)
                    }
                    is ParsedFrame.ActiveBoard -> {
                        consecutiveIdleFrames = 0

                        // Execute non-destructive Guided 3-Point Preview Calibration if armed
                        if (OverlayStateStore.state.value.pendingGuidedCalibration) {
                            if (TacticianAccessibilityService.isServiceConnected.value) {
                                runGuidedPreviewCalibration(activeConfig, parsed, preCellColors)
                            } else {
                                OverlayStateStore.finishGuidedCalibration(
                                    "Enable Accessibility Service first so Guided Calibration can probe."
                                )
                            }
                            lockedBoardMask = -1L
                            lockedTraySig = ""
                            lockedRec = null
                            lockedActive = null
                            parser.resetStability()
                            delay(200L)
                            continue
                        }

                        val traySig = traySignature(parsed.tray)
                        val profile = activeConfig.globalProfile

                        val isUnchanged = lockedRec != null &&
                            lockedConfig != null &&
                            parsed.boardMask == lockedBoardMask &&
                            traySig == lockedTraySig &&
                            activeConfig.globalProfile == lockedConfig.globalProfile &&
                            activeConfig.solverMode == lockedConfig.solverMode &&
                            activeConfig.gridTop == lockedConfig.gridTop

                        val recToUse: MoveRecommendation? = if (isUnchanged) {
                            lockedRec
                        } else {
                            OverlayStateStore.setThinking(parsed.boardMask, parsed.emptyCellColorRgb)
                            val combo = OverlayStateStore.state.value.comboState
                            val rec = withContext(Dispatchers.Default) {
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
                            lockedRec = rec
                            lockedActive = parsed
                            OverlayStateStore.updateGuidance(
                                boardMask = parsed.boardMask,
                                themeRgb = parsed.emptyCellColorRgb,
                                recommendation = rec
                            )
                            rec
                        }

                        val currentState = OverlayStateStore.state.value
                        if (currentState.roiConfig.operatingMode == OperatingMode.AUTOPLAY &&
                            recToUse != null &&
                            TacticianAccessibilityService.isServiceConnected.value &&
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
                            val baselines = preCellColors
                            val useLiveSnap = (profile.enableLivePreviewSnap || !dragConfig.isPreviewLocked) &&
                                baselines != null

                            val dragOk = if (useLiveSnap && baselines != null) {
                                performLivePreviewSnapDrop(
                                    config = dragConfig,
                                    rec = recToUse,
                                    preBoardMask = parsed.boardMask,
                                    preCellColors = baselines,
                                    onConfigRefined = { refined ->
                                        dragConfig = refined
                                        OverlayStateStore.updateRoiConfig(refined)
                                        persistMovementCalibration(refined)
                                    }
                                )
                            } else {
                                TacticianAccessibilityService.executeAutoplayDrag(
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
                                delay(320L)
                                continue
                            }

                            OverlayStateStore.setVerifying()
                            delay(profile.postDropWaitMs)
                            parser.resetStability()

                            val settled = awaitSettledPostDropBoard(dragConfig)
                            val preActive = lockedActive
                            if (settled != null && preActive != null) {
                                val postBoard = settled.first
                                when (
                                    val verdict = AutoplayMoveVerifier.verifyDrop(
                                        config = dragConfig,
                                        screenWidthPx = screenWidthPx,
                                        screenHeightPx = screenHeightPx,
                                        preBoardMask = preActive.boardMask,
                                        preTray = preActive.tray,
                                        executed = recToUse,
                                        postBoardMask = postBoard.boardMask,
                                        postTray = postBoard.tray
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
                                        carryoverBoard = postBoard
                                        carryoverBaselines = settled.second
                                    }
                                    is DropVerificationResult.NewTurnSpawned -> {
                                        failedDropStreak = 0
                                        OverlayStateStore.recordVerifiedMove(
                                            calibrationSummary = null,
                                            linesCleared = verdict.linesCleared
                                        )
                                        carryoverBoard = postBoard
                                        carryoverBaselines = settled.second
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
                            delay(ACTIVE_POLL_MS)
                        }
                    }
                }
            }
        }
    }

    private suspend fun performLivePreviewSnapDrop(
        config: AdaptiveRoiConfig,
        rec: MoveRecommendation,
        preBoardMask: Long,
        preCellColors: IntArray,
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

        val hold = TacticianAccessibilityService.beginHoldDrag(
            startX = endpoints.startX,
            startY = endpoints.startY,
            toX = endpoints.endX,
            toY = endpoints.endY,
            pressMs = profile.pressDurationMs,
            glideMs = profile.glideDurationMs
        ) ?: return false

        // MediaProjection 60Hz only needs ~45ms for the game render frame to show the ghost shadow
        delay(45L)
        val snap = acquireSnapshotFrame(config)
        if (snap != null) {
            val match = withContext(Dispatchers.Default) {
                PreviewHoverDetector.detectHoveredPreview(
                    hoverFrame = snap.pixelFrame,
                    width = snap.width,
                    height = snap.height,
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

                TacticianAccessibilityService.nudgeHoldDrag(hold, correctedX, correctedY, durationMs = 70L)
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

        return TacticianAccessibilityService.releaseHoldDrag(hold, profile.settleDurationMs)
    }

    private suspend fun runGuidedPreviewCalibration(
        config: AdaptiveRoiConfig,
        parsed: ParsedFrame.ActiveBoard,
        initialBaselines: IntArray?
    ) {
        val plan = PreviewHoverDetector.selectGuidedCalibrationPlan(parsed.boardMask, parsed.tray)
        if (plan == null) {
            OverlayStateStore.finishGuidedCalibration("Need open cells on board for Guided Calibration.")
            return
        }

        val baselines = initialBaselines ?: run {
            val snap = acquireSnapshotFrame(config)
            if (snap != null) {
                PreviewHoverDetector.captureCellBaselines(snap.pixelFrame, snap.width, snap.height, config)
            } else {
                null
            }
        }
        if (baselines == null) {
            OverlayStateStore.finishGuidedCalibration("Unable to read frame for Guided Calibration.")
            return
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
        val hold = TacticianAccessibilityService.beginHoldDrag(
            startX = startX,
            startY = startY,
            toX = firstEndpoints.endX,
            toY = firstEndpoints.endY,
            pressMs = 45L,
            glideMs = 230L
        )
        if (hold == null) {
            OverlayStateStore.finishGuidedCalibration("Guided Calibration canceled — touch interrupted.")
            return
        }

        val collectedSamples = ArrayList<PreviewCalibrationSample>(plan.anchors.size)
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
                    if (!TacticianAccessibilityService.nudgeHoldDrag(hold, ep.endX, ep.endY, durationMs = 190L)) break
                }

                delay(65L)
                val snap = acquireSnapshotFrame(config)
                if (snap != null) {
                    val match = withContext(Dispatchers.Default) {
                        PreviewHoverDetector.detectHoveredPreview(
                            hoverFrame = snap.pixelFrame,
                            width = snap.width,
                            height = snap.height,
                            config = config,
                            preBoardMask = parsed.boardMask,
                            preCellColors = baselines,
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
            TacticianAccessibilityService.nudgeHoldDrag(hold, startX, startY, durationMs = 200L)
            TacticianAccessibilityService.releaseHoldDrag(hold, settleMs = 45L)
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

    private suspend fun awaitSettledPostDropBoard(
        config: AdaptiveRoiConfig
    ): Pair<ParsedFrame.ActiveBoard, IntArray?>? {
        for (attempt in 0 until 5) {
            val acquired = acquireParsedFrameWithAutoLayout(
                config = config,
                requireStability = false,
                allowAutoLayout = false
            )
            if (acquired != null && acquired.parsed is ParsedFrame.ActiveBoard) {
                return acquired.parsed to acquired.preCellColors
            }
            delay(160L)
        }
        return null
    }

    private fun acquireSnapshotFrame(config: AdaptiveRoiConfig): SnapshotFrame? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return null

        return try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val src = ByteBufferPixelFrame(
                buffer = plane.buffer,
                width = w,
                height = h,
                pixelStride = plane.pixelStride,
                rowStride = plane.rowStride
            )
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    pixels[rowOff + x] = src.pixelAt(x, y)
                }
            }
            SnapshotFrame(IntArrayPixelFrame(pixels, w, h), w, h)
        } finally {
            image.close()
        }
    }

    private fun acquireParsedFrameWithAutoLayout(
        config: AdaptiveRoiConfig,
        requireStability: Boolean,
        allowAutoLayout: Boolean
    ): AcquiredParsedFrame? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val frame = ByteBufferPixelFrame(
                buffer = plane.buffer,
                width = image.width,
                height = image.height,
                pixelStride = plane.pixelStride,
                rowStride = plane.rowStride
            )
            var parsed = parser.parse(
                frame = frame,
                width = image.width,
                height = image.height,
                config = config,
                requireStability = requireStability
            )
            var updatedCfg: AdaptiveRoiConfig? = null
            val screenChanged =
                config.calibratedScreenWidthPx != screenWidthPx ||
                    config.calibratedScreenHeightPx != screenHeightPx
            if (allowAutoLayout && (screenChanged || parsed is ParsedFrame.IdleOrMenu)) {
                val detected = AutoLayoutCalibrator.detectBoardAndTrayLayout(
                    frame = frame,
                    width = image.width,
                    height = image.height,
                    baseConfig = config,
                    fullScreenWidthPx = screenWidthPx,
                    fullScreenHeightPx = screenHeightPx
                )
                if (detected != null) {
                    updatedCfg = detected
                    parser.resetStability()
                    parsed = parser.parse(
                        frame = frame,
                        width = image.width,
                        height = image.height,
                        config = detected,
                        requireStability = requireStability
                    )
                }
            }
            val baselines = if (parsed is ParsedFrame.ActiveBoard) {
                PreviewHoverDetector.captureCellBaselines(
                    frame = frame,
                    width = image.width,
                    height = image.height,
                    config = updatedCfg ?: config
                )
            } else {
                null
            }
            AcquiredParsedFrame(parsed, updatedCfg, baselines)
        } finally {
            image.close()
        }
    }

    private fun traySignature(tray: List<TraySlotState>): String =
        tray.joinToString("|") { slot ->
            when (slot) {
                is TraySlotState.Empty -> "E"
                is TraySlotState.Recognized -> "${slot.slotIndex}:${slot.piece.id}"
            }
        }

    private fun releaseCaptureResources() {
        pollJob?.cancel()
        pollJob = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    private fun shutdownService() {
        overlayHost?.detach()
        releaseCaptureResources()
        if (OverlayStateStore.state.value.captureSource == CaptureSource.MEDIA_PROJECTION) {
            OverlayStateStore.setStopped()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        overlayHost?.detach()
        releaseCaptureResources()
        if (OverlayStateStore.state.value.captureSource == CaptureSource.MEDIA_PROJECTION) {
            OverlayStateStore.setStopped()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateDisplayMetrics() {
        val wm = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            screenWidthPx = bounds.width()
            screenHeightPx = bounds.height()
            screenDensityDpi = resources.configuration.densityDpi
        } else {
            val dm = resources.displayMetrics
            screenWidthPx = dm.widthPixels
            screenHeightPx = dm.heightPixels
            screenDensityDpi = dm.densityDpi
        }
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val isPaused = OverlayStateStore.state.value.status == ServiceStatus.PAUSED
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(isPaused))
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                getString(if (isPaused) R.string.notif_text_paused else R.string.notif_text_active)
            )
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                0,
                getString(if (isPaused) R.string.action_resume else R.string.action_pause),
                pauseIntent
            )
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }
}
