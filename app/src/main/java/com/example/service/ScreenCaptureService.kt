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
import com.example.engine.AutoplayMoveVerifier
import com.example.engine.BitboardSolver
import com.example.engine.ByteBufferPixelFrame
import com.example.engine.DropVerificationResult
import com.example.engine.MoveRecommendation
import com.example.engine.OperatingMode
import com.example.engine.ParsedFrame
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
 * Serves as the automatic fallback screen reader on Android 8-10 or whenever Accessibility
 * screenshot capture is unavailable/throttled, and coordinates with TacticianAccessibilityService
 * for Autoplay gesture execution + Verify-After-Drop.
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
        private const val ACTIVE_POLL_MS = 200L
        private const val IDLE_POLL_MS = 450L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val parser = AdaptiveScreenParser()

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

        val prefs = PreferencesRepository(applicationContext)
        serviceScope.launch {
            prefs.configFlow.collectLatest { cfg ->
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

                // Ensure native Accessibility capture loop yields before starting MediaProjection stream
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

            while (isActive) {
                val state = OverlayStateStore.state.value
                if (state.status == ServiceStatus.PAUSED || !isContentVisible) {
                    delay(IDLE_POLL_MS)
                    continue
                }

                val parsed = acquireParsedFrame(state.roiConfig, requireStability = true)
                if (parsed == null) {
                    delay(ACTIVE_POLL_MS)
                    continue
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
                        val traySig = traySignature(parsed.tray)

                        val isUnchanged = lockedRec != null &&
                            parsed.boardMask == lockedBoardMask &&
                            traySig == lockedTraySig &&
                            state.roiConfig == lockedConfig

                        val recToUse: MoveRecommendation? = if (isUnchanged) {
                            lockedRec
                        } else {
                            OverlayStateStore.setThinking(parsed.boardMask, parsed.emptyCellColorRgb)
                            val rec = withContext(Dispatchers.Default) {
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
                            delay(140L)
                            if (OverlayStateStore.state.value.status == ServiceStatus.PAUSED) {
                                continue
                            }
                            OverlayStateStore.setDragging(recToUse)
                            val dragOk = TacticianAccessibilityService.executeAutoplayDrag(
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
                                delay(350L)
                                continue
                            }

                            OverlayStateStore.setVerifying()
                            delay(280L)
                            parser.resetStability()

                            val postBoard = awaitSettledPostDropBoard(currentState.roiConfig)
                            val preActive = lockedActive
                            if (postBoard != null && preActive != null) {
                                when (
                                    val verdict = AutoplayMoveVerifier.verifyDrop(
                                        preBoardMask = preActive.boardMask,
                                        preTray = preActive.tray,
                                        executed = recToUse,
                                        postBoardMask = postBoard.boardMask,
                                        postTray = postBoard.tray
                                    )
                                ) {
                                    is DropVerificationResult.Verified -> {
                                        failedDropStreak = 0
                                        OverlayStateStore.recordVerifiedMove(verdict.liftOffsetCorrectionCells)
                                    }
                                    is DropVerificationResult.NewTurnSpawned -> {
                                        failedDropStreak = 0
                                        OverlayStateStore.recordVerifiedMove(null)
                                    }
                                    is DropVerificationResult.NotApplied -> {
                                        failedDropStreak++
                                        if (failedDropStreak >= 3) {
                                            OverlayStateStore.setPaused(
                                                true,
                                                "Autoplay paused — drop did not register. Adjust Drag Lift in Calibrate."
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

    private suspend fun awaitSettledPostDropBoard(config: AdaptiveRoiConfig): ParsedFrame.ActiveBoard? {
        for (attempt in 0 until 5) {
            val frame = acquireParsedFrame(config, requireStability = false)
            if (frame is ParsedFrame.ActiveBoard) return frame
            delay(180L)
        }
        return null
    }

    private fun acquireParsedFrame(config: AdaptiveRoiConfig, requireStability: Boolean): ParsedFrame? {
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
            parser.parse(
                frame = frame,
                width = image.width,
                height = image.height,
                config = config,
                requireStability = requireStability
            )
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
