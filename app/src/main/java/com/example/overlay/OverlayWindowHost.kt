package com.example.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.OverlayStateStore
import com.example.data.ServiceStatus
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reusable WindowManager Compose host shared between ScreenCaptureService
 * (TYPE_APPLICATION_OVERLAY) and TacticianAccessibilityService (TYPE_ACCESSIBILITY_OVERLAY).
 */
class OverlayWindowHost(
    private val context: Context,
    private val useAccessibilityOverlayType: Boolean
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var boardOverlayView: ComposeView? = null
    private var pillOverlayView: ComposeView? = null
    private var lifecycleOwner: ServiceComposeLifecycleOwner? = null

    val isAttached: Boolean get() = boardOverlayView != null

    fun attach(
        screenWidthPx: Int,
        screenHeightPx: Int,
        onTogglePause: () -> Unit,
        onStop: () -> Unit
    ): Boolean {
        if (isAttached) return true
        val wm = windowManager ?: return false
        if (!useAccessibilityOverlayType && !Settings.canDrawOverlays(context)) {
            return false
        }

        val overlayWindowType = if (useAccessibilityOverlayType) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        val owner = ServiceComposeLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        val boardView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val state by OverlayStateStore.state.collectAsState()
                BoardHighlightOverlay(runtimeState = state)
            }
        }

        val boardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType,
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
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidthPx * 0.04f).roundToInt()
            y = (screenHeightPx * 0.12f).roundToInt()
        }

        val pillView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val state by OverlayStateStore.state.collectAsState()
                FloatingControlPill(
                    status = state.status,
                    onTogglePause = onTogglePause,
                    onStop = onStop,
                    onDragDelta = { dx, dy ->
                        pParams.x = (pParams.x + dx.roundToInt()).coerceIn(0, max(0, screenWidthPx - width))
                        pParams.y = (pParams.y + dy.roundToInt()).coerceIn(64, max(64, screenHeightPx - height))
                        try {
                            wm.updateViewLayout(this, pParams)
                        } catch (_: IllegalArgumentException) {
                        }
                    },
                    onDragEnd = {
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

        return try {
            wm.addView(boardView, boardParams)
            wm.addView(pillView, pParams)
            boardOverlayView = boardView
            pillOverlayView = pillView
            true
        } catch (_: Exception) {
            detach()
            false
        }
    }

    fun detach() {
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
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
    }
}
