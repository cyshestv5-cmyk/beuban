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
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.R
import com.example.data.OverlayStateStore
import com.example.data.PreferencesRepository
import com.example.data.ServiceStatus
import com.example.engine.AdaptiveScreenParser
import com.example.engine.BitboardSolver
import com.example.engine.ByteBufferPixelFrame
import com.example.engine.ParsedFrame
import com.example.overlay.BoardHighlightOverlay
import com.example.overlay.FloatingControlPill
import com.example.overlay.ServiceComposeLifecycleOwner
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private val parser = AdaptiveScreenParser()

    private var windowManager: WindowManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var pollJob: Job? = null

    private var boardOverlayView: ComposeView? = null
    private var pillOverlayView: ComposeView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: ServiceComposeLifecycleOwner? = null

    private var screenWidthPx = 1080
    private var screenHeightPx = 2400
    private var screenDensityDpi = 420
    private var isContentVisible = true

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Triggered by screen lock, Android 15 status bar chip, or another projection session
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

                // Android 14/15 requirement: startForeground with MEDIA_PROJECTION type BEFORE getMediaProjection
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

        // Android 14/15 requirement: register callback BEFORE createVirtualDisplay.
        // Dispatch callbacks on main looper so WindowManager overlay teardown and Lifecycle events are thread-safe.
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        recreateVirtualDisplay()
        attachComposeOverlaysIfPermitted()
        OverlayStateStore.setServiceStarted(screenWidthPx, screenHeightPx)
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
            var lastBoardMask = -1L
            var lastTraySig = ""
            var lastConfig: com.example.engine.AdaptiveRoiConfig? = null
            while (isActive) {
                val state = OverlayStateStore.state.value
                if (state.status == ServiceStatus.PAUSED || !isContentVisible) {
                    delay(IDLE_POLL_MS)
                    continue
                }

                val reader = imageReader
                if (reader == null) {
                    delay(IDLE_POLL_MS)
                    continue
                }

                val image = try {
                    reader.acquireLatestImage()
                } catch (_: IllegalStateException) {
                    null
                }

                if (image == null) {
                    delay(ACTIVE_POLL_MS)
                    continue
                }

                val parsed = try {
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
                        config = state.roiConfig,
                        requireStability = true
                    )
                } finally {
                    image.close()
                }

                when (parsed) {
                    is ParsedFrame.IdleOrMenu -> {
                        lastBoardMask = -1L
                        lastTraySig = ""
                        lastConfig = null
                        OverlayStateStore.setSearching()
                        delay(IDLE_POLL_MS)
                    }
                    is ParsedFrame.Animating -> {
                        delay(ACTIVE_POLL_MS)
                    }
                    is ParsedFrame.ActiveBoard -> {
                        val traySig = parsed.tray.joinToString("|") { slot ->
                            when (slot) {
                                is com.example.engine.TraySlotState.Empty -> "E"
                                is com.example.engine.TraySlotState.Recognized -> "${slot.slotIndex}:${slot.piece.id}"
                            }
                        }
                        if (parsed.boardMask != lastBoardMask ||
                            traySig != lastTraySig ||
                            state.roiConfig != lastConfig
                        ) {
                            lastBoardMask = parsed.boardMask
                            lastTraySig = traySig
                            lastConfig = state.roiConfig
                            val rec = BitboardSolver.solve(
                                boardMask = parsed.boardMask,
                                tray = parsed.tray,
                                mode = state.roiConfig.solverMode
                            )
                            OverlayStateStore.updateGuidance(
                                boardMask = parsed.boardMask,
                                themeRgb = parsed.emptyCellColorRgb,
                                recommendation = rec
                            )
                        }
                        delay(ACTIVE_POLL_MS)
                    }
                }
            }
        }
    }

    private fun attachComposeOverlaysIfPermitted() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = windowManager ?: return
        if (boardOverlayView != null) return

        val lifecycleOwner = ServiceComposeLifecycleOwner().also { it.onCreate() }
        overlayLifecycleOwner = lifecycleOwner

        val boardView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val state by OverlayStateStore.state.collectAsState()
                BoardHighlightOverlay(runtimeState = state)
            }
        }
        val boardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        val pParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidthPx * 0.04f).roundToInt()
            y = (screenHeightPx * 0.14f).roundToInt()
        }
        pillParams = pParams

        val pillView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val state by OverlayStateStore.state.collectAsState()
                FloatingControlPill(
                    status = state.status,
                    onTogglePause = {
                        val paused = state.status == ServiceStatus.PAUSED
                        OverlayStateStore.setPaused(!paused)
                        updateNotification()
                    },
                    onStop = { shutdownService() },
                    onDragDelta = { dx, dy ->
                        pParams.x = (pParams.x + dx.roundToInt()).coerceIn(0, max(0, screenWidthPx - width))
                        pParams.y = (pParams.y + dy.roundToInt()).coerceIn(64, max(64, screenHeightPx - height))
                        try {
                            wm.updateViewLayout(this, pParams)
                        } catch (_: IllegalArgumentException) {
                        }
                    },
                    onDragEnd = {
                        // Snap gently to nearest horizontal screen edge
                        val mid = screenWidthPx / 2
                        pParams.x = if (pParams.x + width / 2 < mid) {
                            (screenWidthPx * 0.03f).roundToInt()
                        } else {
                            max(0, screenWidthPx - width - (screenWidthPx * 0.03f).roundToInt())
                        }
                        try {
                            wm.updateViewLayout(this, pParams)
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                )
            }
        }

        wm.addView(boardView, boardParams)
        wm.addView(pillView, pParams)
        boardOverlayView = boardView
        pillOverlayView = pillView
    }

    private fun detachOverlays() {
        val wm = windowManager
        boardOverlayView?.let {
            try {
                wm?.removeView(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        pillOverlayView?.let {
            try {
                wm?.removeView(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        boardOverlayView = null
        pillOverlayView = null
        overlayLifecycleOwner?.onDestroy()
        overlayLifecycleOwner = null
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
        detachOverlays()
        releaseCaptureResources()
        OverlayStateStore.setStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        detachOverlays()
        releaseCaptureResources()
        OverlayStateStore.setStopped()
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
