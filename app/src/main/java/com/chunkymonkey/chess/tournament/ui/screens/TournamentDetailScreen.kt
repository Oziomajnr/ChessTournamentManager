package com.chunkymonkey.chess.tournament.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chunkymonkey.chess.tournament.db.entity.Pairing
import com.chunkymonkey.chess.tournament.db.entity.PairingResult
import com.chunkymonkey.chess.tournament.db.entity.PlayerStanding
import com.chunkymonkey.chess.tournament.db.entity.PlayerTournamentStatus
import com.chunkymonkey.chess.tournament.db.entity.TiebreakType
import com.chunkymonkey.chess.tournament.db.entity.Tournament
import com.chunkymonkey.chess.tournament.db.entity.TournamentStatus
import com.chunkymonkey.chess.tournament.db.entity.TournamentType
import com.chunkymonkey.chess.tournament.report.ReportGenerator
import com.chunkymonkey.chess.tournament.viewmodel.PublishState
import com.chunkymonkey.chess.tournament.viewmodel.TournamentViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.chunkymonkey.chess.tournament.review.PlayReviewPrompt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TournamentDetailScreen(
    tournamentId: Long,
    tournamentViewModel: TournamentViewModel,
    onNavigateBack: () -> Unit,
    onRoundClick: (Int) -> Unit,
    onSettingsClick: () -> Unit,
    reviewPrompt: PlayReviewPrompt
) {
    LaunchedEffect(tournamentId) {
        tournamentViewModel.selectTournament(tournamentId)
        tournamentViewModel.clearRoundError()
    }

    val selectedTournament by tournamentViewModel.selectedTournament.collectAsState()
    val tournament = selectedTournament?.takeIf { it.id == tournamentId }
    val standings by tournamentViewModel.standings.collectAsState()
    val roundNumbers by tournamentViewModel.roundNumbers.collectAsState()
    val roundError by tournamentViewModel.roundError.collectAsState()
    val isGeneratingRound by tournamentViewModel.isGeneratingRound.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Standings", "Rounds", "Cross-Table")
    val context = LocalContext.current
    val publishState by tournamentViewModel.publishState.collectAsState()
    var showPublishDialog by remember { mutableStateOf(false) }
    var endingTournament by remember(tournamentId) { mutableStateOf(false) }
    val onRoundPaired = rememberRoundReviewHandler(
        tournamentId,
        visibleTournament = { tournament },
        overviewAvailable = {
            !endingTournament && !showPublishDialog && !tournamentViewModel.isGeneratingRound.value &&
                tournamentViewModel.publishState.value == PublishState.Idle
        },
        prompt = reviewPrompt
    )

    when (publishState) {
        is PublishState.Success, is PublishState.Error -> {
            if (!showPublishDialog) showPublishDialog = true
        }
        else -> {}
    }

    if (showPublishDialog) {
        PublishDialog(
            state = publishState,
            onDismiss = {
                showPublishDialog = false
                tournamentViewModel.clearPublishState()
            },
            onCopyLink = { url ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Tournament URL", url))
                Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
            },
            onOpenLink = { url ->
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                context.startActivity(intent)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tournament?.name ?: "Tournament") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { tournamentViewModel.publishToWeb() },
                        enabled = publishState !is PublishState.Loading
                    ) {
                        if (publishState is PublishState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = "Publish to Web",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    IconButton(onClick = {
                        val t = tournament ?: return@IconButton
                        val names = standings.associate { it.playerId to it.name }
                        val reportGen = ReportGenerator()
                        val text = reportGen.generateStandingsReport(t, standings, names)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, t.name)
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Report"))
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            tournament?.let { t ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Round ${t.currentRound} / ${t.totalRounds}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    when (t.type) {
                                        TournamentType.SWISS -> "Swiss"
                                        TournamentType.ROUND_ROBIN -> "Round Robin"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                if (!t.timeControl.isNullOrBlank()) {
                                    Text(
                                        t.timeControl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (t.status != TournamentStatus.COMPLETED &&
                                t.currentRound < t.totalRounds
                            ) {
                                Button(
                                    onClick = { tournamentViewModel.generateNextRound(t, onRoundPaired) },
                                    enabled = !isGeneratingRound,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isGeneratingRound) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.width(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Generating...")
                                    } else {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Generate Round ${t.currentRound + 1}")
                                    }
                                }
                            }
                            if (t.status == TournamentStatus.IN_PROGRESS) {
                                OutlinedButton(
                                    onClick = {
                                        endingTournament = true
                                        tournamentViewModel.completeTournament()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("End")
                                }
                            }
                        }
                        if (roundError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = roundError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> StandingsTab(standings, tournament, tournamentViewModel)
                1 -> RoundsTab(roundNumbers, onRoundClick)
                2 -> CrossTableTab(standings, tournamentViewModel)
            }
        }
    }
}

@Composable
private fun StandingsTab(
    standings: List<PlayerStanding>,
    tournament: Tournament?,
    tournamentViewModel: TournamentViewModel
) {
    if (standings.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "No standings yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Generate the first round to see standings",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        return
    }

    val tiebreakChain = tournament?.getTiebreakChain() ?: listOf(TiebreakType.BUCHHOLZ)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "#",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(32.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    "Player",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(120.dp)
                )
                Text(
                    "Rtg",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    "Pts",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.Center
                )
                tiebreakChain.take(4).forEach { tb ->
                    Text(
                        tiebreakShortName(tb),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            HorizontalDivider()
        }

        itemsIndexed(standings) { index, standing ->
            var showMenu by remember { mutableStateOf(false) }
            val isWithdrawn = standing.status == PlayerTournamentStatus.WITHDRAWN

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .clickable { showMenu = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (index < 3) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(32.dp),
                    textAlign = TextAlign.Center,
                    color = when (index) {
                        0 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Column(modifier = Modifier.width(120.dp)) {
                    Text(
                        standing.name + if (isWithdrawn) " (W)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isWithdrawn) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (standing.fideId.isNotBlank()) {
                        Text(
                            standing.fideId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "${standing.rating}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    formatScore(standing.score),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )
                val tbValues = listOf(standing.tiebreak, standing.tiebreak2, standing.tiebreak3, standing.tiebreak4)
                tiebreakChain.take(4).forEachIndexed { i, _ ->
                    Text(
                        formatScore(tbValues.getOrElse(i) { 0f }),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (tournament?.status == TournamentStatus.IN_PROGRESS) {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (isWithdrawn) {
                            DropdownMenuItem(
                                text = { Text("Reinstate Player") },
                                onClick = {
                                    tournamentViewModel.reinstatePlayer(standing.playerId)
                                    showMenu = false
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Withdraw Player") },
                                onClick = {
                                    tournamentViewModel.withdrawPlayer(standing.playerId)
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (index < standings.size - 1) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun CrossTableTab(
    standings: List<PlayerStanding>,
    tournamentViewModel: TournamentViewModel
) {
    val pairingsFlow = remember { tournamentViewModel.getAllPairingsForCrossTable() }
    val allPairings by pairingsFlow.collectAsState(initial = emptyList())

    if (standings.isEmpty() || allPairings.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "No data for cross-table yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val playerOrder = standings.map { it.playerId }
    val playerNames = standings.associate { it.playerId to it.name }

    val matrix = buildResultMatrix(playerOrder, allPairings)

    val scrollState = rememberScrollState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.horizontalScroll(scrollState)
            ) {
                Text(
                    "#",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    "Player",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(100.dp)
                )
                for (i in playerOrder.indices) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    "Pts",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .width(40.dp)
                        .padding(start = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
            HorizontalDivider()
        }

        itemsIndexed(playerOrder) { rowIdx, playerId ->
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${rowIdx + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    playerNames[playerId] ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(100.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                for (colIdx in playerOrder.indices) {
                    val cell = if (rowIdx == colIdx) "X" else matrix[rowIdx][colIdx]
                    val cellColor = when {
                        rowIdx == colIdx -> MaterialTheme.colorScheme.onSurfaceVariant
                        cell == "1" || cell == "+" -> MaterialTheme.colorScheme.primary
                        cell == "0" || cell == "-" -> MaterialTheme.colorScheme.error
                        cell == "\u00BD" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        cell,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (rowIdx == colIdx) FontWeight.Normal else FontWeight.Bold,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.Center,
                        color = cellColor
                    )
                }
                val standing = standings.getOrNull(rowIdx)
                Text(
                    formatScore(standing?.score ?: 0f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .width(40.dp)
                        .padding(start = 4.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (rowIdx < playerOrder.size - 1) {
                HorizontalDivider()
            }
        }
    }
}

private fun buildResultMatrix(
    playerOrder: List<Long>,
    allPairings: List<Pairing>
): List<List<String>> {
    val n = playerOrder.size
    val idx = playerOrder.withIndex().associate { (i, id) -> id to i }
    val matrix = List(n) { MutableList(n) { "" } }

    for (p in allPairings) {
        if (p.blackPlayerId == null) continue
        val wIdx = idx[p.whitePlayerId] ?: continue
        val bIdx = idx[p.blackPlayerId] ?: continue
        when (p.result) {
            PairingResult.WHITE_WINS, PairingResult.FORFEIT_WHITE -> {
                val sym = if (p.result == PairingResult.FORFEIT_WHITE) "+" else "1"
                val opp = if (p.result == PairingResult.FORFEIT_WHITE) "-" else "0"
                matrix[wIdx][bIdx] = appendResult(matrix[wIdx][bIdx], sym)
                matrix[bIdx][wIdx] = appendResult(matrix[bIdx][wIdx], opp)
            }
            PairingResult.BLACK_WINS, PairingResult.FORFEIT_BLACK -> {
                val sym = if (p.result == PairingResult.FORFEIT_BLACK) "+" else "1"
                val opp = if (p.result == PairingResult.FORFEIT_BLACK) "-" else "0"
                matrix[bIdx][wIdx] = appendResult(matrix[bIdx][wIdx], sym)
                matrix[wIdx][bIdx] = appendResult(matrix[wIdx][bIdx], opp)
            }
            PairingResult.DRAW -> {
                matrix[wIdx][bIdx] = appendResult(matrix[wIdx][bIdx], "\u00BD")
                matrix[bIdx][wIdx] = appendResult(matrix[bIdx][wIdx], "\u00BD")
            }
            PairingResult.PENDING -> {
                matrix[wIdx][bIdx] = appendResult(matrix[wIdx][bIdx], ".")
                matrix[bIdx][wIdx] = appendResult(matrix[bIdx][wIdx], ".")
            }
            PairingResult.BYE -> {}
        }
    }
    return matrix
}

private fun appendResult(existing: String, newResult: String): String {
    return if (existing.isEmpty()) newResult else "$existing $newResult"
}

@Composable
private fun RoundsTab(
    roundNumbers: List<Int>,
    onRoundClick: (Int) -> Unit
) {
    if (roundNumbers.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "No rounds generated yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(roundNumbers) { roundNumber ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRoundClick(roundNumber) },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Round $roundNumber",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "View round",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PublishDialog(
    state: PublishState,
    onDismiss: () -> Unit,
    onCopyLink: (String) -> Unit,
    onOpenLink: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (state is PublishState.Success) {
                TextButton(onClick = { onOpenLink(state.url) }) { Text("Open in Browser") }
            }
        },
        title = {
            Text(
                when (state) {
                    is PublishState.Success -> "Published"
                    is PublishState.Error -> "Publish Failed"
                    else -> "Publishing..."
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            when (state) {
                is PublishState.Success -> {
                    Column {
                        Text(
                            "Tournament is live at:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenLink(state.url) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    state.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                )
                                IconButton(onClick = { onCopyLink(state.url) }) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy link",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Re-publish anytime to update the page with latest results.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is PublishState.Error -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    Text("Uploading tournament data...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    )
}

private fun tiebreakShortName(type: TiebreakType): String = when (type) {
    TiebreakType.BUCHHOLZ -> "BH"
    TiebreakType.BUCHHOLZ_CUT1 -> "BC1"
    TiebreakType.MEDIAN_BUCHHOLZ -> "MBH"
    TiebreakType.SONNEBORN_BERGER -> "SB"
    TiebreakType.DIRECT_ENCOUNTER -> "DE"
    TiebreakType.WINS -> "W"
    TiebreakType.BLACK_WINS -> "BW"
    TiebreakType.KOYA -> "KY"
}

private fun formatScore(score: Float): String {
    return if (score == score.toLong().toFloat()) {
        score.toLong().toString()
    } else {
        String.format("%.1f", score)
    }
}
