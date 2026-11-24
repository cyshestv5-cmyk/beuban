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
    VERIFYING,
    CALIBRATING
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
    val comboState: ComboState = ComboState(),
    val lastCalibrationSummary: String? = null,
    val noticeMessage: String? = null,
    val pendingGuidedCalibration: Boolean = false,
    val guidedCalibrationStep: Int = 0,
    val thoughtStatus: String = "Engine idle — ready",
    val thoughtDetail: String? = null
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

    fun requestGuidedCalibration(requested: Boolean) {
        _state.update {
            it.copy(
                pendingGuidedCalibration = requested,
                guidedCalibrationStep = if (!requested) 0 else it.guidedCalibrationStep,
                thoughtStatus = if (requested) "Slow 3-point probe armed" else it.thoughtStatus,
                thoughtDetail = if (requested) "Will probe next settled board gently" else it.thoughtDetail
            )
        }
    }

    fun setThought(status: String, detail: String? = null) {
        _state.update { current ->
            current.copy(
                thoughtStatus = status,
                thoughtDetail = detail
            )
        }
    }

    fun setCalibratingStep(step: Int, notice: String? = null, detail: String? = null) {
        _state.update { current ->
            if (current.status == ServiceStatus.STOPPED) return@update current
            current.copy(
                status = ServiceStatus.CALIBRATING,
                guidedCalibrationStep = step,
                noticeMessage = notice ?: current.noticeMessage,
                thoughtStatus = notice ?: "Calibrating Point $step…",
                thoughtDetail = detail ?: "Slow gentle preview lock"
            )
        }
    }

    fun finishGuidedCalibration(summary: String, detail: String? = null) {
        _state.update { current ->
            current.copy(
                pendingGuidedCalibration = false,
                guidedCalibrationStep = 0,
                status = if (current.status == ServiceStatus.CALIBRATING) ServiceStatus.SEARCHING else current.status,
                lastCalibrationSummary = summary,
                noticeMessage = summary,
                thoughtStatus = summary,
                thoughtDetail = detail
            )
        }
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
                comboState = ComboTracker.reset(),
                guidedCalibrationStep = 0,
                noticeMessage = null,
                thoughtStatus = "Watching screen for Block Blast board…",
                thoughtDetail = "Waiting for calm 2-frame settle"
            )
        }
    }

    fun setPaused(
        paused: Boolean,
        notice: String? = null,
        thought: String? = null,
        detail: String? = null
    ) {
        _state.update { current ->
            if (current.status == ServiceStatus.STOPPED) return@update current
            current.copy(
                status = if (paused) ServiceStatus.PAUSED else ServiceStatus.SEARCHING,
                recommendation = if (paused) null else current.recommendation,
                guidedCalibrationStep = 0,
                noticeMessage = notice,
                thoughtStatus = thought ?: if (paused) {
                    notice ?: "Paused — tap Start to resume"
                } else {
                    "Resumed — waiting for settled board…"
                },
                thoughtDetail = detail
            )
        }
    }

    fun setGameOverPaused(message: String, detail: String? = "No legal placements remain") {
        _state.update { current ->
            if (current.status == ServiceStatus.STOPPED) return@update current
            current.copy(
                status = ServiceStatus.PAUSED,
                recommendation = null,
                guidedCalibrationStep = 0,
                noticeMessage = message,
                thoughtStatus = message,
                thoughtDetail = detail
            )
        }
    }

    fun setThinking(boardMask: Long, themeRgb: Int, detail: String? = null) {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            val filled = java.lang.Long.bitCount(boardMask)
            current.copy(
                status = ServiceStatus.THINKING,
                lastBoardMask = boardMask,
                detectedThemeRgb = themeRgb,
                noticeMessage = null,
                thoughtStatus = "Calculating all 6 piece orders…",
                thoughtDetail = detail ?: "$filled/64 filled • Streak ${current.comboState.comboStreak}"
            )
        }
    }

    fun updateGuidance(
        boardMask: Long,
        themeRgb: Int,
        recommendation: MoveRecommendation?,
        detail: String? = null
    ) {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            val statusMsg: String
            val detailMsg: String?
            if (recommendation != null) {
                val safePct = (recommendation.monteCarloSurvivalRate * 100).toInt()
                val clearTxt = if (recommendation.linesCleared > 0) {
                    "Clears ${recommendation.linesCleared}L • "
                } else {
                    ""
                }
                statusMsg = "Plan: Slot ${recommendation.slotIndex + 1} (${recommendation.piece.label}) → R${recommendation.targetRow + 1},C${recommendation.targetCol + 1}"
                detailMsg = detail ?: "$clearTxt$safePct% safe • ${recommendation.solveTimeMs} ms"
            } else {
                statusMsg = "Checking legal moves on board…"
                detailMsg = detail
            }
            current.copy(
                status = if (recommendation != null) ServiceStatus.GUIDING else ServiceStatus.SEARCHING,
                recommendation = recommendation,
                detectedThemeRgb = themeRgb,
                lastBoardMask = boardMask,
                noticeMessage = null,
                thoughtStatus = statusMsg,
                thoughtDetail = detailMsg
            )
        }
    }

    fun setDragging(recommendation: MoveRecommendation, detail: String? = null) {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            current.copy(
                status = ServiceStatus.DRAGGING,
                recommendation = recommendation,
                noticeMessage = null,
                thoughtStatus = "Moving Slot ${recommendation.slotIndex + 1} → R${recommendation.targetRow + 1},C${recommendation.targetCol + 1}",
                thoughtDetail = detail ?: "${recommendation.piece.label} • smooth glide"
            )
        }
    }

    fun setVerifying(detail: String? = null) {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            current.copy(
                status = ServiceStatus.VERIFYING,
                thoughtStatus = "Verifying landing & waiting to settle…",
                thoughtDetail = detail ?: "Reading post-drop board"
            )
        }
    }

    fun recordVerifiedMove(
        calibrationSummary: String? = null,
        linesCleared: Int = 0,
        thoughtStatus: String? = null,
        thoughtDetail: String? = null
    ) {
        _state.update { current ->
            val nextCombo = ComboTracker.onPlacementCleared(current.comboState, linesCleared)
            current.copy(
                autoplayMovesCount = current.autoplayMovesCount + 1,
                comboState = nextCombo,
                lastCalibrationSummary = calibrationSummary ?: current.lastCalibrationSummary,
                thoughtStatus = thoughtStatus ?: (calibrationSummary ?: "Landed cleanly • waiting next settle"),
                thoughtDetail = thoughtDetail ?: if (linesCleared > 0) {
                    "Cleared $linesCleared line(s) • Streak ${nextCombo.comboStreak}"
                } else {
                    "Move #${current.autoplayMovesCount + 1}"
                }
            )
        }
    }

    fun resetComboOnNewGame() {
        _state.update { current ->
            if (current.comboState.comboStreak == 0 && current.comboState.placementsSinceClear == 0) {
                current
            } else {
                current.copy(comboState = ComboTracker.reset())
            }
        }
    }

    fun setCaptureSource(source: CaptureSource) {
        _state.update { it.copy(captureSource = source) }
    }

    fun setNotice(notice: String?) {
        _state.update { it.copy(noticeMessage = notice) }
    }

    fun setSearching(
        thought: String = "Watching screen for settled board…",
        detail: String? = null
    ) {
        _state.update { current ->
            if (current.status == ServiceStatus.PAUSED || current.status == ServiceStatus.STOPPED) {
                return@update current
            }
            current.copy(
                status = ServiceStatus.SEARCHING,
                recommendation = null,
                thoughtStatus = thought,
                thoughtDetail = detail
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
                guidedCalibrationStep = 0,
                pendingGuidedCalibration = false,
                noticeMessage = null,
                thoughtStatus = "Engine idle — ready",
                thoughtDetail = null
            )
        }
    }
}
