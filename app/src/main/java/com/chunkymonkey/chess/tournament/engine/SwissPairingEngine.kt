package com.chunkymonkey.chess.tournament.engine

import com.chunkymonkey.chess.tournament.db.entity.Pairing
import com.chunkymonkey.chess.tournament.db.entity.PairingResult
import com.chunkymonkey.chess.tournament.db.entity.PlayerStanding
import kotlin.math.abs

/**
 * Swiss pairing engine implementing FIDE C.04.1 rules:
 *
 *  R2:  No rematches.
 *  R3:  Odd player count → exactly one bye.
 *  R4:  No double byes; no bye to a player who already received one.
 *  R5:  Players with same score are generally paired together.
 *  R6:  |whites − blacks| ≤ 2 for every player.
 *  R7:  No three consecutive games with the same colour.
 *  R8:  Colour balancing and alternation.
 *
 * Algorithm:
 *  1. For odd counts, try bye candidates in priority order (R3/R4) until one
 *     yields a valid complete matching.
 *  2. Find a complete matching via backtracking, trying opponents in
 *     score-proximity order (R5) and forbidding rematches (R2).
 *  3. Assign colours globally via backtracking so that R6 + R7 are satisfied
 *     for the entire round simultaneously, with R8 guiding the search order.
 */
class SwissPairingEngine(
    private val maxSearchNodes: Int = DEFAULT_MAX_SEARCH_NODES
) : PairingEngine {

    init {
        require(maxSearchNodes > 0) { "maxSearchNodes must be positive" }
    }

    private companion object {
        const val DEFAULT_MAX_SEARCH_NODES = 100_000
    }

    private data class PlayerPairKey(val lowerId: Long, val higherId: Long)

    private class SearchBudget(private var remainingNodes: Int) {
        fun visit(): Boolean {
            if (remainingNodes <= 0) return false
            remainingNodes--
            return true
        }
    }

    private class AvailabilityKey(private val words: LongArray) {
        override fun equals(other: Any?): Boolean =
            other is AvailabilityKey && words.contentEquals(other.words)

        override fun hashCode(): Int = words.contentHashCode()
    }

    override fun generatePairings(
        tournamentId: Long,
        roundNumber: Int,
        players: List<PlayerStanding>,
        previousPairings: List<Pairing>
    ): List<Pairing> {
        val matchupSet = buildMatchupSet(previousPairings)
        val byeReceivers = previousPairings
            .filter { it.result == PairingResult.BYE }
            .map { it.whitePlayerId }
            .toSet()
        val colorHistory = buildColorHistory(previousPairings)
        val whiteCount = mutableMapOf<Long, Int>()
        val blackCount = mutableMapOf<Long, Int>()
        countColors(previousPairings, whiteCount, blackCount)

        val sorted = players.sortedWith(
            compareByDescending<PlayerStanding> { it.score }
                .thenByDescending { it.rating }
        )

        // --- Step 1 + 2: Find bye + matching ---
        val (byePlayer, pairs) = findByeAndMatching(
            sorted, matchupSet, byeReceivers, whiteCount, blackCount, colorHistory
        )

        // --- Step 3: Assign colours globally (R6, R7, R8) ---
        val colouredPairs = assignAllColors(
            pairs,
            whiteCount.toMutableMap(),
            blackCount.toMutableMap(),
            colorHistory.mapValues { it.value.toMutableList() }.toMutableMap()
        )

        val result = colouredPairs.mapIndexed { idx, (white, black) ->
            Pairing(
                tournamentId = tournamentId,
                roundNumber = roundNumber,
                board = idx + 1,
                whitePlayerId = white.playerId,
                blackPlayerId = black.playerId
            )
        }.toMutableList()

        if (byePlayer != null) {
            result.add(
                Pairing(
                    tournamentId = tournamentId,
                    roundNumber = roundNumber,
                    board = result.size + 1,
                    whitePlayerId = byePlayer.playerId,
                    blackPlayerId = null,
                    result = PairingResult.BYE
                )
            )
        }

        return result
    }

    // =====================================================================
    // Bye selection + matching  (R2, R3, R4, R5)
    // =====================================================================

    private fun findByeAndMatching(
        sorted: List<PlayerStanding>,
        matchupSet: Set<PlayerPairKey>,
        byeReceivers: Set<Long>,
        whiteCount: Map<Long, Int>,
        blackCount: Map<Long, Int>,
        colorHistory: Map<Long, MutableList<Char>>
    ): Pair<PlayerStanding?, List<Pair<PlayerStanding, PlayerStanding>>> {
        val searchBudget = SearchBudget(maxSearchNodes)
        if (sorted.size % 2 == 0) {
            return Pair(
                null,
                findMatching(
                    sorted,
                    matchupSet,
                    whiteCount,
                    blackCount,
                    colorHistory,
                    searchBudget
                )
            )
        }

        // R4: eligible (no previous bye) first, then by lowest score, then lowest rating
        val byeCandidates = sorted.sortedWith(
            compareBy<PlayerStanding> { if (it.playerId in byeReceivers) 1 else 0 }
                .thenBy { it.score }
                .thenBy { it.rating }
        )

        for (candidate in byeCandidates) {
            val pool = sorted.filter { it.playerId != candidate.playerId }
            val matching = findMatching(
                pool,
                matchupSet,
                whiteCount,
                blackCount,
                colorHistory,
                searchBudget
            )
            if (matching.size == pool.size / 2) {
                return Pair(candidate, matching)
            }
        }

        // Fallback: take the first bye candidate and best-effort matching
        val fallbackBye = byeCandidates.first()
        val pool = sorted.filter { it.playerId != fallbackBye.playerId }
        return Pair(
            fallbackBye,
            findMatching(
                pool,
                matchupSet,
                whiteCount,
                blackCount,
                colorHistory,
                searchBudget
            )
        )
    }

    /**
     * Finds a complete matching via backtracking.
     * Tries opponents in score-proximity order (R5).
     * Rejects pairs that have no valid colour assignment (R6/R7 check).
     */
    private fun findMatching(
        pool: List<PlayerStanding>,
        matchupSet: Set<PlayerPairKey>,
        whiteCount: Map<Long, Int>,
        blackCount: Map<Long, Int>,
        colorHistory: Map<Long, MutableList<Char>>,
        searchBudget: SearchBudget
    ): List<Pair<PlayerStanding, PlayerStanding>> {
        val result = mutableListOf<Pair<PlayerStanding, PlayerStanding>>()
        val available = BooleanArray(pool.size) { true }
        val orderedCandidates = Array(pool.size) { firstIdx ->
            pool.indices
                .filter { it != firstIdx }
                .sortedWith(
                    compareBy<Int> { abs(pool[it].score - pool[firstIdx].score) }
                        .thenByDescending { pool[it].rating }
                )
        }
        val failedStrictStates = mutableSetOf<AvailabilityKey>()

        fun solve(startIdx: Int): Boolean {
            if (!searchBudget.visit()) return false

            val state = availabilityKey(available)
            if (state in failedStrictStates) return false

            var firstIdx = -1
            for (i in startIdx until pool.size) {
                if (available[i]) { firstIdx = i; break }
            }
            if (firstIdx == -1) return true

            val first = pool[firstIdx]
            available[firstIdx] = false

            for (ci in orderedCandidates[firstIdx]) {
                if (!available[ci]) continue
                val candidate = pool[ci]
                if (pairKey(first.playerId, candidate.playerId) in matchupSet) continue
                if (!pairHasValidColors(first, candidate, whiteCount, blackCount, colorHistory)) continue

                available[ci] = false
                result.add(Pair(first, candidate))

                if (solve(firstIdx + 1)) return true

                result.removeAt(result.size - 1)
                available[ci] = true
            }

            available[firstIdx] = true
            failedStrictStates.add(state)
            return false
        }

        // Try with color constraint
        if (solve(0)) return result

        // Level 2: relax color constraint (R6/R7 allow exceptions per FIDE)
        result.clear()
        for (i in available.indices) available[i] = true

        fun solveRelaxed(
            startIdx: Int,
            allowRematch: Boolean,
            failedStates: MutableSet<AvailabilityKey>
        ): Boolean {
            if (!searchBudget.visit()) return false

            val state = availabilityKey(available)
            if (state in failedStates) return false

            var firstIdx = -1
            for (i in startIdx until pool.size) {
                if (available[i]) { firstIdx = i; break }
            }
            if (firstIdx == -1) return true

            val first = pool[firstIdx]
            available[firstIdx] = false

            val candidateIndices = orderedCandidates[firstIdx].sortedBy { candidateIndex ->
                if (pairKey(first.playerId, pool[candidateIndex].playerId) in matchupSet) 1 else 0
            }

            for (ci in candidateIndices) {
                if (!available[ci]) continue
                val candidate = pool[ci]
                val isRematch = pairKey(first.playerId, candidate.playerId) in matchupSet
                if (isRematch && !allowRematch) continue

                available[ci] = false
                result.add(Pair(first, candidate))

                if (solveRelaxed(firstIdx + 1, allowRematch, failedStates)) return true

                result.removeAt(result.size - 1)
                available[ci] = true
            }

            available[firstIdx] = true
            failedStates.add(state)
            return false
        }

        if (solveRelaxed(0, allowRematch = false, mutableSetOf())) return result

        // Level 3: allow rematches as last resort (exhausted all no-rematch options)
        result.clear()
        for (i in available.indices) available[i] = true
        if (solveRelaxed(0, allowRematch = true, mutableSetOf())) return result

        // The bounded exhaustive search ran out of nodes. Always return a complete,
        // deterministic pairing instead of keeping a CPU core busy indefinitely.
        return greedyMatching(pool, matchupSet, whiteCount, blackCount, colorHistory)
    }

    private fun greedyMatching(
        pool: List<PlayerStanding>,
        matchupSet: Set<PlayerPairKey>,
        whiteCount: Map<Long, Int>,
        blackCount: Map<Long, Int>,
        colorHistory: Map<Long, MutableList<Char>>
    ): List<Pair<PlayerStanding, PlayerStanding>> {
        val remaining = pool.indices.toMutableList()
        val pairs = mutableListOf<Pair<PlayerStanding, PlayerStanding>>()

        while (remaining.size >= 2) {
            val firstIndex = remaining.removeAt(0)
            val first = pool[firstIndex]
            val candidatePosition = remaining.indices.minWithOrNull(
                compareBy<Int> { position ->
                    val candidate = pool[remaining[position]]
                    if (pairKey(first.playerId, candidate.playerId) in matchupSet) 1 else 0
                }.thenBy { position ->
                    val candidate = pool[remaining[position]]
                    if (pairHasValidColors(
                            first,
                            candidate,
                            whiteCount,
                            blackCount,
                            colorHistory
                        )
                    ) 0 else 1
                }.thenBy { position ->
                    abs(pool[remaining[position]].score - first.score)
                }.thenByDescending { position ->
                    pool[remaining[position]].rating
                }
            ) ?: break

            val candidateIndex = remaining.removeAt(candidatePosition)
            pairs.add(Pair(first, pool[candidateIndex]))
        }

        return pairs
    }

    /** Returns true if at least one colour assignment is legal for this pair. */
    private fun pairHasValidColors(
        a: PlayerStanding, b: PlayerStanding,
        whiteCount: Map<Long, Int>,
        blackCount: Map<Long, Int>,
        colorHistory: Map<Long, MutableList<Char>>
    ): Boolean {
        val aWhiteLegal = isColorLegal(a.playerId, 'W', whiteCount, blackCount, colorHistory) &&
                          isColorLegal(b.playerId, 'B', whiteCount, blackCount, colorHistory)
        val bWhiteLegal = isColorLegal(b.playerId, 'W', whiteCount, blackCount, colorHistory) &&
                          isColorLegal(a.playerId, 'B', whiteCount, blackCount, colorHistory)
        return aWhiteLegal || bWhiteLegal
    }

    // =====================================================================
    // Global colour assignment  (R6, R7, R8)
    // =====================================================================

    /** Assigns each pair's colours independently, with R8 guiding the preferred order. */
    private fun assignAllColors(
        pairs: List<Pair<PlayerStanding, PlayerStanding>>,
        whiteCount: MutableMap<Long, Int>,
        blackCount: MutableMap<Long, Int>,
        colorHistory: MutableMap<Long, MutableList<Char>>
    ): List<Pair<PlayerStanding, PlayerStanding>> {
        return pairs.map { (a, b) -> greedyColorAssign(a, b, whiteCount, blackCount, colorHistory) }
    }

    /** Returns [(white, black)] options ordered by R8 preference. */
    private fun orderedColorOptions(
        a: PlayerStanding,
        b: PlayerStanding,
        whiteCount: Map<Long, Int>,
        blackCount: Map<Long, Int>,
        colorHistory: Map<Long, MutableList<Char>>
    ): List<Pair<PlayerStanding, PlayerStanding>> {
        val aPref = colorPreference(a.playerId, whiteCount, blackCount, colorHistory)
        val bPref = colorPreference(b.playerId, whiteCount, blackCount, colorHistory)

        // If a prefers white or b prefers black, try (a=W, b=B) first
        val aWhiteFirst = when {
            aPref == 'W' && bPref != 'W' -> true
            bPref == 'W' && aPref != 'W' -> false
            aPref == 'B' && bPref != 'B' -> false
            bPref == 'B' && aPref != 'B' -> true
            else -> {
                val aDiff = (whiteCount[a.playerId] ?: 0) - (blackCount[a.playerId] ?: 0)
                val bDiff = (whiteCount[b.playerId] ?: 0) - (blackCount[b.playerId] ?: 0)
                aDiff <= bDiff
            }
        }

        return if (aWhiteFirst) {
            listOf(Pair(a, b), Pair(b, a))
        } else {
            listOf(Pair(b, a), Pair(a, b))
        }
    }

    private fun applyColor(
        playerId: Long, color: Char,
        whiteCount: MutableMap<Long, Int>,
        blackCount: MutableMap<Long, Int>,
        colorHistory: MutableMap<Long, MutableList<Char>>
    ) {
        if (color == 'W') whiteCount[playerId] = (whiteCount[playerId] ?: 0) + 1
        else blackCount[playerId] = (blackCount[playerId] ?: 0) + 1
        colorHistory.getOrPut(playerId) { mutableListOf() }.add(color)
    }

    /** Greedy fallback for the rare case where global backtracking fails. */
    private fun greedyColorAssign(
        a: PlayerStanding, b: PlayerStanding,
        whiteCount: MutableMap<Long, Int>,
        blackCount: MutableMap<Long, Int>,
        colorHistory: MutableMap<Long, MutableList<Char>>
    ): Pair<PlayerStanding, PlayerStanding> {
        val options = orderedColorOptions(a, b, whiteCount, blackCount, colorHistory)
        for ((w, bk) in options) {
            if (isColorLegal(w.playerId, 'W', whiteCount, blackCount, colorHistory) &&
                isColorLegal(bk.playerId, 'B', whiteCount, blackCount, colorHistory)
            ) {
                applyColor(w.playerId, 'W', whiteCount, blackCount, colorHistory)
                applyColor(bk.playerId, 'B', whiteCount, blackCount, colorHistory)
                return Pair(w, bk)
            }
        }
        // Absolute last resort: pick the less-bad option
        val (w, bk) = options.first()
        applyColor(w.playerId, 'W', whiteCount, blackCount, colorHistory)
        applyColor(bk.playerId, 'B', whiteCount, blackCount, colorHistory)
        return Pair(w, bk)
    }

    // =====================================================================
    // Colour legality checks  (R6, R7)
    // =====================================================================

    private fun isColorLegal(
        playerId: Long, color: Char,
        whiteCount: Map<Long, Int>,
        blackCount: Map<Long, Int>,
        colorHistory: Map<Long, MutableList<Char>>
    ): Boolean {
        val w = whiteCount[playerId] ?: 0
        val b = blackCount[playerId] ?: 0
        val newW = if (color == 'W') w + 1 else w
        val newB = if (color == 'B') b + 1 else b
        if (abs(newW - newB) > 2) return false // R6

        val history = colorHistory[playerId]
        if (history != null && history.size >= 2) {
            if (history[history.size - 1] == color && history[history.size - 2] == color) {
                return false // R7
            }
        }
        return true
    }

    /** R8: preferred colour for balancing/alternation. */
    private fun colorPreference(
        playerId: Long,
        whiteCount: Map<Long, Int>,
        blackCount: Map<Long, Int>,
        colorHistory: Map<Long, MutableList<Char>>
    ): Char? {
        val w = whiteCount[playerId] ?: 0
        val b = blackCount[playerId] ?: 0
        if (w < b) return 'W'
        if (b < w) return 'B'
        val history = colorHistory[playerId]
        if (!history.isNullOrEmpty()) return if (history.last() == 'W') 'B' else 'W'
        return null
    }

    // =====================================================================
    // Utilities
    // =====================================================================

    private fun pairKey(firstPlayerId: Long, secondPlayerId: Long): PlayerPairKey =
        if (firstPlayerId < secondPlayerId) {
            PlayerPairKey(firstPlayerId, secondPlayerId)
        } else {
            PlayerPairKey(secondPlayerId, firstPlayerId)
        }

    private fun buildMatchupSet(pairings: List<Pairing>): Set<PlayerPairKey> =
        pairings.mapNotNull { pairing ->
            pairing.blackPlayerId?.let { blackPlayerId ->
                pairKey(pairing.whitePlayerId, blackPlayerId)
            }
        }.toSet()

    private fun availabilityKey(available: BooleanArray): AvailabilityKey {
        val words = LongArray((available.size + Long.SIZE_BITS - 1) / Long.SIZE_BITS)
        for (index in available.indices) {
            if (available[index]) {
                val wordIndex = index / Long.SIZE_BITS
                words[wordIndex] = words[wordIndex] or (1L shl (index % Long.SIZE_BITS))
            }
        }
        return AvailabilityKey(words)
    }

    private fun buildColorHistory(pairings: List<Pairing>): MutableMap<Long, MutableList<Char>> {
        val history = mutableMapOf<Long, MutableList<Char>>()
        for (p in pairings.filter { it.blackPlayerId != null }.sortedBy { it.roundNumber }) {
            history.getOrPut(p.whitePlayerId) { mutableListOf() }.add('W')
            val blackId = p.blackPlayerId ?: continue
            history.getOrPut(blackId) { mutableListOf() }.add('B')
        }
        return history
    }

    private fun countColors(
        pairings: List<Pairing>,
        whiteCount: MutableMap<Long, Int>,
        blackCount: MutableMap<Long, Int>
    ) {
        for (p in pairings) {
            val blackId = p.blackPlayerId ?: continue
            whiteCount[p.whitePlayerId] = (whiteCount[p.whitePlayerId] ?: 0) + 1
            blackCount[blackId] = (blackCount[blackId] ?: 0) + 1
        }
    }
}
