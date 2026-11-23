package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.engine.AdaptiveRoiConfig
import com.example.engine.GlobalProfile
import com.example.engine.OperatingMode
import com.example.engine.SolverMode
import com.example.engine.ThinkingDepth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tactician_prefs")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val GRID_LEFT = floatPreferencesKey("grid_left")
        val GRID_TOP = floatPreferencesKey("grid_top")
        val GRID_RIGHT = floatPreferencesKey("grid_right")
        val GRID_BOTTOM = floatPreferencesKey("grid_bottom")
        val TRAY_TOP = floatPreferencesKey("tray_top")
        val TRAY_BOTTOM = floatPreferencesKey("tray_bottom")
        val TRAY_LEFT_MARGIN = floatPreferencesKey("tray_left_margin")
        val TRAY_RIGHT_MARGIN = floatPreferencesKey("tray_right_margin")
        val MINI_TILE_FRACTION = floatPreferencesKey("mini_tile_width_fraction")
        val OPERATING_MODE = stringPreferencesKey("operating_mode")
        val GLOBAL_PROFILE = stringPreferencesKey("global_profile")
        val SOLVER_MODE = stringPreferencesKey("solver_mode")
        val THINKING_DEPTH = stringPreferencesKey("thinking_depth")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val DRAG_LIFT_OFFSET = floatPreferencesKey("drag_lift_offset_cells")
        val DRAG_VERTICAL_GAIN = floatPreferencesKey("drag_vertical_gain")
        val DRAG_HORIZONTAL_OFFSET = floatPreferencesKey("drag_horizontal_offset_cells")
        val DRAG_HORIZONTAL_GAIN = floatPreferencesKey("drag_horizontal_gain")
        val CALIBRATED_WIDTH_PX = intPreferencesKey("calibrated_width_px")
        val CALIBRATED_HEIGHT_PX = intPreferencesKey("calibrated_height_px")
        val DRAG_CALIBRATION_SAMPLES = intPreferencesKey("drag_calibration_samples")
        val IS_PREVIEW_LOCKED = booleanPreferencesKey("is_preview_locked")
    }

    val configFlow: Flow<AdaptiveRoiConfig> = context.dataStore.data.map { prefs ->
        fromPrefs(prefs)
    }

    suspend fun updateConfig(transform: (AdaptiveRoiConfig) -> AdaptiveRoiConfig) {
        context.dataStore.edit { prefs ->
            val updated = transform(fromPrefs(prefs))
            prefs[Keys.GRID_LEFT] = updated.gridLeft
            prefs[Keys.GRID_TOP] = updated.gridTop
            prefs[Keys.GRID_RIGHT] = updated.gridRight
            prefs[Keys.GRID_BOTTOM] = updated.gridBottom
            prefs[Keys.TRAY_TOP] = updated.trayTop
            prefs[Keys.TRAY_BOTTOM] = updated.trayBottom
            prefs[Keys.TRAY_LEFT_MARGIN] = updated.trayLeftMargin
            prefs[Keys.TRAY_RIGHT_MARGIN] = updated.trayRightMargin
            prefs[Keys.MINI_TILE_FRACTION] = updated.expectedMiniTileWidthFraction
            prefs[Keys.OPERATING_MODE] = updated.operatingMode.name
            prefs[Keys.GLOBAL_PROFILE] = updated.globalProfile.name
            prefs[Keys.SOLVER_MODE] = updated.solverMode.name
            prefs[Keys.THINKING_DEPTH] = updated.thinkingDepth.name
            prefs[Keys.OVERLAY_OPACITY] = updated.overlayOpacity
            prefs[Keys.DRAG_LIFT_OFFSET] = updated.dragLiftOffsetCells
            prefs[Keys.DRAG_VERTICAL_GAIN] = updated.dragVerticalGain
            prefs[Keys.DRAG_HORIZONTAL_OFFSET] = updated.dragHorizontalOffsetCells
            prefs[Keys.DRAG_HORIZONTAL_GAIN] = updated.dragHorizontalGain
            prefs[Keys.CALIBRATED_WIDTH_PX] = updated.calibratedScreenWidthPx
            prefs[Keys.CALIBRATED_HEIGHT_PX] = updated.calibratedScreenHeightPx
            prefs[Keys.DRAG_CALIBRATION_SAMPLES] = updated.dragCalibrationSamples
            prefs[Keys.IS_PREVIEW_LOCKED] = updated.isPreviewLocked
        }
    }

    suspend fun resetDefaults() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun fromPrefs(prefs: Preferences): AdaptiveRoiConfig {
        val defaults = AdaptiveRoiConfig()
        val opMode = OperatingMode.entries.firstOrNull { it.name == prefs[Keys.OPERATING_MODE] }
            ?: defaults.operatingMode
        val profile = GlobalProfile.entries.firstOrNull { it.name == prefs[Keys.GLOBAL_PROFILE] }
            ?: defaults.globalProfile
        val mode = SolverMode.entries.firstOrNull { it.name == prefs[Keys.SOLVER_MODE] }
            ?: profile.solverMode
        val depth = ThinkingDepth.entries.firstOrNull { it.name == prefs[Keys.THINKING_DEPTH] }
            ?: defaults.thinkingDepth
        return AdaptiveRoiConfig(
            gridLeft = prefs[Keys.GRID_LEFT] ?: defaults.gridLeft,
            gridTop = prefs[Keys.GRID_TOP] ?: defaults.gridTop,
            gridRight = prefs[Keys.GRID_RIGHT] ?: defaults.gridRight,
            gridBottom = prefs[Keys.GRID_BOTTOM] ?: defaults.gridBottom,
            trayTop = prefs[Keys.TRAY_TOP] ?: defaults.trayTop,
            trayBottom = prefs[Keys.TRAY_BOTTOM] ?: defaults.trayBottom,
            trayLeftMargin = prefs[Keys.TRAY_LEFT_MARGIN] ?: defaults.trayLeftMargin,
            trayRightMargin = prefs[Keys.TRAY_RIGHT_MARGIN] ?: defaults.trayRightMargin,
            expectedMiniTileWidthFraction = prefs[Keys.MINI_TILE_FRACTION] ?: defaults.expectedMiniTileWidthFraction,
            operatingMode = opMode,
            globalProfile = profile,
            solverMode = mode,
            thinkingDepth = depth,
            overlayOpacity = prefs[Keys.OVERLAY_OPACITY] ?: defaults.overlayOpacity,
            dragLiftOffsetCells = prefs[Keys.DRAG_LIFT_OFFSET] ?: defaults.dragLiftOffsetCells,
            dragVerticalGain = prefs[Keys.DRAG_VERTICAL_GAIN] ?: defaults.dragVerticalGain,
            dragHorizontalOffsetCells = prefs[Keys.DRAG_HORIZONTAL_OFFSET] ?: defaults.dragHorizontalOffsetCells,
            dragHorizontalGain = prefs[Keys.DRAG_HORIZONTAL_GAIN] ?: defaults.dragHorizontalGain,
            calibratedScreenWidthPx = prefs[Keys.CALIBRATED_WIDTH_PX] ?: defaults.calibratedScreenWidthPx,
            calibratedScreenHeightPx = prefs[Keys.CALIBRATED_HEIGHT_PX] ?: defaults.calibratedScreenHeightPx,
            dragCalibrationSamples = prefs[Keys.DRAG_CALIBRATION_SAMPLES] ?: defaults.dragCalibrationSamples,
            isPreviewLocked = prefs[Keys.IS_PREVIEW_LOCKED] ?: defaults.isPreviewLocked
        )
    }
}
