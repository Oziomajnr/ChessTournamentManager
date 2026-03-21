package com.chess.tournament.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chess.tournament.ChessTournamentApp
import com.chess.tournament.db.entity.Pairing
import com.chess.tournament.db.entity.PairingResult
import com.chess.tournament.db.entity.PlayerStanding
import com.chess.tournament.db.entity.PlayerTournamentStatus
import com.chess.tournament.db.entity.TiebreakType
import com.chess.tournament.db.entity.Tournament
import com.chess.tournament.db.entity.TournamentPlayer
import com.chess.tournament.db.entity.TournamentStatus
import com.chess.tournament.db.entity.TournamentType
import com.chess.tournament.engine.PairingEngine
import com.chess.tournament.engine.RoundRobinEngine
import com.chess.tournament.engine.SwissPairingEngine
import com.chess.tournament.engine.TiebreakCalculator
import com.chess.tournament.billing.ProStateManager
import com.chess.tournament.web.PublishResult
import com.chess.tournament.web.WebPublisher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class PublishState {
    data object Idle : PublishState()
    data object Loading : PublishState()
    data class Success(val url: String) : PublishState()
    data class Error(val message: String) : PublishState()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TournamentViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ChessTournamentApp.getDatabase(application)
    private val tournamentDao = db.tournamentDao()
    private val pairingDao = db.pairingDao()
    private val playerDao = db.playerDao()
    private val proStateManager = ChessTournamentApp.getProStateManager(application)

    val allTournaments = tournamentDao.getAllTournaments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTournamentId = MutableStateFlow<Long?>(null)
    private val _roundError = MutableStateFlow<String?>(null)
    val roundError = _roundError.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _publishState = MutableStateFlow<PublishState>(PublishState.Idle)
    val publishState: StateFlow<PublishState> = _publishState
    private val webPublisher = WebPublisher()

    val selectedTournament = _selectedTournamentId.flatMapLatest { id ->
        if (id != null) tournamentDao.getTournamentById(id) else flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val standings = _selectedTournamentId.flatMapLatest { id ->
        if (id != null) tournamentDao.getTournamentStandings(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val roundNumbers = _selectedTournamentId.flatMapLatest { id ->
        if (id != null) pairingDao.getRoundNumbers(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectTournament(id: Long) {
        _selectedTournamentId.value = id
    }

    fun createTournament(
        name: String,
        type: TournamentType,
        playerIds: List<Long>,
        totalRounds: Int,
        timeControl: String? = null,
        tiebreakConfig: String = "BUCHHOLZ"
    ) {
        viewModelScope.launch {
            val tournament = Tournament(
                name = name, type = type, totalRounds = totalRounds,
                timeControl = timeControl, tiebreakConfig = tiebreakConfig
            )
            val tournamentId = tournamentDao.insert(tournament)
            proStateManager.incrementTournamentCount()

            val players = playerDao.getPlayersByIds(playerIds)
                .sortedByDescending { it.rating }

            val tournamentPlayers = players.mapIndexed { index, player ->
                TournamentPlayer(
                    tournamentId = tournamentId,
                    playerId = player.id,
                    seedNumber = index + 1
                )
            }
            tournamentDao.insertTournamentPlayers(tournamentPlayers)
        }
    }

    fun deleteTournament(tournament: Tournament) {
        viewModelScope.launch {
            tournamentDao.delete(tournament)
        }
    }

    fun clearRoundError() {
        _roundError.value = null
    }

    fun generateNextRound() {
        viewModelScope.launch {
            _roundError.value = null
            val tournament = selectedTournament.value ?: return@launch
            if (tournament.currentRound >= tournament.totalRounds) return@launch

            if (tournament.currentRound > 0) {
                val pending = pairingDao.getPendingCount(tournament.id, tournament.currentRound)
                if (pending > 0) {
                    _roundError.value = "Round ${tournament.currentRound} still has $pending pending result${if (pending > 1) "s" else ""}. Complete all results before generating the next round."
                    return@launch
                }
            }

            val nextRound = tournament.currentRound + 1
            val currentStandings = tournamentDao.getTournamentStandingsOnce(tournament.id)
            val activeStandings = currentStandings.filter { it.status == PlayerTournamentStatus.ACTIVE }
            val previousPairings = pairingDao.getAllPairingsForTournament(tournament.id)

            val engine: PairingEngine = when (tournament.type) {
                TournamentType.SWISS -> SwissPairingEngine()
                TournamentType.ROUND_ROBIN -> RoundRobinEngine()
            }

            val pairings = engine.generatePairings(
                tournament.id, nextRound, activeStandings, previousPairings
            )

            pairingDao.insertAll(pairings)
            tournamentDao.update(
                tournament.copy(
                    currentRound = nextRound,
                    status = TournamentStatus.IN_PROGRESS
                )
            )

            for (p in pairings) {
                if (p.result == PairingResult.BYE) {
                    recalculateStandings(tournament.id)
                    break
                }
            }
        }
    }

    fun updatePairingResult(pairingId: Long, result: PairingResult) {
        viewModelScope.launch {
            pairingDao.updateResult(pairingId, result)
            val tournamentId = _selectedTournamentId.value ?: return@launch
            recalculateStandings(tournamentId)
        }
    }

    fun withdrawPlayer(playerId: Long) {
        viewModelScope.launch {
            val tournamentId = _selectedTournamentId.value ?: return@launch
            tournamentDao.updatePlayerStatus(tournamentId, playerId, PlayerTournamentStatus.WITHDRAWN)
        }
    }

    fun reinstatePlayer(playerId: Long) {
        viewModelScope.launch {
            val tournamentId = _selectedTournamentId.value ?: return@launch
            tournamentDao.updatePlayerStatus(tournamentId, playerId, PlayerTournamentStatus.ACTIVE)
        }
    }

    fun updateTournamentSettings(name: String, timeControl: String?, tiebreakConfig: String) {
        viewModelScope.launch {
            val tournament = selectedTournament.value ?: return@launch
            tournamentDao.update(
                tournament.copy(
                    name = name,
                    timeControl = timeControl,
                    tiebreakConfig = tiebreakConfig
                )
            )
            recalculateStandings(tournament.id)
        }
    }

    private suspend fun recalculateStandings(tournamentId: Long) {
        val tournament = tournamentDao.getTournamentByIdOnce(tournamentId) ?: return
        val allPairings = pairingDao.getAllPairingsForTournament(tournamentId)
        val playerIds = tournamentDao.getTournamentPlayerIds(tournamentId)

        val scores = mutableMapOf<Long, Float>()
        for (id in playerIds) scores[id] = 0f

        for (pairing in allPairings) {
            when (pairing.result) {
                PairingResult.WHITE_WINS, PairingResult.FORFEIT_WHITE -> {
                    scores[pairing.whitePlayerId] =
                        (scores[pairing.whitePlayerId] ?: 0f) + 1f
                }
                PairingResult.BLACK_WINS, PairingResult.FORFEIT_BLACK -> {
                    if (pairing.blackPlayerId != null) {
                        scores[pairing.blackPlayerId] =
                            (scores[pairing.blackPlayerId] ?: 0f) + 1f
                    }
                }
                PairingResult.DRAW -> {
                    scores[pairing.whitePlayerId] =
                        (scores[pairing.whitePlayerId] ?: 0f) + 0.5f
                    if (pairing.blackPlayerId != null) {
                        scores[pairing.blackPlayerId] =
                            (scores[pairing.blackPlayerId] ?: 0f) + 0.5f
                    }
                }
                PairingResult.BYE -> {
                    scores[pairing.whitePlayerId] =
                        (scores[pairing.whitePlayerId] ?: 0f) + 1f
                }
                PairingResult.PENDING -> { }
            }
        }

        val tiebreakChain = tournament.getTiebreakChain()
        val calculator = TiebreakCalculator(allPairings, scores, playerIds)

        for (id in playerIds) {
            val tbValues = tiebreakChain.mapIndexed { idx, type ->
                if (idx < 4) calculator.compute(type, id) else 0f
            }
            tournamentDao.updatePlayerScore(
                tournamentId, id,
                scores[id] ?: 0f,
                tbValues.getOrElse(0) { 0f },
                tbValues.getOrElse(1) { 0f },
                tbValues.getOrElse(2) { 0f },
                tbValues.getOrElse(3) { 0f }
            )
        }
    }

    fun publishToWeb() {
        viewModelScope.launch {
            if (proStateManager.proFeaturesLocked.value) {
                _publishState.value = PublishState.Error("PRO_REQUIRED")
                return@launch
            }
            _publishState.value = PublishState.Loading
            val tournament = selectedTournament.value
            if (tournament == null) {
                _publishState.value = PublishState.Error("No tournament selected")
                return@launch
            }
            val currentStandings = tournamentDao.getTournamentStandingsOnce(tournament.id)
            val allPairings = pairingDao.getAllPairingsForTournament(tournament.id)

            when (val result = webPublisher.publish(tournament, currentStandings, allPairings, tournament.shareCode)) {
                is PublishResult.Success -> {
                    if (tournament.shareCode == null) {
                        tournamentDao.update(tournament.copy(shareCode = result.shareCode))
                    }
                    _publishState.value = PublishState.Success(result.url)
                }
                is PublishResult.Error -> {
                    _publishState.value = PublishState.Error(result.message)
                }
            }
        }
    }

    fun clearPublishState() {
        _publishState.value = PublishState.Idle
    }

    fun getPairingsForRound(roundNumber: Int): Flow<List<Pairing>> {
        val id = _selectedTournamentId.value ?: return flowOf(emptyList())
        return pairingDao.getPairingsForRound(id, roundNumber)
    }

    fun getPlayerName(playerId: Long): String {
        return standings.value.find { it.playerId == playerId }?.name ?: "Unknown"
    }

    fun completeTournament() {
        viewModelScope.launch {
            val tournament = selectedTournament.value ?: return@launch
            tournamentDao.update(tournament.copy(status = TournamentStatus.COMPLETED))
        }
    }

    fun getAllPairingsForCrossTable(): Flow<List<Pairing>> {
        val id = _selectedTournamentId.value ?: return flowOf(emptyList())
        return pairingDao.getAllPairingsFlow(id)
    }
}
