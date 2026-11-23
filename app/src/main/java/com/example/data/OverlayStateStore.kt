package com.example.data

import com.example.engine.AdaptiveRoiConfig
import com.example.engine.MoveRecommendation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServiceStatus {
    STOPPED,
    SEARCHING,
    PAUSED,
    GUIDING
}

data class OverlayRuntimeState(
    val status: ServiceStatus = ServiceStatus.STOPPED,
    val recommendation: MoveRecommendation? = null,
    val detectedThemeRgb: Int? = null,
    val lastBoardMask: Long = 0L,
    val roiConfig: AdaptiveRoiConfig = AdaptiveRoiConfig(),
    val captureWidthPx: Int = 0,
    val captureHeightPx: Int = 0
)

/**
 * Shared in-process state store synchronized between ScreenCaptureService,
 * the Compose WindowManager overlays, and MainViewModel.
 */
object OverlayStateStore {
    private val _state = MutableStateFlow(OverlayRuntimeState())
    val state: StateFlow<OverlayRuntimeState> = _state.asStateFlow()

    fun updateRoiConfig(config: AdaptiveRoiConfig) {
        _state.update { it.copy(roiConfig = config) }
    }

    fun setServiceStarted(widthPx: Int, heightPx: Int) {
        _state.update {
            it.copy(
                status = ServiceStatus.SEARCHING,
                recommendation = null,
                captureWidthPx = widthPx,
                captureHeightPx = heightPx
            )
        }
    }

    fun setPaused(paused: Boolean) {
        _state.update { current ->
            if (current.status == ServiceStatus.STOPPED) return@update current
            current.copy(
                status = if (paused) ServiceStatus.PAUSED else ServiceStatus.SEARCHING,
                recommendation = if (paused) null else current.recommendation
            )
        }
    }

    fun updateGuidance(
        boardMask: Long,
        themeRgb: Int,
        recommendation: MoveRecommendation?
    ) {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            current.copy(
                status = if (recommendation != null) ServiceStatus.GUIDING else ServiceStatus.SEARCHING,
                recommendation = recommendation,
                detectedThemeRgb = themeRgb,
                lastBoardMask = boardMask
            )
        }
    }

    fun setSearching() {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            current.copy(
                status = ServiceStatus.SEARCHING,
                recommendation = null
            )
        }
    }

    fun setStopped() {
        _state.update {
            it.copy(
                status = ServiceStatus.STOPPED,
                recommendation = null,
                detectedThemeRgb = null
            )
        }
    }
}
