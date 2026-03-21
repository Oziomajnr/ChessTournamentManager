package com.chess.tournament.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chess.tournament.db.entity.Pairing
import com.chess.tournament.db.entity.PairingResult
import com.chess.tournament.report.ReportGenerator
import com.chess.tournament.viewmodel.TournamentViewModel
import android.content.Intent
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundDetailScreen(
    tournamentId: Long,
    roundNumber: Int,
    tournamentViewModel: TournamentViewModel,
    onNavigateBack: () -> Unit
) {
    val pairingsFlow = remember(tournamentId, roundNumber) {
        tournamentViewModel.getPairingsForRound(roundNumber)
    }
    val pairings by pairingsFlow.collectAsState(initial = emptyList())
    val standings by tournamentViewModel.standings.collectAsState()

    val playerNames = remember(standings) {
        standings.associate { it.playerId to it.name }
    }
    val tournament by tournamentViewModel.selectedTournament.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Round $roundNumber") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val t = tournament ?: return@IconButton
                        val report = ReportGenerator().generatePairingsReport(t, roundNumber, pairings, playerNames)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "${t.name} — Round $roundNumber")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Pairings"))
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }
            items(pairings, key = { it.id }) { pairing ->
                PairingCard(
                    pairing = pairing,
                    whiteName = playerNames[pairing.whitePlayerId] ?: "Unknown",
                    blackName = pairing.blackPlayerId?.let { playerNames[it] } ?: "BYE",
                    onResultSelected = { result ->
                        tournamentViewModel.updatePairingResult(pairing.id, result)
                    }
                )
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PairingCard(
    pairing: Pairing,
    whiteName: String,
    blackName: String,
    onResultSelected: (PairingResult) -> Unit
) {
    val isBye = pairing.blackPlayerId == null
    var showForfeit by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Board ${pairing.board}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ResultBadge(pairing.result)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = whiteName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = when (pairing.result) {
                            PairingResult.WHITE_WINS, PairingResult.BYE, PairingResult.FORFEIT_WHITE -> FontWeight.Bold
                            else -> FontWeight.Normal
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "vs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = blackName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = when (pairing.result) {
                            PairingResult.BLACK_WINS, PairingResult.FORFEIT_BLACK -> FontWeight.Bold
                            else -> FontWeight.Normal
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF333333)),
                        contentAlignment = Alignment.Center
                    ) {}
                }
            }

            if (!isBye && pairing.result != PairingResult.BYE) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ResultButton(
                        text = "1 - 0",
                        isSelected = pairing.result == PairingResult.WHITE_WINS,
                        onClick = { onResultSelected(PairingResult.WHITE_WINS) },
                        modifier = Modifier.weight(1f)
                    )
                    ResultButton(
                        text = "\u00BD - \u00BD",
                        isSelected = pairing.result == PairingResult.DRAW,
                        onClick = { onResultSelected(PairingResult.DRAW) },
                        modifier = Modifier.weight(1f)
                    )
                    ResultButton(
                        text = "0 - 1",
                        isSelected = pairing.result == PairingResult.BLACK_WINS,
                        onClick = { onResultSelected(PairingResult.BLACK_WINS) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { showForfeit = !showForfeit }) {
                        Text(
                            if (showForfeit) "Hide Forfeit" else "Forfeit...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (showForfeit) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ResultButton(
                            text = "F 1-0",
                            isSelected = pairing.result == PairingResult.FORFEIT_WHITE,
                            onClick = { onResultSelected(PairingResult.FORFEIT_WHITE) },
                            modifier = Modifier.weight(1f),
                            isForfeit = true
                        )
                        ResultButton(
                            text = "F 0-1",
                            isSelected = pairing.result == PairingResult.FORFEIT_BLACK,
                            onClick = { onResultSelected(PairingResult.FORFEIT_BLACK) },
                            modifier = Modifier.weight(1f),
                            isForfeit = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isForfeit: Boolean = false
) {
    val selectedColor = if (isForfeit) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        colors = if (isSelected) {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = selectedColor,
                contentColor = if (isForfeit) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onPrimary
            )
        } else {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
        }
    ) {
        Text(
            text = text,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun ResultBadge(result: PairingResult) {
    val (text, color) = when (result) {
        PairingResult.PENDING -> "Pending" to MaterialTheme.colorScheme.outline
        PairingResult.WHITE_WINS -> "1 - 0" to MaterialTheme.colorScheme.primary
        PairingResult.BLACK_WINS -> "0 - 1" to MaterialTheme.colorScheme.primary
        PairingResult.DRAW -> "\u00BD - \u00BD" to MaterialTheme.colorScheme.tertiary
        PairingResult.BYE -> "BYE" to MaterialTheme.colorScheme.secondary
        PairingResult.FORFEIT_WHITE -> "F 1-0" to MaterialTheme.colorScheme.error
        PairingResult.FORFEIT_BLACK -> "F 0-1" to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
