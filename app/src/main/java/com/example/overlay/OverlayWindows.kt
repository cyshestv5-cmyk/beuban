package com.example.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.OverlayRuntimeState
import com.example.data.ServiceStatus
import com.example.ui.theme.AmberPop
import com.example.ui.theme.CoralPulse
import com.example.ui.theme.CoralPulseContainer
import com.example.ui.theme.MintSurge
import com.example.ui.theme.MintSurgeContainer
import com.example.ui.theme.OnMintSurge
import com.example.ui.theme.SlateSurfaceDark
import com.example.ui.theme.TacticianTheme
import kotlin.math.roundToInt

/**
 * Click-through Material 3 Expressive overlay that renders ONLY the single next best move
 * directly over the active Block Blast Lite 8x8 board and highlights its tray slot.
 */
@Composable
fun BoardHighlightOverlay(runtimeState: OverlayRuntimeState) {
    TacticianTheme(darkTheme = true) {
        val rec = runtimeState.recommendation
        val showOverlay = runtimeState.status == ServiceStatus.GUIDING && rec != null
        val config = runtimeState.roiConfig
        val opacity = config.overlayOpacity.coerceIn(0.35f, 1f)

        val infiniteTransition = rememberInfiniteTransition(label = "mint_pulse")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 0.72f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(tween(180)) + scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.95f)
        ) {
            if (rec != null) {
                val animRow by animateFloatAsState(
                    targetValue = rec.targetRow.toFloat(),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "target_row"
                )
                val animCol by animateFloatAsState(
                    targetValue = rec.targetCol.toFloat(),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "target_col"
                )

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val widthPx = constraints.maxWidth.toFloat()
                    val heightPx = constraints.maxHeight.toFloat()
                    val density = LocalDensity.current

                    val gridLeftPx = config.gridLeft * widthPx
                    val gridTopPx = config.gridTop * heightPx
                    val cellWPx = config.cellWidth() * widthPx
                    val cellHPx = config.cellHeight() * heightPx

                    val slotBounds = config.traySlotBounds(rec.slotIndex)
                    val slotCenterX = ((slotBounds.left + slotBounds.right) * 0.5f) * widthPx
                    val slotTopY = slotBounds.top * heightPx

                    val targetCenterX = gridLeftPx + (animCol + rec.piece.width * 0.5f) * cellWPx
                    val targetCenterY = gridTopPx + (animRow + rec.piece.height * 0.5f) * cellHPx

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val inset = cellWPx * 0.08f
                        val corner = CornerRadius(cellWPx * 0.24f, cellWPx * 0.24f)

                        // Soft connector line from tray slot to target footprint center
                        drawLine(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    MintSurge.copy(alpha = 0.12f * opacity),
                                    MintSurge.copy(alpha = 0.42f * opacity * pulse)
                                ),
                                startY = slotTopY,
                                endY = targetCenterY
                            ),
                            start = Offset(slotCenterX, slotTopY - 12f),
                            end = Offset(targetCenterX, targetCenterY),
                            strokeWidth = 4f
                        )

                        for ((dr, dc) in rec.piece.cells()) {
                            val left = gridLeftPx + (animCol + dc) * cellWPx + inset
                            val top = gridTopPx + (animRow + dr) * cellHPx + inset
                            val w = cellWPx - inset * 2f
                            val h = cellHPx - inset * 2f

                            // Outer ambient glow
                            drawRoundRect(
                                color = MintSurge.copy(alpha = 0.18f * opacity * pulse),
                                topLeft = Offset(left - 5f, top - 5f),
                                size = Size(w + 10f, h + 10f),
                                cornerRadius = CornerRadius(corner.x + 4f, corner.y + 4f)
                            )

                            // Expressive Mint Surge translucent fill
                            drawRoundRect(
                                color = MintSurge.copy(alpha = 0.34f * opacity),
                                topLeft = Offset(left, top),
                                size = Size(w, h),
                                cornerRadius = corner
                            )

                            // Crisp luminous border
                            drawRoundRect(
                                color = MintSurge.copy(alpha = 0.96f * opacity),
                                topLeft = Offset(left, top),
                                size = Size(w, h),
                                cornerRadius = corner,
                                style = Stroke(width = 5.5f)
                            )
                        }

                        // Subtle ring around source tray slot
                        drawCircle(
                            color = MintSurge.copy(alpha = 0.25f * opacity * pulse),
                            radius = cellWPx * 0.52f,
                            center = Offset(slotCenterX, (slotBounds.top + slotBounds.bottom) * 0.5f * heightPx),
                            style = Stroke(width = 4f)
                        )
                    }

                    // Compact M3 Expressive "NEXT" pill centered dynamically above the chosen tray slot
                    var badgeWidthPx by remember { mutableIntStateOf(0) }
                    val badgeX = (slotCenterX - badgeWidthPx / 2f).roundToInt()
                    val badgeY = with(density) { (slotTopY - 26.dp.toPx()).roundToInt() }

                    Surface(
                        modifier = Modifier
                            .offset { IntOffset(badgeX, badgeY) }
                            .onSizeChanged { badgeWidthPx = it.width }
                            .testTag("overlay_next_badge"),
                        shape = RoundedCornerShape(50),
                        color = MintSurge.copy(alpha = 0.95f * opacity),
                        shadowElevation = 6.dp
                    ) {
                        val badgeLabel = if (rec.linesCleared > 0) {
                            "${stringResource(R.string.overlay_next_badge)} • ×${rec.linesCleared}"
                        } else {
                            stringResource(R.string.overlay_next_badge)
                        }
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = OnMintSurge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Minimalist Material 3 Expressive floating control pill containing Start/Pause and Stop ONLY.
 */
@Composable
fun FloatingControlPill(
    status: ServiceStatus,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    TacticianTheme(darkTheme = true) {
        val isPaused = status == ServiceStatus.PAUSED
        val indicatorColor = when (status) {
            ServiceStatus.GUIDING -> MintSurge
            ServiceStatus.SEARCHING -> Color(0xFF4D8DFF)
            ServiceStatus.PAUSED -> AmberPop
            ServiceStatus.STOPPED -> CoralPulse
        }

        Surface(
            modifier = Modifier
                .padding(4.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount.x, dragAmount.y)
                        }
                    )
                }
                .testTag("floating_control_pill"),
            shape = RoundedCornerShape(50),
            color = SlateSurfaceDark.copy(alpha = 0.92f),
            border = BorderStroke(
                width = 1.5.dp,
                color = indicatorColor.copy(alpha = 0.55f)
            ),
            shadowElevation = 10.dp,
            tonalElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Drag grip + status dot
                Box(
                    modifier = Modifier.padding(start = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragIndicator,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.size(18.dp)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(7.dp)
                            .background(indicatorColor, CircleShape)
                    )
                }

                // Start / Pause toggle (48dp touch target)
                FilledIconButton(
                    onClick = onTogglePause,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("pill_toggle_pause_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isPaused) MintSurge else MintSurgeContainer,
                        contentColor = if (isPaused) OnMintSurge else MintSurge
                    )
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = stringResource(
                            if (isPaused) R.string.action_resume else R.string.action_pause
                        )
                    )
                }

                // Stop button (48dp touch target)
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("pill_stop_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CoralPulseContainer,
                        contentColor = CoralPulse
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = stringResource(R.string.action_stop)
                    )
                }
            }
        }
    }
}
