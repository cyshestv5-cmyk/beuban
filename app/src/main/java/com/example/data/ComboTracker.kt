package com.example.data

data class ComboState(
    val comboStreak: Int = 0,
    val placementsSinceClear: Int = 0,
    val totalLinesCleared: Int = 0
)

/**
 * Pure Kotlin tracker implementing Block Blast combo-streak continuity rules:
 * clearing 1+ lines increments [comboStreak] and resets [placementsSinceClear];
 * failing to clear within 3 consecutive piece placements resets the streak to 0.
 */
object ComboTracker {

    fun onPlacementCleared(current: ComboState, linesCleared: Int): ComboState {
        return if (linesCleared > 0) {
            current.copy(
                comboStreak = current.comboStreak + 1,
                placementsSinceClear = 0,
                totalLinesCleared = current.totalLinesCleared + linesCleared
            )
        } else {
            val nextGap = current.placementsSinceClear + 1
            if (nextGap >= 3) {
                current.copy(
                    comboStreak = 0,
                    placementsSinceClear = 0
                )
            } else {
                current.copy(placementsSinceClear = nextGap)
            }
        }
    }

    fun reset(): ComboState = ComboState()
}
