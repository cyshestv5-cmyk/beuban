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
    THINKING,
    PAUSED,
    GUIDING,
    DRAGGING,
    VERIFYING
}

enum class CaptureSource(val label: String) {
    NONE("Offline"),
    ACCESSIBILITY("Android Accessibility"),
    MEDIA_PROJECTION("MediaProjection")
}

data class OverlayRuntimeState(
    val status: ServiceStatus = ServiceStatus.STOPPED,
    val captureSource: CaptureSource = CaptureSource.NONE,
    val recommendation: MoveRecommendation? = null,
    val detectedThemeRgb: Int? = null,
    val lastBoardMask: Long = 0L,
    val roiConfig: AdaptiveRoiConfig = AdaptiveRoiConfig(),
    val captureWidthPx: Int = 0,
    val captureHeightPx: Int = 0,
    val autoplayMovesCount: Int = 0,
    val learnedLiftDeltaCells: Float = 0f,
    val noticeMessage: String? = null
)

/**
 * Shared in-process state store synchronized between TacticianAccessibilityService,
 * ScreenCaptureService, the Compose WindowManager overlays, and MainViewModel.
 */
object OverlayStateStore {
    private val _state = MutableStateFlow(OverlayRuntimeState())
    val state: StateFlow<OverlayRuntimeState> = _state.asStateFlow()

    fun updateRoiConfig(config: AdaptiveRoiConfig) {
        _state.update { it.copy(roiConfig = config) }
    }

    fun setServiceStarted(
        widthPx: Int,
        heightPx: Int,
        source: CaptureSource = CaptureSource.MEDIA_PROJECTION
    ) {
        _state.update {
            it.copy(
                status = ServiceStatus.SEARCHING,
                captureSource = source,
                recommendation = null,
                captureWidthPx = widthPx,
                captureHeightPx = heightPx,
                autoplayMovesCount = 0,
                learnedLiftDeltaCells = 0f,
                noticeMessage = null
            )
        }
    }

    fun setPaused(paused: Boolean, notice: String? = null) {
        _state.update { current ->
            if (current.status == ServiceStatus.STOPPED) return@update current
            current.copy(
                status = if (paused) ServiceStatus.PAUSED else ServiceStatus.SEARCHING,
                recommendation = if (paused) null else current.recommendation,
                noticeMessage = notice
            )
        }
    }

    fun setThinking(boardMask: Long, themeRgb: Int) {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            current.copy(
                status = ServiceStatus.THINKING,
                lastBoardMask = boardMask,
                detectedThemeRgb = themeRgb,
                noticeMessage = null
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
                lastBoardMask = boardMask,
                noticeMessage = null
            )
        }
    }

    fun setDragging(recommendation: MoveRecommendation) {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            current.copy(
                status = ServiceStatus.DRAGGING,
                recommendation = recommendation,
                noticeMessage = null
            )
        }
    }

    fun setVerifying() {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            current.copy(status = ServiceStatus.VERIFYING)
        }
    }

    fun recordVerifiedMove(learnedDeltaAdjustment: Float? = null) {
        _state.update { current ->
            val updatedDelta = if (learnedDeltaAdjustment != null) {
                (current.learnedLiftDeltaCells + learnedDeltaAdjustment).coerceIn(-1.5f, 1.5f)
            } else {
                current.learnedLiftDeltaCells
            }
            current.copy(
                autoplayMovesCount = current.autoplayMovesCount + 1,
                learnedLiftDeltaCells = updatedDelta
            )
        }
    }

    fun setCaptureSource(source: CaptureSource) {
        _state.update { it.copy(captureSource = source) }
    }

    fun setNotice(notice: String?) {
        _state.update { it.copy(noticeMessage = notice) }
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
                captureSource = CaptureSource.NONE,
                recommendation = null,
                detectedThemeRgb = null,
                learnedLiftDeltaCells = 0f,
                noticeMessage = null
            )
        }
    }
}
