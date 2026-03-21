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
class SwissPairingEngine : PairingEngine {

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
        matchupSet: Set<Set<Long>>,
        byeReceivers: Set<Long>,
        whiteCount: Map<Long, Int>,
        blackCount: Map<Long, Int>,
        colorHistory: Map<Long, MutableList<Char>>
    ): Pair<PlayerStanding?, List<Pair<PlayerStanding, PlayerStanding>>> {
        if (sorted.size % 2 == 0) {
            return Pair(null, findMatching(sorted, matchupSet, whiteCount, blackCount, colorHistory))
        }

        // R4: eligible (no previous bye) first, then by lowest score, then lowest rating
        val byeCandidates = sorted.sortedWith(
            compareBy<PlayerStanding> { if (it.playerId in byeReceivers) 1 else 0 }
                .thenBy { it.score }
                .thenBy { it.rating }
        )

        for (candidate in byeCandidates) {
            val pool = sorted.filter { it.playerId != candidate.playerId }
            val matching = findMatching(pool, matchupSet, whiteCount, blackCount, colorHistory)
            if (matching.size == pool.size / 2) {
                return Pair(candidate, matching)
            }
        }

        // Fallback: take the first bye candidate and best-effort matching
        val fallbackBye = byeCandidates.first()
        val pool = sorted.filter { it.playerId != fallbackBye.playerId }
        return Pair(fallbackBye, findMatching(pool, matchupSet, whiteCount, blackCount, colorHistory))
    }

    /**
     * Finds a complete matching via backtracking.
     * Tries opponents in score-proximity order (R5).
     * Rejects pairs that have no valid colour assignment (R6/R7 check).
     */
    private fun findMatching(
        pool: List<PlayerStanding>,
        matchupSet: Set<Set<Long>>,
        whiteCount: Map<Long, Int>,
        blackCount: Map<Long, Int>,
        colorHistory: Map<Long, MutableList<Char>>
    ): List<Pair<PlayerStanding, PlayerStanding>> {
        val result = mutableListOf<Pair<PlayerStanding, PlayerStanding>>()
        val available = BooleanArray(pool.size) { true }

        fun solve(startIdx: Int): Boolean {
            var firstIdx = -1
            for (i in startIdx until pool.size) {
                if (available[i]) { firstIdx = i; break }
            }
            if (firstIdx == -1) return true

            val first = pool[firstIdx]
            available[firstIdx] = false

            val candidateIndices = (firstIdx + 1 until pool.size)
                .filter { available[it] }
                .sortedWith(
                    compareBy<Int> { abs(pool[it].score - first.score) }
                        .thenByDescending { pool[it].rating }
                )

            for (ci in candidateIndices) {
                val candidate = pool[ci]
                if (setOf(first.playerId, candidate.playerId) in matchupSet) continue
                if (!pairHasValidColors(first, candidate, whiteCount, blackCount, colorHistory)) continue

                available[ci] = false
                result.add(Pair(first, candidate))

                if (solve(firstIdx + 1)) return true

                result.removeAt(result.size - 1)
                available[ci] = true
            }

            available[firstIdx] = true
            return false
        }

        // Try with color constraint
        if (solve(0)) return result

        // Level 2: relax color constraint (R6/R7 allow exceptions per FIDE)
        result.clear()
        for (i in available.indices) available[i] = true

        fun solveRelaxed(startIdx: Int, allowRematch: Boolean): Boolean {
            var firstIdx = -1
            for (i in startIdx until pool.size) {
                if (available[i]) { firstIdx = i; break }
            }
            if (firstIdx == -1) return true

            val first = pool[firstIdx]
            available[firstIdx] = false

            val candidateIndices = (firstIdx + 1 until pool.size)
                .filter { available[it] }
                .sortedWith(
                    compareBy<Int> {
                        val isRematch = setOf(pool[it].playerId, first.playerId) in matchupSet
                        if (isRematch) 1 else 0
                    }.thenBy { abs(pool[it].score - first.score) }
                        .thenByDescending { pool[it].rating }
                )

            for (ci in candidateIndices) {
                val candidate = pool[ci]
                val isRematch = setOf(first.playerId, candidate.playerId) in matchupSet
                if (isRematch && !allowRematch) continue

                available[ci] = false
                result.add(Pair(first, candidate))

                if (solveRelaxed(firstIdx + 1, allowRematch)) return true

                result.removeAt(result.size - 1)
                available[ci] = true
            }

            available[firstIdx] = true
            return false
        }

        if (solveRelaxed(0, allowRematch = false)) return result

        // Level 3: allow rematches as last resort (exhausted all no-rematch options)
        result.clear()
        for (i in available.indices) available[i] = true
        solveRelaxed(0, allowRematch = true)
        return result
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

    /**
     * Assigns colours to all pairs via backtracking, ensuring R6 and R7 hold
     * for every pair. R8 guides the search order (preferred colour first).
     */
    private fun assignAllColors(
        pairs: List<Pair<PlayerStanding, PlayerStanding>>,
        whiteCount: MutableMap<Long, Int>,
        blackCount: MutableMap<Long, Int>,
        colorHistory: MutableMap<Long, MutableList<Char>>
    ): List<Pair<PlayerStanding, PlayerStanding>> {
        val result = arrayOfNulls<Pair<PlayerStanding, PlayerStanding>>(pairs.size)

        fun solve(index: Int): Boolean {
            if (index == pairs.size) return true
            val (a, b) = pairs[index]

            // Order: try the preferred assignment first (R8)
            val options = orderedColorOptions(a, b, whiteCount, blackCount, colorHistory)

            for ((white, black) in options) {
                val wOk = isColorLegal(white.playerId, 'W', whiteCount, blackCount, colorHistory)
                val bOk = isColorLegal(black.playerId, 'B', whiteCount, blackCount, colorHistory)
                if (!wOk || !bOk) continue

                // Apply
                applyColor(white.playerId, 'W', whiteCount, blackCount, colorHistory)
                applyColor(black.playerId, 'B', whiteCount, blackCount, colorHistory)
                result[index] = Pair(white, black)

                if (solve(index + 1)) return true

                // Undo
                undoColor(white.playerId, 'W', whiteCount, blackCount, colorHistory)
                undoColor(black.playerId, 'B', whiteCount, blackCount, colorHistory)
            }
            return false
        }

        if (solve(0)) {
            return result.map { it!! }
        }

        // Extremely rare fallback: use greedy assignment (may violate R6/R7)
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

    private fun undoColor(
        playerId: Long, color: Char,
        whiteCount: MutableMap<Long, Int>,
        blackCount: MutableMap<Long, Int>,
        colorHistory: MutableMap<Long, MutableList<Char>>
    ) {
        if (color == 'W') whiteCount[playerId] = (whiteCount[playerId] ?: 1) - 1
        else blackCount[playerId] = (blackCount[playerId] ?: 1) - 1
        colorHistory[playerId]?.removeLastOrNull()
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

    private fun buildMatchupSet(pairings: List<Pairing>): Set<Set<Long>> =
        pairings.mapNotNull { p -> p.blackPlayerId?.let { setOf(p.whitePlayerId, it) } }.toSet()

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
