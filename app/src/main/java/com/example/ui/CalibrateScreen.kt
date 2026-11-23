package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.AdaptiveRoiConfig
import com.example.ui.theme.AmberPop
import com.example.ui.theme.AmberPopContainer
import com.example.ui.theme.ElectricCobalt
import com.example.ui.theme.MintSurge
import com.example.ui.theme.MintSurgeContainer
import com.example.ui.theme.OnMintSurge
import kotlin.math.roundToInt

private enum class CalibrateThemePreview(val label: String) {
    WOOD("Wood"),
    RED("Red"),
    LIGHT("Light")
}

@Composable
fun CalibrateScreen(
    config: AdaptiveRoiConfig,
    isCalibratingPending: Boolean,
    calibratingStep: Int,
    onArmGuidedCalibration: () -> Unit,
    onUpdateGridBounds: (leftMargin: Float, top: Float, bottom: Float) -> Unit,
    onUpdateTrayBounds: (trayTop: Float, trayHeight: Float) -> Unit,
    onUpdateOpacity: (Float) -> Unit,
    onUpdateDragLift: (Float) -> Unit,
    onUpdateDragVerticalGain: (Float) -> Unit,
    onUpdateDragHorizontalOffset: (Float) -> Unit,
    onUpdateDragHorizontalGain: (Float) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    var previewTheme by remember { mutableStateOf(CalibrateThemePreview.WOOD) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.calibrate_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.calibrate_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Saved Phone Calibration Status Card
        val formattedLiftHeader = ((config.dragLiftOffsetCells * 100f).roundToInt() / 100f)
        val formattedGainYHeader = ((config.dragVerticalGain * 100f).roundToInt() / 100f)
        val formattedOffXHeader = ((config.dragHorizontalOffsetCells * 100f).roundToInt() / 100f)
        val formattedGainXHeader = ((config.dragHorizontalGain * 100f).roundToInt() / 100f)
        val screenBadge = if (config.calibratedScreenWidthPx > 0 && config.calibratedScreenHeightPx > 0) {
            "${config.calibratedScreenWidthPx}×${config.calibratedScreenHeightPx} px"
        } else {
            "Auto-detects on first frame"
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
                1.dp,
                if (config.isPreviewLocked) MintSurge.copy(alpha = 0.65f) else ElectricCobalt.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("saved_calibration_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Saved Phone Calibration",
                        style = MaterialTheme.typography.titleSmall,
                        color = MintSurge
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (config.isPreviewLocked) MintSurgeContainer else MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (config.isPreviewLocked) {
                                Icon(
                                    imageVector = Icons.Rounded.Verified,
                                    contentDescription = null,
                                    tint = MintSurge,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = if (config.isPreviewLocked) "Preview-Locked" else "Self-Learning",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (config.isPreviewLocked) MintSurge else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = "Screen: $screenBadge • ${config.dragCalibrationSamples} calibration samples",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Y: ${formattedGainYHeader}x, +$formattedLiftHeader c  |  X: ${formattedGainXHeader}x, ${formattedOffXHeader}c",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Guided 3-Point Non-Destructive Preview Calibration Card
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (isCalibratingPending || calibratingStep > 0) {
                AmberPopContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            border = BorderStroke(
                1.dp,
                if (isCalibratingPending || calibratingStep > 0) AmberPop else MintSurge.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.guided_cal_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.guided_cal_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onArmGuidedCalibration,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCalibratingPending || calibratingStep > 0) AmberPop else MintSurge,
                        contentColor = OnMintSurge
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("guided_calibrate_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CenterFocusStrong,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    val btnText = when {
                        calibratingStep > 0 -> "Calibrating Point $calibratingStep/3…"
                        isCalibratingPending -> stringResource(R.string.guided_cal_armed)
                        else -> stringResource(R.string.guided_cal_cta)
                    }
                    Text(
                        text = btnText,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // Theme Preview Toggle (Wood vs Red vs Light)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = previewTheme == CalibrateThemePreview.WOOD,
                onClick = { previewTheme = CalibrateThemePreview.WOOD },
                label = { Text("Wood") },
                modifier = Modifier.testTag("preview_wood_chip")
            )
            FilterChip(
                selected = previewTheme == CalibrateThemePreview.RED,
                onClick = { previewTheme = CalibrateThemePreview.RED },
                label = { Text("Red") },
                modifier = Modifier.testTag("preview_red_chip")
            )
            FilterChip(
                selected = previewTheme == CalibrateThemePreview.LIGHT,
                onClick = { previewTheme = CalibrateThemePreview.LIGHT },
                label = { Text("Light") },
                modifier = Modifier.testTag("preview_light_chip")
            )
        }

        // Visual Simulated Phone Screen with Live ROI + Drag Lift Wireframe
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(9f / 20f),
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            shadowElevation = 8.dp
        ) {
            val bgColor = when (previewTheme) {
                CalibrateThemePreview.WOOD -> Color(0xFFC46C47)
                CalibrateThemePreview.RED -> Color(0xFFB9464D)
                CalibrateThemePreview.LIGHT -> Color(0xFFF6F8FC)
            }
            val boardBg = when (previewTheme) {
                CalibrateThemePreview.WOOD -> Color(0xFF4B2314)
                CalibrateThemePreview.RED -> Color(0xFF481515)
                CalibrateThemePreview.LIGHT -> Color(0xFFFFFFFF)
            }
            val lineColor = when (previewTheme) {
                CalibrateThemePreview.WOOD -> Color(0xFF5D2D1B)
                CalibrateThemePreview.RED -> Color(0xFF330C0C)
                CalibrateThemePreview.LIGHT -> Color(0xFFDCE2EC)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    val defLeft = 0.056f * w
                    val defRight = 0.944f * w
                    val defTop = 0.240f * h
                    val defBottom = 0.638f * h
                    drawRoundRect(
                        color = boardBg,
                        topLeft = Offset(defLeft - 6f, defTop - 6f),
                        size = Size((defRight - defLeft) + 12f, (defBottom - defTop) + 12f),
                        cornerRadius = CornerRadius(14f, 14f)
                    )
                    val cellWSim = (defRight - defLeft) / 8f
                    val cellHSim = (defBottom - defTop) / 8f
                    for (i in 1..7) {
                        drawLine(
                            color = lineColor,
                            start = Offset(defLeft + i * cellWSim, defTop),
                            end = Offset(defLeft + i * cellWSim, defBottom),
                            strokeWidth = 1.5f
                        )
                        drawLine(
                            color = lineColor,
                            start = Offset(defLeft, defTop + i * cellHSim),
                            end = Offset(defRight, defTop + i * cellHSim),
                            strokeWidth = 1.5f
                        )
                    }

                    // Active user-calibrated 8x8 grid ROI overlay
                    val gLeft = config.gridLeft * w
                    val gTop = config.gridTop * h
                    val gRight = config.gridRight * w
                    val gBottom = config.gridBottom * h
                    val cw = (gRight - gLeft) / 8f
                    val ch = (gBottom - gTop) / 8f

                    drawRect(
                        color = MintSurge.copy(alpha = 0.9f),
                        topLeft = Offset(gLeft, gTop),
                        size = Size(gRight - gLeft, gBottom - gTop),
                        style = Stroke(width = 4f)
                    )
                    for (r in 0 until 8) {
                        for (c in 0 until 8) {
                            drawCircle(
                                color = MintSurge.copy(alpha = 0.55f),
                                radius = 3.5f,
                                center = Offset(gLeft + (c + 0.5f) * cw, gTop + (r + 0.5f) * ch)
                            )
                        }
                    }

                    // Sample target cell vs Autoplay finger touch point (4-param affine compensation)
                    val previewEndpoints = config.computeDragEndpoints(
                        slotIndex = 0,
                        targetRow = 2,
                        targetCol = 5,
                        pieceWidth = 1,
                        pieceHeight = 1,
                        screenWidthPx = w.roundToInt().coerceAtLeast(100),
                        screenHeightPx = h.roundToInt().coerceAtLeast(200)
                    )
                    val sampleTarget = Offset(
                        previewEndpoints.baseTargetX,
                        previewEndpoints.baseTargetY
                    )
                    val touchDrop = Offset(
                        previewEndpoints.endX.coerceIn(12f, w - 12f),
                        previewEndpoints.endY.coerceIn(gTop * 0.6f, h - 16f)
                    )
                    drawLine(
                        color = AmberPop,
                        start = sampleTarget,
                        end = touchDrop,
                        strokeWidth = 3f
                    )
                    drawCircle(color = MintSurge, radius = 6f, center = sampleTarget)
                    drawCircle(color = AmberPop, radius = 6f, center = touchDrop)

                    // Active user-calibrated 3-slot tray ROI overlay
                    for (slot in 0..2) {
                        val b = config.traySlotBounds(slot)
                        drawRoundRect(
                            color = ElectricCobalt.copy(alpha = 0.2f),
                            topLeft = Offset(b.left * w, b.top * h),
                            size = Size((b.right - b.left) * w, (b.bottom - b.top) * h),
                            cornerRadius = CornerRadius(12f, 12f)
                        )
                        drawRoundRect(
                            color = ElectricCobalt,
                            topLeft = Offset(b.left * w, b.top * h),
                            size = Size((b.right - b.left) * w, (b.bottom - b.top) * h),
                            cornerRadius = CornerRadius(12f, 12f),
                            style = Stroke(width = 3f)
                        )
                    }
                }
            }
        }

        // Tactile Calibration Sliders
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val formattedGain = ((config.dragVerticalGain * 100f).roundToInt() / 100f)
                SliderSetting(
                    label = "Vertical Drag Gain",
                    valueText = "${formattedGain}x",
                    value = config.dragVerticalGain.coerceIn(0.65f, 1.95f),
                    range = 0.65f..1.95f,
                    onValueChange = onUpdateDragVerticalGain
                )
                val formattedLift = ((config.dragLiftOffsetCells * 10f).roundToInt() / 10f)
                SliderSetting(
                    label = "Vertical Lift Offset",
                    valueText = "${if (formattedLift >= 0f) "+" else ""}$formattedLift cells",
                    value = config.dragLiftOffsetCells.coerceIn(-1.0f, 4.8f),
                    range = -1.0f..4.8f,
                    onValueChange = onUpdateDragLift
                )
                val formattedGainX = ((config.dragHorizontalGain * 100f).roundToInt() / 100f)
                SliderSetting(
                    label = "Horizontal Drag Gain",
                    valueText = "${formattedGainX}x",
                    value = config.dragHorizontalGain.coerceIn(0.65f, 1.45f),
                    range = 0.65f..1.45f,
                    onValueChange = onUpdateDragHorizontalGain
                )
                val formattedOffX = ((config.dragHorizontalOffsetCells * 10f).roundToInt() / 10f)
                SliderSetting(
                    label = "Horizontal Offset",
                    valueText = "${if (formattedOffX >= 0f) "+" else ""}$formattedOffX cells",
                    value = config.dragHorizontalOffsetCells.coerceIn(-2.5f, 2.5f),
                    range = -2.5f..2.5f,
                    onValueChange = onUpdateDragHorizontalOffset
                )
                SliderSetting(
                    label = "Grid Top Edge",
                    valueText = "${(config.gridTop * 100).toInt()}%",
                    value = config.gridTop,
                    range = 0.16f..0.32f,
                    onValueChange = { onUpdateGridBounds(config.gridLeft, it, config.gridBottom) }
                )
                SliderSetting(
                    label = "Grid Bottom Edge",
                    valueText = "${(config.gridBottom * 100).toInt()}%",
                    value = config.gridBottom,
                    range = 0.54f..0.70f,
                    onValueChange = { onUpdateGridBounds(config.gridLeft, config.gridTop, it) }
                )
                SliderSetting(
                    label = "Side Margin",
                    valueText = "${(config.gridLeft * 1000).toInt() / 10f}%",
                    value = config.gridLeft,
                    range = 0.02f..0.12f,
                    onValueChange = { onUpdateGridBounds(it, config.gridTop, config.gridBottom) }
                )
                SliderSetting(
                    label = "Tray Top Edge",
                    valueText = "${(config.trayTop * 100).toInt()}%",
                    value = config.trayTop,
                    range = 0.64f..0.78f,
                    onValueChange = {
                        onUpdateTrayBounds(it, config.trayBottom - config.trayTop)
                    }
                )
                SliderSetting(
                    label = "Overlay Glow Opacity",
                    valueText = "${(config.overlayOpacity * 100).toInt()}%",
                    value = config.overlayOpacity,
                    range = 0.40f..1.0f,
                    onValueChange = onUpdateOpacity
                )

                OutlinedButton(
                    onClick = onResetDefaults,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("reset_roi_button")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.reset_defaults))
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MintSurge
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}
