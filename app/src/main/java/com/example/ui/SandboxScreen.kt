package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.PieceCatalog
import com.example.engine.PieceShape
import com.example.ui.theme.AmberPop
import com.example.ui.theme.ElectricCobalt
import com.example.ui.theme.MintSurge
import com.example.ui.theme.MintSurgeContainer
import com.example.ui.theme.OnMintSurge

@Composable
fun SandboxScreen(
    state: SandboxUiState,
    onToggleCell: (row: Int, col: Int) -> Unit,
    onSelectSlot: (Int) -> Unit,
    onPickPiece: (PieceShape?) -> Unit,
    onSolve: () -> Unit,
    onClearBoard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rec = state.recommendation
    val highlightedMask = if (rec != null) {
        rec.piece.maskAt(rec.targetRow, rec.targetCol)
    } else 0L

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.sandbox_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.sandbox_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Interactive 8x8 Bitboard Grid
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .testTag("sandbox_board")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (r in 0 until 8) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (c in 0 until 8) {
                            val bit = 1L shl (r * 8 + c)
                            val isOccupied = (state.boardMask and bit) != 0L
                            val isRecommended = (highlightedMask and bit) != 0L

                            val cellColor by animateColorAsState(
                                targetValue = when {
                                    isRecommended -> MintSurge
                                    isOccupied -> ElectricCobalt
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                label = "sandbox_cell_$r$c"
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(cellColor)
                                    .clickable { onToggleCell(r, c) }
                                    .testTag("cell_${r}_${c}")
                            )
                        }
                    }
                }
            }
        }

        // 3-Slot Tray Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (slot in 0..2) {
                val piece = state.trayPieces[slot]
                val isSelected = state.selectedSlot == slot
                val isNextMove = rec?.slotIndex == slot

                Surface(
                    onClick = { onSelectSlot(slot) },
                    modifier = Modifier
                        .weight(1f)
                        .height(74.dp)
                        .testTag("sandbox_slot_$slot"),
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        width = if (isNextMove) 2.dp else if (isSelected) 1.5.dp else 1.dp,
                        color = when {
                            isNextMove -> MintSurge
                            isSelected -> ElectricCobalt
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isNextMove) "NEXT • Slot ${slot + 1}" else "Slot ${slot + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isNextMove) MintSurge else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = piece?.label ?: "Empty",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Piece Picker Strip for Active Slot
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.trayPieces[state.selectedSlot] == null,
                onClick = { onPickPiece(null) },
                label = { Text("Empty") }
            )
            PieceCatalog.ALL.take(24).forEach { piece ->
                val chosen = state.trayPieces[state.selectedSlot]?.id == piece.id
                FilterChip(
                    selected = chosen,
                    onClick = { onPickPiece(piece) },
                    label = { Text(piece.label) }
                )
            }
        }

        // Solve & Clear Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onClearBoard,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .weight(0.9f)
                    .height(52.dp)
                    .testTag("sandbox_clear_button")
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sandbox_clear))
            }

            Button(
                onClick = onSolve,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MintSurge,
                    contentColor = OnMintSurge
                ),
                modifier = Modifier
                    .weight(1.3f)
                    .height(52.dp)
                    .testTag("sandbox_solve_button")
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.sandbox_solve),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        // Lookahead Result Card
        if (rec != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MintSurge.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sandbox_result_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Best Next Move: Slot ${rec.slotIndex + 1} (${rec.piece.label})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MintSurge
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MintSurgeContainer
                        ) {
                            Text(
                                text = "${rec.solveTimeMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MintSurge,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Place at Row ${rec.targetRow + 1}, Col ${rec.targetCol + 1} • Monte Carlo Survival: ${(rec.monteCarloSurvivalRate * 100).toInt()}% • Bulky Capacity: ${rec.bulkyCapacityScore}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Planned 3-Move Sequence: " + rec.fullPlan.joinToString(" → ") {
                            "S${it.slotIndex + 1}(${it.row + 1},${it.col + 1})"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberPop
                    )
                }
            }
        }
    }
}
