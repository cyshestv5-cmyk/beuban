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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.AdaptiveRoiConfig
import com.example.ui.theme.ElectricCobalt
import com.example.ui.theme.MintSurge

@Composable
fun CalibrateScreen(
    config: AdaptiveRoiConfig,
    onUpdateGridBounds: (leftMargin: Float, top: Float, bottom: Float) -> Unit,
    onUpdateTrayBounds: (trayTop: Float, trayHeight: Float) -> Unit,
    onUpdateOpacity: (Float) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    var previewWoodTheme by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
        }

        // Theme Preview Toggle (Light vs Wood)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = previewWoodTheme,
                onClick = { previewWoodTheme = true },
                label = { Text("Wood Theme Preview") },
                modifier = Modifier.testTag("preview_wood_chip")
            )
            FilterChip(
                selected = !previewWoodTheme,
                onClick = { previewWoodTheme = false },
                label = { Text("Light Theme Preview") },
                modifier = Modifier.testTag("preview_light_chip")
            )
        }

        // Visual Simulated Phone Screen with Live ROI Alignment Wireframe
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(9f / 20f),
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            shadowElevation = 8.dp
        ) {
            val bgColor = if (previewWoodTheme) Color(0xFFC46C47) else Color(0xFFF6F8FC)
            val boardBg = if (previewWoodTheme) Color(0xFF4B2314) else Color(0xFFFFFFFF)
            val lineColor = if (previewWoodTheme) Color(0xFF5D2D1B) else Color(0xFFDCE2EC)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Simulated game board at default Block Blast Lite aspect
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
