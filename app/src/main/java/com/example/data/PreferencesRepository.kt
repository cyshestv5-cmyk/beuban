package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.engine.AdaptiveRoiConfig
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
        val OPERATING_MODE = stringPreferencesKey("operating_mode")
        val SOLVER_MODE = stringPreferencesKey("solver_mode")
        val THINKING_DEPTH = stringPreferencesKey("thinking_depth")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val DRAG_LIFT_OFFSET = floatPreferencesKey("drag_lift_offset_cells")
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
            prefs[Keys.OPERATING_MODE] = updated.operatingMode.name
            prefs[Keys.SOLVER_MODE] = updated.solverMode.name
            prefs[Keys.THINKING_DEPTH] = updated.thinkingDepth.name
            prefs[Keys.OVERLAY_OPACITY] = updated.overlayOpacity
            prefs[Keys.DRAG_LIFT_OFFSET] = updated.dragLiftOffsetCells
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
        val mode = SolverMode.entries.firstOrNull { it.name == prefs[Keys.SOLVER_MODE] }
            ?: defaults.solverMode
        val depth = ThinkingDepth.entries.firstOrNull { it.name == prefs[Keys.THINKING_DEPTH] }
            ?: defaults.thinkingDepth
        return AdaptiveRoiConfig(
            gridLeft = prefs[Keys.GRID_LEFT] ?: defaults.gridLeft,
            gridTop = prefs[Keys.GRID_TOP] ?: defaults.gridTop,
            gridRight = prefs[Keys.GRID_RIGHT] ?: defaults.gridRight,
            gridBottom = prefs[Keys.GRID_BOTTOM] ?: defaults.gridBottom,
            trayTop = prefs[Keys.TRAY_TOP] ?: defaults.trayTop,
            trayBottom = prefs[Keys.TRAY_BOTTOM] ?: defaults.trayBottom,
            operatingMode = opMode,
            solverMode = mode,
            thinkingDepth = depth,
            overlayOpacity = prefs[Keys.OVERLAY_OPACITY] ?: defaults.overlayOpacity,
            dragLiftOffsetCells = prefs[Keys.DRAG_LIFT_OFFSET] ?: defaults.dragLiftOffsetCells
        )
    }
}
