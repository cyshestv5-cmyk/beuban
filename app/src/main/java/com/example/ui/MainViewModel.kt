package com.example.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OverlayRuntimeState
import com.example.data.OverlayStateStore
import com.example.data.PreferencesRepository
import com.example.data.ServiceStatus
import com.example.engine.AdaptiveRoiConfig
import com.example.engine.BitboardSolver
import com.example.engine.MoveRecommendation
import com.example.engine.PieceCatalog
import com.example.engine.PieceShape
import com.example.engine.SolverMode
import com.example.engine.TraySlotState
import com.example.service.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PermissionState(
    val hasOverlayPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true
)

data class SandboxUiState(
    val boardMask: Long = 0x00003838000080E0L, // Sample L-shape + 3x2 block
    val selectedSlot: Int = 0,
    val trayPieces: List<PieceShape?> = listOf(
        PieceCatalog.BY_ID["l_3x2_1"],
        PieceCatalog.BY_ID["sq_2x2"],
        PieceCatalog.BY_ID["bar_1x3"]
    ),
    val recommendation: MoveRecommendation? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesRepository(application.applicationContext)

    val roiConfig: StateFlow<AdaptiveRoiConfig> = prefs.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AdaptiveRoiConfig())

    val overlayRuntime: StateFlow<OverlayRuntimeState> = OverlayStateStore.state

    private val _permissions = MutableStateFlow(PermissionState())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _sandbox = MutableStateFlow(SandboxUiState())
    val sandbox: StateFlow<SandboxUiState> = _sandbox.asStateFlow()

    init {
        refreshPermissions()
        solveSandbox()
    }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        val canOverlay = Settings.canDrawOverlays(ctx)
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        _permissions.value = PermissionState(
            hasOverlayPermission = canOverlay,
            hasNotificationPermission = hasNotif
        )
    }

    fun setSolverMode(mode: SolverMode) {
        viewModelScope.launch {
            prefs.updateConfig { it.copy(solverMode = mode) }
        }
    }

    fun updateGridBounds(leftMargin: Float, top: Float, bottom: Float) {
        val clampedMargin = leftMargin.coerceIn(0.01f, 0.16f)
        val clampedTop = top.coerceIn(0.14f, 0.34f)
        val clampedBottom = bottom.coerceIn(clampedTop + 0.25f, 0.72f)
        viewModelScope.launch {
            prefs.updateConfig {
                it.copy(
                    gridLeft = clampedMargin,
                    gridRight = 1f - clampedMargin,
                    gridTop = clampedTop,
                    gridBottom = clampedBottom
                )
            }
        }
    }

    fun updateTrayBounds(trayTop: Float, trayHeight: Float) {
        val clampedTop = trayTop.coerceIn(0.62f, 0.80f)
        val clampedBottom = (clampedTop + trayHeight.coerceIn(0.08f, 0.20f)).coerceAtMost(0.95f)
        viewModelScope.launch {
            prefs.updateConfig {
                it.copy(trayTop = clampedTop, trayBottom = clampedBottom)
            }
        }
    }

    fun updateOverlayOpacity(opacity: Float) {
        viewModelScope.launch {
            prefs.updateConfig { it.copy(overlayOpacity = opacity.coerceIn(0.35f, 1.0f)) }
        }
    }

    fun resetRoiDefaults() {
        viewModelScope.launch {
            prefs.resetDefaults()
        }
    }

    fun startCaptureService(resultCode: Int, data: Intent) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun stopCaptureService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        ctx.startService(intent)
    }

    fun togglePauseCaptureService() {
        if (overlayRuntime.value.status == ServiceStatus.STOPPED) return
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_TOGGLE_PAUSE
        }
        ctx.startService(intent)
    }

    // --- Sandbox Interactions ---

    fun toggleSandboxCell(row: Int, col: Int) {
        val bit = 1L shl (row * 8 + col)
        _sandbox.update { state ->
            state.copy(
                boardMask = state.boardMask xor bit,
                recommendation = null
            )
        }
    }

    fun selectSandboxSlot(slotIndex: Int) {
        _sandbox.update { it.copy(selectedSlot = slotIndex.coerceIn(0, 2)) }
    }

    fun setSandboxSlotPiece(piece: PieceShape?) {
        _sandbox.update { state ->
            val updated = state.trayPieces.toMutableList()
            updated[state.selectedSlot] = piece
            state.copy(trayPieces = updated, recommendation = null)
        }
    }

    fun clearSandboxBoard() {
        _sandbox.update { it.copy(boardMask = 0L, recommendation = null) }
    }

    fun applySandboxPreset(boardMask: Long) {
        _sandbox.update { it.copy(boardMask = boardMask, recommendation = null) }
        solveSandbox()
    }

    fun solveSandbox() {
        val current = _sandbox.value
        val mode = roiConfig.value.solverMode
        val tray = current.trayPieces.mapIndexed { idx, piece ->
            if (piece == null) TraySlotState.Empty else TraySlotState.Recognized(idx, piece)
        }
        viewModelScope.launch(Dispatchers.Default) {
            val rec = BitboardSolver.solve(current.boardMask, tray, mode)
            _sandbox.update { it.copy(recommendation = rec) }
        }
    }
}
