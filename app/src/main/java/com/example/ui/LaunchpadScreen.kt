package com.example.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.CaptureSource
import com.example.data.OverlayRuntimeState
import com.example.data.ServiceStatus
import com.example.engine.AdaptiveRoiConfig
import com.example.engine.GlobalProfile
import com.example.engine.OperatingMode
import com.example.ui.theme.AmberPop
import com.example.ui.theme.AmberPopContainer
import com.example.ui.theme.CoralPulse
import com.example.ui.theme.ElectricCobalt
import com.example.ui.theme.MintSurge
import com.example.ui.theme.MintSurgeContainer
import com.example.ui.theme.OnMintSurge

@Composable
fun LaunchpadScreen(
    runtime: OverlayRuntimeState,
    permissions: PermissionState,
    roiConfig: AdaptiveRoiConfig,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSelectOperatingMode: (OperatingMode) -> Unit,
    onSelectGlobalProfile: (GlobalProfile) -> Unit,
    onLaunchOverlay: () -> Unit,
    onLaunchProjectionFallback: () -> Unit,
    onTogglePause: () -> Unit,
    onStopOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = runtime.status != ServiceStatus.STOPPED
    val isAutoplay = roiConfig.operatingMode == OperatingMode.AUTOPLAY
    val scrollState = rememberScrollState()

    val statusColor by animateColorAsState(
        targetValue = when (runtime.status) {
            ServiceStatus.GUIDING, ServiceStatus.DRAGGING, ServiceStatus.VERIFYING -> MintSurge
            ServiceStatus.CALIBRATING, ServiceStatus.PAUSED -> AmberPop
            ServiceStatus.THINKING -> Color(0xFF7B9DFF)
            ServiceStatus.SEARCHING -> ElectricCobalt
            ServiceStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "status_color"
    )

    val statusText = when (runtime.status) {
        ServiceStatus.STOPPED -> stringResource(R.string.status_stopped)
        ServiceStatus.SEARCHING -> stringResource(R.string.status_searching)
        ServiceStatus.THINKING -> stringResource(R.string.status_thinking)
        ServiceStatus.PAUSED -> stringResource(R.string.status_paused)
        ServiceStatus.GUIDING -> stringResource(R.string.status_guiding)
        ServiceStatus.DRAGGING -> stringResource(R.string.status_dragging)
        ServiceStatus.VERIFYING -> stringResource(R.string.status_verifying)
        ServiceStatus.CALIBRATING -> stringResource(R.string.status_calibrating)
    }

    val pulseTransition = rememberInfiniteTransition(label = "hero_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hero_pulse_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Expressive Hero Surface with live engine telemetry, combo streak & preview lock status
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("hero_status_card"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, statusColor.copy(alpha = if (isRunning) 0.55f else 0.18f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                statusColor.copy(alpha = if (isRunning) 0.18f else 0.08f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = statusColor.copy(alpha = 0.16f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(
                                            statusColor.copy(
                                                alpha = if (isRunning) pulseAlpha else 0.7f
                                            )
                                        )
                                )
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = statusColor
                                )
                            }
                        }

                        if (isRunning && runtime.captureSource != CaptureSource.NONE) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = runtime.captureSource.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        } else if (runtime.detectedThemeRgb != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(runtime.detectedThemeRgb))
                                )
                                Text(
                                    text = "Theme Locked",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.hero_title),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.hero_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Telemetry pills: Preview Lock + Calibration + Live Combo Streak
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (roiConfig.isPreviewLocked) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MintSurgeContainer,
                                modifier = Modifier.testTag("preview_locked_pill")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Verified,
                                        contentDescription = null,
                                        tint = MintSurge,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Preview-Locked",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MintSurge
                                    )
                                }
                            }
                        }

                        if (isRunning || runtime.comboState.comboStreak > 0) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (runtime.comboState.comboStreak > 0) {
                                    AmberPopContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                modifier = Modifier.testTag("combo_streak_pill")
                            ) {
                                val streak = runtime.comboState.comboStreak
                                val rem = (3 - runtime.comboState.placementsSinceClear).coerceAtLeast(1)
                                val comboLabel = if (streak > 0) {
                                    "Combo ×$streak ($rem moves left)"
                                } else {
                                    "${runtime.comboState.totalLinesCleared} lines cleared"
                                }
                                Text(
                                    text = comboLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (streak > 0) AmberPop else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    val calBadgeText = when {
                        !runtime.lastCalibrationSummary.isNullOrBlank() -> runtime.lastCalibrationSummary
                        roiConfig.calibratedScreenWidthPx > 0 && roiConfig.dragCalibrationSamples > 0 -> {
                            val g = ((roiConfig.dragVerticalGain * 100).toInt() / 100f)
                            val l = ((roiConfig.dragLiftOffsetCells * 100).toInt() / 100f)
                            "Saved ${roiConfig.calibratedScreenWidthPx}×${roiConfig.calibratedScreenHeightPx} • ${g}x, +${l}c (${roiConfig.dragCalibrationSamples} drops)"
                        }
                        roiConfig.calibratedScreenWidthPx > 0 ->
                            "Screen adapted: ${roiConfig.calibratedScreenWidthPx}×${roiConfig.calibratedScreenHeightPx}"
                        else -> null
                    }
                    if (calBadgeText != null) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.testTag("calibration_summary_pill")
                        ) {
                            Text(
                                text = calBadgeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MintSurge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    if (!runtime.noticeMessage.isNullOrBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = AmberPopContainer.copy(alpha = 0.55f)
                        ) {
                            Text(
                                text = runtime.noticeMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AmberPop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    } else if (isRunning) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = runtime.thoughtStatus,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!runtime.thoughtDetail.isNullOrBlank()) {
                                    Text(
                                        text = runtime.thoughtDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = statusColor
                                    )
                                }
                            }
                        }
                    }

                    // Active Move Telemetry Strip
                    val rec = runtime.recommendation
                    if (isRunning && rec != null) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Slot ${rec.slotIndex + 1} • ${rec.piece.label}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MintSurge
                                    )
                                    val movesNote = if (runtime.autoplayMovesCount > 0) {
                                        " • ${runtime.autoplayMovesCount} auto-placed"
                                    } else {
                                        ""
                                    }
                                    Text(
                                        text = "Row ${rec.targetRow + 1}, Col ${rec.targetCol + 1} • ${rec.solveTimeMs} ms$movesNote",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MintSurgeContainer
                                ) {
                                    Text(
                                        text = "${(rec.monteCarloSurvivalRate * 100).toInt()}% Safe",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MintSurge,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Primary Expressive Action Pill(s)
        if (!isRunning) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLaunchOverlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                        .testTag("launch_overlay_button"),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MintSurge,
                        contentColor = OnMintSurge
                    )
                ) {
                    Icon(
                        imageVector = if (isAutoplay) Icons.Rounded.TouchApp else Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(
                            if (isAutoplay) R.string.btn_launch_autoplay else R.string.btn_launch_overlay
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && permissions.hasAccessibilityPermission) {
                    OutlinedButton(
                        onClick = onLaunchProjectionFallback,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("launch_projection_fallback_button"),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "Or Start via MediaProjection Stream (60 Hz Preview-Snap)",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onTogglePause,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .testTag("home_toggle_pause_button"),
                    shape = RoundedCornerShape(50)
                ) {
                    val paused = runtime.status == ServiceStatus.PAUSED
                    Icon(
                        imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(if (paused) R.string.action_resume else R.string.action_pause),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = onStopOverlay,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .testTag("stop_overlay_button"),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoralPulse,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_stop_overlay),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Operating Mode Switcher (Guide Overlay vs Autoplay)
        Text(
            text = stringResource(R.string.mode_section_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OptionCard(
                title = stringResource(R.string.mode_guide),
                subtitle = stringResource(R.string.mode_guide_desc),
                icon = Icons.Rounded.Visibility,
                accentColor = ElectricCobalt,
                selected = roiConfig.operatingMode == OperatingMode.GUIDE_OVERLAY,
                onClick = { onSelectOperatingMode(OperatingMode.GUIDE_OVERLAY) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("mode_guide_card")
            )

            OptionCard(
                title = stringResource(R.string.mode_autoplay),
                subtitle = stringResource(R.string.mode_autoplay_desc),
                icon = Icons.Rounded.TouchApp,
                accentColor = MintSurge,
                selected = roiConfig.operatingMode == OperatingMode.AUTOPLAY,
                onClick = { onSelectOperatingMode(OperatingMode.AUTOPLAY) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("mode_autoplay_card")
            )
        }

        // Built-In Global Profiles (unifying AI search depth, combo strategy & drag cadence)
        Text(
            text = stringResource(R.string.profile_section_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlobalProfileCard(
                profile = GlobalProfile.MAX_COMBOS_SCORE,
                icon = Icons.Rounded.Bolt,
                accentColor = AmberPop,
                selected = roiConfig.globalProfile == GlobalProfile.MAX_COMBOS_SCORE,
                onClick = { onSelectGlobalProfile(GlobalProfile.MAX_COMBOS_SCORE) }
            )
            GlobalProfileCard(
                profile = GlobalProfile.MAX_SURVIVAL,
                icon = Icons.Rounded.Shield,
                accentColor = MintSurge,
                selected = roiConfig.globalProfile == GlobalProfile.MAX_SURVIVAL,
                onClick = { onSelectGlobalProfile(GlobalProfile.MAX_SURVIVAL) }
            )
            GlobalProfileCard(
                profile = GlobalProfile.FASTEST_SPEED,
                icon = Icons.Rounded.Speed,
                accentColor = ElectricCobalt,
                selected = roiConfig.globalProfile == GlobalProfile.FASTEST_SPEED,
                onClick = { onSelectGlobalProfile(GlobalProfile.FASTEST_SPEED) }
            )
        }

        // Permissions & System Readiness Section
        Text(
            text = "System Readiness",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionReadinessCard(
                icon = Icons.Outlined.AccessibilityNew,
                title = stringResource(R.string.perm_accessibility_title),
                description = stringResource(R.string.perm_accessibility_desc),
                isGranted = permissions.hasAccessibilityPermission,
                onGrant = onRequestAccessibilityPermission,
                testTag = "perm_accessibility_card"
            )

            if (!permissions.hasAccessibilityPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = stringResource(R.string.accessibility_sideload_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            PermissionReadinessCard(
                icon = Icons.Outlined.Layers,
                title = stringResource(R.string.perm_overlay_title),
                description = stringResource(R.string.perm_overlay_desc),
                isGranted = permissions.hasOverlayPermission,
                onGrant = onRequestOverlayPermission,
                testTag = "perm_overlay_card"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionReadinessCard(
                    icon = Icons.Outlined.NotificationsActive,
                    title = stringResource(R.string.perm_notif_title),
                    description = stringResource(R.string.perm_notif_desc),
                    isGranted = permissions.hasNotificationPermission,
                    onGrant = onRequestNotificationPermission,
                    testTag = "perm_notif_card"
                )
            }
        }

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.GridView,
                    contentDescription = null,
                    tint = ElectricCobalt
                )
                Text(
                    text = "Adaptive 64-bit bitwise lookahead scales compute dynamically from sub-15 ms on open boards up to deep 2-turn survival rollouts on tight boards.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GlobalProfileCard(
    profile: GlobalProfile,
    icon: ImageVector,
    accentColor: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (selected) {
        when (accentColor) {
            AmberPop -> AmberPopContainer.copy(alpha = 0.55f)
            ElectricCobalt -> ElectricCobalt.copy(alpha = 0.16f)
            else -> MintSurgeContainer.copy(alpha = 0.65f)
        }
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_${profile.name.lowercase()}_card"),
        shape = MaterialTheme.shapes.medium,
        color = bgColor,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = accentColor.copy(alpha = if (selected) 0.24f else 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = profile.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = "${profile.openBudgetMs}–${profile.criticalBudgetMs}ms • ${profile.glideDurationMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionReadinessCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onGrant: () -> Unit,
    testTag: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isGranted) MintSurgeContainer else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isGranted) MintSurge else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isGranted) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MintSurgeContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MintSurge,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.perm_granted),
                            style = MaterialTheme.typography.labelMedium,
                            color = MintSurge
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onGrant,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(stringResource(R.string.perm_grant_cta))
                }
            }
        }
    }
}

@Composable
private fun OptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            when (accentColor) {
                AmberPop -> AmberPopContainer.copy(alpha = 0.55f)
                ElectricCobalt -> ElectricCobalt.copy(alpha = 0.16f)
                else -> MintSurgeContainer.copy(alpha = 0.65f)
            }
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
