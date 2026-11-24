package com.chunkymonkey.chess.tournament.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chunkymonkey.chess.tournament.db.entity.Player
import com.chunkymonkey.chess.tournament.db.entity.TournamentType
import com.chunkymonkey.chess.tournament.engine.RoundRobinEngine
import com.chunkymonkey.chess.tournament.viewmodel.PlayerViewModel
import com.chunkymonkey.chess.tournament.viewmodel.TournamentViewModel
import kotlin.math.ceil
import kotlin.math.log2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTournamentScreen(
    playerViewModel: PlayerViewModel,
    tournamentViewModel: TournamentViewModel,
    onNavigateBack: () -> Unit
) {
    val allPlayers by playerViewModel.allPlayers.collectAsState()
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(TournamentType.SWISS) }
    var selectedPlayerIds by remember { mutableStateOf(setOf<Long>()) }
    var roundsText by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf(false) }

    val suggestedRounds = when (selectedType) {
        TournamentType.SWISS -> {
            if (selectedPlayerIds.size >= 2)
                ceil(log2(selectedPlayerIds.size.toDouble())).toInt().coerceAtLeast(1)
            else 1
        }
        TournamentType.ROUND_ROBIN -> {
            if (selectedPlayerIds.size >= 2)
                RoundRobinEngine.requiredRounds(selectedPlayerIds.size)
            else 1
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Tournament") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = false
                    },
                    label = { Text("Tournament Name") },
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text("Name is required") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                var showSwissInfo by remember { mutableStateOf(false) }
                var showRoundRobinInfo by remember { mutableStateOf(false) }

                Text(
                    "Tournament System",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedType == TournamentType.SWISS,
                        onClick = { selectedType = TournamentType.SWISS },
                        label = { Text("Swiss") }
                    )
                    IconButton(
                        onClick = { showSwissInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "Swiss system rules",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = selectedType == TournamentType.ROUND_ROBIN,
                        onClick = { selectedType = TournamentType.ROUND_ROBIN },
                        label = { Text("Round Robin") }
                    )
                    IconButton(
                        onClick = { showRoundRobinInfo = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "Round Robin rules",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (showSwissInfo) {
                    TournamentRulesDialog(
                        type = TournamentType.SWISS,
                        onDismiss = { showSwissInfo = false }
                    )
                }
                if (showRoundRobinInfo) {
                    TournamentRulesDialog(
                        type = TournamentType.ROUND_ROBIN,
                        onDismiss = { showRoundRobinInfo = false }
                    )
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = roundsText.ifEmpty { suggestedRounds.toString() },
                        onValueChange = { roundsText = it.filter { c -> c.isDigit() } },
                        label = { Text("Number of Rounds") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedType == TournamentType.ROUND_ROBIN) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "($suggestedRounds needed for full RR)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Select Players (${selectedPlayerIds.size} selected)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (allPlayers.isNotEmpty()) {
                        Row {
                            androidx.compose.material3.TextButton(
                                onClick = { selectedPlayerIds = allPlayers.map { it.id }.toSet() }
                            ) { Text("All") }
                            androidx.compose.material3.TextButton(
                                onClick = { selectedPlayerIds = emptySet() }
                            ) { Text("None") }
                        }
                    }
                }
                if (playerError) {
                    Text(
                        "Select at least 2 players",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (allPlayers.isEmpty()) {
                item {
                    Text(
                        "No players available. Add players first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(allPlayers, key = { it.id }) { player ->
                    PlayerSelectionRow(
                        player = player,
                        isSelected = player.id in selectedPlayerIds,
                        onToggle = {
                            selectedPlayerIds = if (player.id in selectedPlayerIds) {
                                selectedPlayerIds - player.id
                            } else {
                                selectedPlayerIds + player.id
                            }
                            playerError = false
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        nameError = name.isBlank()
                        playerError = selectedPlayerIds.size < 2
                        if (!nameError && !playerError) {
                            val rounds = roundsText.toIntOrNull() ?: suggestedRounds
                            tournamentViewModel.createTournament(
                                name = name.trim(),
                                type = selectedType,
                                playerIds = selectedPlayerIds.toList(),
                                totalRounds = rounds.coerceAtLeast(1)
                            )
                            onNavigateBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Tournament")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TournamentRulesDialog(
    type: TournamentType,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
        title = {
            Text(
                text = if (type == TournamentType.SWISS) "Swiss System" else "Round Robin",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (type == TournamentType.SWISS) {
                    SwissRulesContent()
                } else {
                    RoundRobinRulesContent()
                }
            }
        }
    )
}

@Composable
private fun SwissRulesContent() {
    Text(
        "Players with similar scores are paired each round. " +
        "Efficient for large fields \u2014 only \u2308log\u2082(n)\u2309 rounds needed.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text("FIDE Rules (C.04.1)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(8.dp))

    val rules = listOf(
        "R1" to "The number of rounds is declared beforehand.",
        "R2" to "Two players shall not play each other more than once.",
        "R3" to "If odd number of players, one receives a bye worth 1 point.",
        "R4" to "A player who already received a bye shall not get another.",
        "R5" to "Players are generally paired with others on the same score.",
        "R6" to "For each player, |whites \u2212 blacks| shall not exceed 2.",
        "R7" to "No player shall receive the same colour three times in a row.",
        "R8" to "A player gets the colour they played fewer games with. If equal, alternate from last."
    )

    rules.forEach { (id, text) ->
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text(
                text = id,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(32.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    Text("Best for", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(4.dp))
    val pros = listOf(
        "Large tournaments (8+ players)",
        "Limited time (few rounds needed)",
        "Competitive ranking with minimal games"
    )
    pros.forEach { item ->
        Text("\u2022  $item", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp))
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text("Rounds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "Recommended: \u2308log\u2082(n)\u2309 rounds.\n" +
        "e.g. 8 players \u2192 3 rounds, 16 \u2192 4, 32 \u2192 5.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun RoundRobinRulesContent() {
    Text(
        "Every player plays every other player exactly once. " +
        "The most thorough format \u2014 no pairing luck.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text("Rules", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(8.dp))

    val rules = listOf(
        "RR1" to "Every participant plays every other participant exactly once.",
        "RR2" to "Total rounds: n\u22121 (even players) or n (odd players).",
        "RR3" to "If odd players, each gets exactly one bye across the tournament.",
        "RR4" to "All pairings for all rounds are determined before the event starts.",
        "RR5" to "Colours alternate by board position and round for approximate balance."
    )

    rules.forEach { (id, text) ->
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text(
                text = id,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.width(40.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    Text("Best for", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(4.dp))
    val pros = listOf(
        "Small groups (4\u201312 players)",
        "Maximum fairness (everyone plays everyone)",
        "League-style events with ample time"
    )
    pros.forEach { item ->
        Text("\u2022  $item", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp))
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text("Algorithm", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "Uses the circle (polygon) rotation method:\n" +
        "Player 1 is fixed; all others rotate each round.\n" +
        "Pairs are formed by matching top and bottom positions.",
        style = MaterialTheme.typography.bodySmall
    )

    Spacer(modifier = Modifier.height(8.dp))
    Text("Rounds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "Fixed: n\u22121 rounds for even n, n for odd n.\n" +
        "e.g. 6 players \u2192 5 rounds, 7 \u2192 7, 10 \u2192 9.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun PlayerSelectionRow(
    player: Player,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = player.name,
                style = MaterialTheme.typography.bodyLarge
            )
            if (player.fideId.isNotBlank()) {
                Text(
                    text = "FIDE: ${player.fideId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (player.rating > 0) {
            Text(
                text = "${player.rating}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
