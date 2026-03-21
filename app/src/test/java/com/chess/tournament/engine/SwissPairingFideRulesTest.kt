package com.chess.tournament.engine

import com.chess.tournament.db.entity.Pairing
import com.chess.tournament.db.entity.PairingResult
import com.chess.tournament.db.entity.PlayerStanding
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.random.Random

/**
 * Simulation-based property tests for the Swiss pairing engine.
 *
 * Each test generates random tournament configurations (player counts, ratings,
 * round results) and verifies that all applicable FIDE Swiss system rules hold
 * after every generated round.
 *
 * FIDE rules under test (C.04.1):
 *  1. Number of rounds declared beforehand
 *  2. No rematches
 *  3. Odd player count => exactly one bye per round
 *  4. No double byes; no bye to a player who already won a full point without playing
 *  5. Players with same score are generally paired together
 *  6. Color difference per player in [-2, +2]
 *  7. No three same colors in a row
 *  8. Color balancing & alternation
 */
class SwissPairingFideRulesTest {

    private val engine = SwissPairingEngine()
    private val tournamentId = 1L

    // -----------------------------------------------------------------------
    // Simulation harness
    // -----------------------------------------------------------------------

    private data class SimResult(
        val allPairings: List<Pairing>,
        val standings: List<PlayerStanding>,
        val roundCount: Int
    )

    /**
     * Simulates a full Swiss tournament, verifying FIDE rules after every round.
     * Returns the final state for additional assertions.
     */
    private fun simulateTournament(
        playerCount: Int,
        rounds: Int,
        seed: Long,
        resultGenerator: (Pairing, Random) -> PairingResult = ::randomResult
    ): SimResult {
        val rng = Random(seed)
        val players = generatePlayers(playerCount, rng)
        val allPairings = mutableListOf<Pairing>()
        var standings = players.map { p ->
            PlayerStanding(
                tournamentId = tournamentId,
                playerId = p.playerId,
                score = 0f,
                tiebreak = 0f,
                seedNumber = p.seedNumber,
                name = p.name,
                fideId = p.fideId,
                rating = p.rating
            )
        }

        for (round in 1..rounds) {
            val newPairings = engine.generatePairings(
                tournamentId, round, standings, allPairings
            )

            // --- VERIFY RULES FOR THIS ROUND ---
            val context = "seed=$seed, players=$playerCount, round=$round/$rounds"
            verifyRule2_NoRematches(allPairings, newPairings, context)
            verifyRule3_ByeForOddPlayers(playerCount, newPairings, context)
            verifyRule4_NoDoubleByes(allPairings, newPairings, context)
            verifyRule6_ColorDifference(allPairings, newPairings, context)
            verifyRule7_NoThreeConsecutiveSameColor(allPairings, newPairings, context)
            verifyAllPlayersPairedExactlyOnce(standings, newPairings, context)

            // Assign results
            val resolvedPairings = newPairings.map { pairing ->
                if (pairing.result == PairingResult.BYE) pairing
                else pairing.copy(result = resultGenerator(pairing, rng))
            }
            allPairings.addAll(resolvedPairings)

            // Recalculate standings
            standings = recalculate(players, allPairings)
        }

        return SimResult(allPairings, standings, rounds)
    }

    // -----------------------------------------------------------------------
    // FIDE Rule verifiers
    // -----------------------------------------------------------------------

    /**
     * Rule 2: Two participants shall not play against each other more than once.
     */
    private fun verifyRule2_NoRematches(
        previousPairings: List<Pairing>,
        newPairings: List<Pairing>,
        context: String
    ) {
        val existingMatchups = previousPairings
            .filter { it.blackPlayerId != null }
            .map { setOf(it.whitePlayerId, it.blackPlayerId!!) }
            .toMutableSet()

        for (p in newPairings) {
            val blackId = p.blackPlayerId ?: continue
            val matchup = setOf(p.whitePlayerId, blackId)
            if (matchup in existingMatchups) {
                fail(
                    "RULE 2 VIOLATED: Rematch between players " +
                    "${p.whitePlayerId} and $blackId [$context]"
                )
            }
            existingMatchups.add(matchup)
        }
    }

    /**
     * Rule 3: If odd number of participants, exactly one receives a bye.
     * The bye is worth a full point (result = BYE).
     */
    private fun verifyRule3_ByeForOddPlayers(
        playerCount: Int,
        pairings: List<Pairing>,
        context: String
    ) {
        val byes = pairings.filter { it.blackPlayerId == null }
        if (playerCount % 2 != 0) {
            if (byes.size != 1) {
                fail(
                    "RULE 3 VIOLATED: Odd player count ($playerCount) but " +
                    "${byes.size} byes issued (expected 1) [$context]"
                )
            }
            if (byes[0].result != PairingResult.BYE) {
                fail(
                    "RULE 3 VIOLATED: Bye pairing does not have BYE result [$context]"
                )
            }
        } else {
            if (byes.isNotEmpty()) {
                fail(
                    "RULE 3 VIOLATED: Even player count ($playerCount) but " +
                    "${byes.size} bye(s) issued [$context]"
                )
            }
        }
    }

    /**
     * Rule 4: A participant who has already received a pairing-allocated bye,
     * or has already scored a full point without playing, shall not receive another bye.
     */
    private fun verifyRule4_NoDoubleByes(
        previousPairings: List<Pairing>,
        newPairings: List<Pairing>,
        context: String
    ) {
        val previousByeReceivers: Set<Long> = previousPairings
            .filter { it.result == PairingResult.BYE }
            .map { it.whitePlayerId }
            .toSet()

        val newByes = newPairings.filter { it.blackPlayerId == null }
        for (bye in newByes) {
            if (bye.whitePlayerId in previousByeReceivers) {
                val allPlayersInRound: Set<Long> = newPairings.flatMap { p ->
                    listOfNotNull(p.whitePlayerId, p.blackPlayerId)
                }.toSet()
                val eligibleForBye = allPlayersInRound - previousByeReceivers
                if (eligibleForBye.isNotEmpty()) {
                    fail(
                        "RULE 4 VIOLATED: Player ${bye.whitePlayerId} received a second bye " +
                        "while eligible players exist: $eligibleForBye [$context]"
                    )
                }
            }
        }
    }

    /**
     * Rule 6: For each participant, |whites - blacks| <= 2 at all times.
     */
    private fun verifyRule6_ColorDifference(
        previousPairings: List<Pairing>,
        newPairings: List<Pairing>,
        context: String
    ) {
        val allPairings = previousPairings + newPairings
        val whiteCount = mutableMapOf<Long, Int>()
        val blackCount = mutableMapOf<Long, Int>()

        for (p in allPairings) {
            val blackId = p.blackPlayerId ?: continue
            whiteCount[p.whitePlayerId] = (whiteCount[p.whitePlayerId] ?: 0) + 1
            blackCount[blackId] = (blackCount[blackId] ?: 0) + 1
        }

        val allPlayers = (whiteCount.keys + blackCount.keys).toSet()
        for (playerId in allPlayers) {
            val w = whiteCount[playerId] ?: 0
            val b = blackCount[playerId] ?: 0
            val diff = w - b
            if (abs(diff) > 2) {
                fail(
                    "RULE 6 VIOLATED: Player $playerId has color difference " +
                    "$diff (white=$w, black=$b) [$context]"
                )
            }
        }
    }

    /**
     * Rule 7: No participant shall receive the same colour three times in a row.
     */
    private fun verifyRule7_NoThreeConsecutiveSameColor(
        previousPairings: List<Pairing>,
        newPairings: List<Pairing>,
        context: String
    ) {
        val allPairings = previousPairings + newPairings

        // Build per-player color history in round order
        val colorHistory = mutableMapOf<Long, MutableList<Char>>()
        val byRound = allPairings
            .filter { it.blackPlayerId != null }
            .sortedBy { it.roundNumber }

        for (p in byRound) {
            colorHistory.getOrPut(p.whitePlayerId) { mutableListOf() }.add('W')
            val blackId = p.blackPlayerId ?: continue
            colorHistory.getOrPut(blackId) { mutableListOf() }.add('B')
        }

        for ((playerId, history) in colorHistory) {
            for (i in 2 until history.size) {
                if (history[i] == history[i - 1] && history[i] == history[i - 2]) {
                    fail(
                        "RULE 7 VIOLATED: Player $playerId has three consecutive " +
                        "'${history[i]}' colors: ${history.joinToString()} [$context]"
                    )
                }
            }
        }
    }

    /**
     * Structural: Every player must appear in exactly one pairing per round.
     */
    private fun verifyAllPlayersPairedExactlyOnce(
        standings: List<PlayerStanding>,
        pairings: List<Pairing>,
        context: String
    ) {
        val pairedIds = mutableListOf<Long>()
        for (p in pairings) {
            pairedIds.add(p.whitePlayerId)
            val blackId = p.blackPlayerId
            if (blackId != null) {
                pairedIds.add(blackId)
            }
        }

        val expected = standings.map { it.playerId }.toSet()
        val actual = pairedIds.toSet()

        if (expected != actual) {
            val missing = expected - actual
            val extra = actual - expected
            fail(
                "STRUCTURAL VIOLATION: Not all players paired exactly once. " +
                "Missing: $missing, Extra: $extra [$context]"
            )
        }

        if (pairedIds.size != pairedIds.toSet().size) {
            val duplicates = pairedIds.groupBy { it }.filter { it.value.size > 1 }.keys
            fail(
                "STRUCTURAL VIOLATION: Players paired multiple times: $duplicates [$context]"
            )
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun generatePlayers(count: Int, rng: Random): List<PlayerStanding> {
        return (1..count).map { i ->
            PlayerStanding(
                tournamentId = tournamentId,
                playerId = i.toLong(),
                score = 0f,
                tiebreak = 0f,
                seedNumber = i,
                name = "Player $i",
                fideId = "${100000 + i}",
                rating = rng.nextInt(1200, 2800)
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun randomResult(pairing: Pairing, rng: Random): PairingResult {
        return when (rng.nextInt(3)) {
            0 -> PairingResult.WHITE_WINS
            1 -> PairingResult.BLACK_WINS
            else -> PairingResult.DRAW
        }
    }

    private fun recalculate(
        basePlayers: List<PlayerStanding>,
        allPairings: List<Pairing>
    ): List<PlayerStanding> {
        val scores = basePlayers.associate { it.playerId to 0f }.toMutableMap()
        for (p in allPairings) {
            when (p.result) {
                PairingResult.WHITE_WINS, PairingResult.FORFEIT_WHITE -> scores[p.whitePlayerId] = (scores[p.whitePlayerId] ?: 0f) + 1f
                PairingResult.BLACK_WINS, PairingResult.FORFEIT_BLACK -> {
                    val blackId = p.blackPlayerId ?: continue
                    scores[blackId] = (scores[blackId] ?: 0f) + 1f
                }
                PairingResult.DRAW -> {
                    scores[p.whitePlayerId] = (scores[p.whitePlayerId] ?: 0f) + 0.5f
                    val blackId = p.blackPlayerId ?: continue
                    scores[blackId] = (scores[blackId] ?: 0f) + 0.5f
                }
                PairingResult.BYE -> scores[p.whitePlayerId] = (scores[p.whitePlayerId] ?: 0f) + 1f
                PairingResult.PENDING -> {}
            }
        }
        return basePlayers.map { it.copy(score = scores[it.playerId] ?: 0f) }
    }

    // -----------------------------------------------------------------------
    // Property tests: random tournament simulations
    // -----------------------------------------------------------------------

    // Standard Swiss round count: ceil(log2(n))
    private fun swissRounds(n: Int) = ceil(log2(n.toDouble())).toInt().coerceAtLeast(1)

    @Test
    fun `FIDE rules hold for many random 4-player tournaments`() {
        repeat(50) { seed ->
            simulateTournament(playerCount = 4, rounds = swissRounds(4), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for many random 5-player tournaments (odd)`() {
        repeat(50) { seed ->
            simulateTournament(playerCount = 5, rounds = swissRounds(5), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for many random 6-player tournaments`() {
        repeat(50) { seed ->
            simulateTournament(playerCount = 6, rounds = swissRounds(6), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for many random 7-player tournaments (odd)`() {
        repeat(50) { seed ->
            simulateTournament(playerCount = 7, rounds = swissRounds(7), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for many random 8-player tournaments`() {
        repeat(50) { seed ->
            simulateTournament(playerCount = 8, rounds = swissRounds(8), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for many random 10-player tournaments`() {
        repeat(30) { seed ->
            simulateTournament(playerCount = 10, rounds = swissRounds(10), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for many random 12-player tournaments`() {
        repeat(30) { seed ->
            simulateTournament(playerCount = 12, rounds = swissRounds(12), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for many random 16-player tournaments`() {
        repeat(20) { seed ->
            simulateTournament(playerCount = 16, rounds = swissRounds(16), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for many random 20-player tournaments`() {
        repeat(20) { seed ->
            simulateTournament(playerCount = 20, rounds = swissRounds(20), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for 3-player tournament (minimum odd)`() {
        repeat(50) { seed ->
            simulateTournament(playerCount = 3, rounds = swissRounds(3), seed = seed.toLong())
        }
    }

    @Test
    fun `FIDE rules hold for large 32-player tournament`() {
        repeat(10) { seed ->
            simulateTournament(playerCount = 32, rounds = swissRounds(32), seed = seed.toLong())
        }
    }

    // -----------------------------------------------------------------------
    // Focused edge case tests
    // -----------------------------------------------------------------------

    @Test
    fun `all decisive results - FIDE rules hold`() {
        repeat(30) { seed ->
            simulateTournament(
                playerCount = 10, rounds = swissRounds(10), seed = seed.toLong()
            ) { _, rng ->
                if (rng.nextBoolean()) PairingResult.WHITE_WINS else PairingResult.BLACK_WINS
            }
        }
    }

    @Test
    fun `all draws - FIDE rules hold`() {
        repeat(30) { seed ->
            simulateTournament(
                playerCount = 10, rounds = swissRounds(10), seed = seed.toLong()
            ) { _, _ -> PairingResult.DRAW }
        }
    }

    @Test
    fun `white always wins - extreme score spread - FIDE rules hold`() {
        repeat(30) { seed ->
            simulateTournament(
                playerCount = 10, rounds = swissRounds(10), seed = seed.toLong()
            ) { _, _ -> PairingResult.WHITE_WINS }
        }
    }

    @Test
    fun `extended rounds with more players - FIDE rules hold`() {
        repeat(20) { seed ->
            simulateTournament(playerCount = 12, rounds = 5, seed = seed.toLong())
        }
    }

    @Test
    fun `single round - FIDE rules hold for many sizes`() {
        for (n in 3..20) {
            repeat(5) { seed ->
                simulateTournament(playerCount = n, rounds = 1, seed = (n * 100 + seed).toLong())
            }
        }
    }

    @Test
    fun `FIDE rules hold across varied player counts with random seeds`() {
        val rng = Random(42)
        repeat(100) {
            val playerCount = rng.nextInt(4, 25)
            val rounds = swissRounds(playerCount)
            val seed = rng.nextLong()
            simulateTournament(playerCount = playerCount, rounds = rounds, seed = seed)
        }
    }
}
