package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.engine.AdaptiveRoiConfig
import com.example.engine.SolverMode
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
        val SOLVER_MODE = stringPreferencesKey("solver_mode")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
    }

    val configFlow: Flow<AdaptiveRoiConfig> = context.dataStore.data.map { prefs ->
        val defaults = AdaptiveRoiConfig()
        val modeName = prefs[Keys.SOLVER_MODE]
        val mode = SolverMode.entries.firstOrNull { it.name == modeName } ?: defaults.solverMode
        AdaptiveRoiConfig(
            gridLeft = prefs[Keys.GRID_LEFT] ?: defaults.gridLeft,
            gridTop = prefs[Keys.GRID_TOP] ?: defaults.gridTop,
            gridRight = prefs[Keys.GRID_RIGHT] ?: defaults.gridRight,
            gridBottom = prefs[Keys.GRID_BOTTOM] ?: defaults.gridBottom,
            trayTop = prefs[Keys.TRAY_TOP] ?: defaults.trayTop,
            trayBottom = prefs[Keys.TRAY_BOTTOM] ?: defaults.trayBottom,
            solverMode = mode,
            overlayOpacity = prefs[Keys.OVERLAY_OPACITY] ?: defaults.overlayOpacity
        )
    }

    suspend fun updateConfig(transform: (AdaptiveRoiConfig) -> AdaptiveRoiConfig) {
        val defaults = AdaptiveRoiConfig()
        context.dataStore.edit { prefs ->
            val currentMode = SolverMode.entries.firstOrNull { it.name == prefs[Keys.SOLVER_MODE] }
                ?: defaults.solverMode
            val current = AdaptiveRoiConfig(
                gridLeft = prefs[Keys.GRID_LEFT] ?: defaults.gridLeft,
                gridTop = prefs[Keys.GRID_TOP] ?: defaults.gridTop,
                gridRight = prefs[Keys.GRID_RIGHT] ?: defaults.gridRight,
                gridBottom = prefs[Keys.GRID_BOTTOM] ?: defaults.gridBottom,
                trayTop = prefs[Keys.TRAY_TOP] ?: defaults.trayTop,
                trayBottom = prefs[Keys.TRAY_BOTTOM] ?: defaults.trayBottom,
                solverMode = currentMode,
                overlayOpacity = prefs[Keys.OVERLAY_OPACITY] ?: defaults.overlayOpacity
            )
            val updated = transform(current)
            prefs[Keys.GRID_LEFT] = updated.gridLeft
            prefs[Keys.GRID_TOP] = updated.gridTop
            prefs[Keys.GRID_RIGHT] = updated.gridRight
            prefs[Keys.GRID_BOTTOM] = updated.gridBottom
            prefs[Keys.TRAY_TOP] = updated.trayTop
            prefs[Keys.TRAY_BOTTOM] = updated.trayBottom
            prefs[Keys.SOLVER_MODE] = updated.solverMode.name
            prefs[Keys.OVERLAY_OPACITY] = updated.overlayOpacity
        }
    }

    suspend fun resetDefaults() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
