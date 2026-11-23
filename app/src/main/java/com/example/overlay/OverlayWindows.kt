package com.example.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
 *
 * Rendering uses a hollow outer-perimeter silhouette + below-tray indicator so cell centers
 * and tray interiors remain 100% unobstructed for both the player and MediaProjection parsing.
 */
@Composable
fun BoardHighlightOverlay(runtimeState: OverlayRuntimeState) {
    TacticianTheme(darkTheme = true) {
        val rec = runtimeState.recommendation
        val showOverlay = runtimeState.status == ServiceStatus.GUIDING && rec != null
        val config = runtimeState.roiConfig
        val opacity = config.overlayOpacity.coerceIn(0.35f, 1f)

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

                val occupiedCells = remember(rec.piece) { rec.piece.cells().toSet() }

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
                    val trayBottomPx = config.trayBottom * heightPx

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Keep strokes strictly in the outer 12% border of each cell so the
                        // parser's inner cell-center patch and divider scans remain untouched.
                        val insetX = cellWPx * 0.11f
                        val insetY = cellHPx * 0.11f
                        val outerCasingWidth = 6.dp.toPx()
                        val innerMintWidth = 3.5.dp.toPx()
                        val cornerPadX = cellWPx * 0.10f
                        val cornerPadY = cellHPx * 0.10f

                        for ((dr, dc) in occupiedCells) {
                            val cellLeft = gridLeftPx + (animCol + dc) * cellWPx
                            val cellTop = gridTopPx + (animRow + dr) * cellHPx
                            val cellRight = cellLeft + cellWPx
                            val cellBottom = cellTop + cellHPx

                            val hasTop = (dr - 1 to dc) in occupiedCells
                            val hasBottom = (dr + 1 to dc) in occupiedCells
                            val hasLeft = (dr to dc - 1) in occupiedCells
                            val hasRight = (dr to dc + 1) in occupiedCells

                            // Top outer boundary
                            if (!hasTop) {
                                val y = cellTop + insetY
                                val startX = if (hasLeft) cellLeft else cellLeft + insetX
                                val endX = if (hasRight) cellRight else cellRight - insetX
                                drawLine(
                                    color = SlateSurfaceDark.copy(alpha = 0.72f * opacity),
                                    start = Offset(startX, y),
                                    end = Offset(endX, y),
                                    strokeWidth = outerCasingWidth,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = MintSurge.copy(alpha = 0.96f * opacity),
                                    start = Offset(startX, y),
                                    end = Offset(endX, y),
                                    strokeWidth = innerMintWidth,
                                    cap = StrokeCap.Round
                                )
                            }

                            // Bottom outer boundary
                            if (!hasBottom) {
                                val y = cellBottom - insetY
                                val startX = if (hasLeft) cellLeft else cellLeft + insetX
                                val endX = if (hasRight) cellRight else cellRight - insetX
                                drawLine(
                                    color = SlateSurfaceDark.copy(alpha = 0.72f * opacity),
                                    start = Offset(startX, y),
                                    end = Offset(endX, y),
                                    strokeWidth = outerCasingWidth,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = MintSurge.copy(alpha = 0.96f * opacity),
                                    start = Offset(startX, y),
                                    end = Offset(endX, y),
                                    strokeWidth = innerMintWidth,
                                    cap = StrokeCap.Round
                                )
                            }

                            // Left outer boundary
                            if (!hasLeft) {
                                val x = cellLeft + insetX
                                val startY = if (hasTop) cellTop else cellTop + insetY
                                val endY = if (hasBottom) cellBottom else cellBottom - insetY
                                drawLine(
                                    color = SlateSurfaceDark.copy(alpha = 0.72f * opacity),
                                    start = Offset(x, startY),
                                    end = Offset(x, endY),
                                    strokeWidth = outerCasingWidth,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = MintSurge.copy(alpha = 0.96f * opacity),
                                    start = Offset(x, startY),
                                    end = Offset(x, endY),
                                    strokeWidth = innerMintWidth,
                                    cap = StrokeCap.Round
                                )
                            }

                            // Right outer boundary
                            if (!hasRight) {
                                val x = cellRight - insetX
                                val startY = if (hasTop) cellTop else cellTop + insetY
                                val endY = if (hasBottom) cellBottom else cellBottom - insetY
                                drawLine(
                                    color = SlateSurfaceDark.copy(alpha = 0.72f * opacity),
                                    start = Offset(x, startY),
                                    end = Offset(x, endY),
                                    strokeWidth = outerCasingWidth,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = MintSurge.copy(alpha = 0.96f * opacity),
                                    start = Offset(x, startY),
                                    end = Offset(x, endY),
                                    strokeWidth = innerMintWidth,
                                    cap = StrokeCap.Round
                                )
                            }

                            // Subtle bright corner dots on convex outer corners for quick visual lock
                            val dotRadius = 2.4.dp.toPx()
                            if (!hasTop && !hasLeft) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.9f * opacity),
                                    radius = dotRadius,
                                    center = Offset(cellLeft + cornerPadX, cellTop + cornerPadY)
                                )
                            }
                            if (!hasTop && !hasRight) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.9f * opacity),
                                    radius = dotRadius,
                                    center = Offset(cellRight - cornerPadX, cellTop + cornerPadY)
                                )
                            }
                            if (!hasBottom && !hasLeft) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.9f * opacity),
                                    radius = dotRadius,
                                    center = Offset(cellLeft + cornerPadX, cellBottom - cornerPadY)
                                )
                            }
                            if (!hasBottom && !hasRight) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.9f * opacity),
                                    radius = dotRadius,
                                    center = Offset(cellRight - cornerPadX, cellBottom - cornerPadY)
                                )
                            }
                        }

                        // Sleek underline bar strictly below trayBottom (zero interference with tray slot)
                        val barWidth = cellWPx * 1.15f
                        val barHeight = 3.5.dp.toPx()
                        val barTop = (trayBottomPx + 3.dp.toPx()).coerceAtMost(heightPx - 64.dp.toPx())
                        drawRoundRect(
                            color = MintSurge.copy(alpha = 0.94f * opacity),
                            topLeft = Offset(slotCenterX - barWidth / 2f, barTop),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barHeight / 2f, barHeight / 2f)
                        )
                    }

                    // Compact M3 Expressive "NEXT" pill placed strictly BELOW trayBottom
                    var badgeWidthPx by remember { mutableIntStateOf(0) }
                    val badgeX = (slotCenterX - badgeWidthPx / 2f)
                        .coerceIn(8f, (widthPx - badgeWidthPx - 8f).coerceAtLeast(8f))
                        .roundToInt()
                    val badgeY = with(density) {
                        (trayBottomPx + 10.dp.toPx())
                            .coerceAtMost(heightPx - 56.dp.toPx())
                            .roundToInt()
                    }

                    Surface(
                        modifier = Modifier
                            .offset { IntOffset(badgeX, badgeY) }
                            .onSizeChanged { badgeWidthPx = it.width }
                            .testTag("overlay_next_badge"),
                        shape = RoundedCornerShape(50),
                        color = MintSurge.copy(alpha = 0.96f * opacity),
                        border = BorderStroke(1.5.dp, SlateSurfaceDark.copy(alpha = 0.85f)),
                        shadowElevation = 6.dp
                    ) {
                        val badgeLabel = if (rec.linesCleared > 0) {
                            "▲ ${stringResource(R.string.overlay_next_badge)} • ×${rec.linesCleared}"
                        } else {
                            "▲ ${stringResource(R.string.overlay_next_badge)}"
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
            ServiceStatus.GUIDING, ServiceStatus.DRAGGING -> MintSurge
            ServiceStatus.VERIFYING -> MintSurge.copy(alpha = 0.7f)
            ServiceStatus.THINKING -> Color(0xFF7B9DFF)
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
