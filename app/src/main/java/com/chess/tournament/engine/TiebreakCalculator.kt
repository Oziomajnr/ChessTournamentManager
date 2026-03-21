package com.chess.tournament.engine

import com.chess.tournament.db.entity.Pairing
import com.chess.tournament.db.entity.PairingResult
import com.chess.tournament.db.entity.TiebreakType

class TiebreakCalculator(
    private val allPairings: List<Pairing>,
    private val scores: Map<Long, Float>,
    private val playerIds: List<Long>
) {
    private val opponents: Map<Long, List<Long>> by lazy { buildOpponentMap() }
    private val resultMap: Map<Pair<Long, Long>, Float> by lazy { buildResultMap() }

    private fun buildOpponentMap(): Map<Long, List<Long>> {
        val map = mutableMapOf<Long, MutableList<Long>>()
        for (id in playerIds) map[id] = mutableListOf()
        for (p in allPairings) {
            if (p.result == PairingResult.PENDING) continue
            if (p.blackPlayerId != null) {
                map.getOrPut(p.whitePlayerId) { mutableListOf() }.add(p.blackPlayerId)
                map.getOrPut(p.blackPlayerId) { mutableListOf() }.add(p.whitePlayerId)
            }
        }
        return map
    }

    private fun buildResultMap(): Map<Pair<Long, Long>, Float> {
        val map = mutableMapOf<Pair<Long, Long>, Float>()
        for (p in allPairings) {
            if (p.result == PairingResult.PENDING || p.blackPlayerId == null) continue
            val (ws, bs) = scoreForPairing(p)
            val key1 = Pair(p.whitePlayerId, p.blackPlayerId)
            val key2 = Pair(p.blackPlayerId, p.whitePlayerId)
            map[key1] = (map[key1] ?: 0f) + ws
            map[key2] = (map[key2] ?: 0f) + bs
        }
        return map
    }

    private fun scoreForPairing(p: Pairing): Pair<Float, Float> = when (p.result) {
        PairingResult.WHITE_WINS, PairingResult.FORFEIT_WHITE -> 1f to 0f
        PairingResult.BLACK_WINS, PairingResult.FORFEIT_BLACK -> 0f to 1f
        PairingResult.DRAW -> 0.5f to 0.5f
        PairingResult.BYE -> 1f to 0f
        PairingResult.PENDING -> 0f to 0f
    }

    private fun isForfeit(p: Pairing): Boolean =
        p.result == PairingResult.FORFEIT_WHITE || p.result == PairingResult.FORFEIT_BLACK

    fun compute(type: TiebreakType, playerId: Long): Float = when (type) {
        TiebreakType.BUCHHOLZ -> buchholz(playerId)
        TiebreakType.BUCHHOLZ_CUT1 -> buchholzCut1(playerId)
        TiebreakType.MEDIAN_BUCHHOLZ -> medianBuchholz(playerId)
        TiebreakType.SONNEBORN_BERGER -> sonnebornBerger(playerId)
        TiebreakType.DIRECT_ENCOUNTER -> directEncounter(playerId)
        TiebreakType.WINS -> wins(playerId)
        TiebreakType.BLACK_WINS -> blackWins(playerId)
        TiebreakType.KOYA -> koya(playerId)
    }

    private fun buchholz(playerId: Long): Float {
        return opponents[playerId]?.sumOf { (scores[it] ?: 0f).toDouble() }?.toFloat() ?: 0f
    }

    private fun buchholzCut1(playerId: Long): Float {
        val oppScores = opponents[playerId]?.map { scores[it] ?: 0f } ?: return 0f
        if (oppScores.size <= 1) return 0f
        return oppScores.sorted().drop(1).sum()
    }

    private fun medianBuchholz(playerId: Long): Float {
        val oppScores = opponents[playerId]?.map { scores[it] ?: 0f }?.sorted() ?: return 0f
        if (oppScores.size <= 2) return oppScores.sum()
        return oppScores.drop(1).dropLast(1).sum()
    }

    private fun sonnebornBerger(playerId: Long): Float {
        var sb = 0f
        for (p in allPairings) {
            if (p.result == PairingResult.PENDING || p.blackPlayerId == null) continue
            if (isForfeit(p)) continue
            when {
                p.whitePlayerId == playerId -> {
                    val myScore = scoreForPairing(p).first
                    sb += myScore * (scores[p.blackPlayerId] ?: 0f)
                }
                p.blackPlayerId == playerId -> {
                    val myScore = scoreForPairing(p).second
                    sb += myScore * (scores[p.whitePlayerId] ?: 0f)
                }
            }
        }
        return sb
    }

    private fun directEncounter(playerId: Long): Float {
        val topScore = scores[playerId] ?: 0f
        val tiedPlayers = playerIds.filter { (scores[it] ?: 0f) == topScore && it != playerId }
        var total = 0f
        for (other in tiedPlayers) {
            total += resultMap[Pair(playerId, other)] ?: 0f
        }
        return total
    }

    private fun wins(playerId: Long): Float {
        var count = 0f
        for (p in allPairings) {
            if (isForfeit(p)) continue
            when {
                p.whitePlayerId == playerId && p.result == PairingResult.WHITE_WINS -> count += 1f
                p.blackPlayerId == playerId && p.result == PairingResult.BLACK_WINS -> count += 1f
                p.whitePlayerId == playerId && p.result == PairingResult.BYE -> count += 1f
            }
        }
        return count
    }

    private fun blackWins(playerId: Long): Float {
        var count = 0f
        for (p in allPairings) {
            if (isForfeit(p)) continue
            if (p.blackPlayerId == playerId && p.result == PairingResult.BLACK_WINS) count += 1f
        }
        return count
    }

    private fun koya(playerId: Long): Float {
        val halfRounds = allPairings.maxOfOrNull { it.roundNumber }?.let { it / 2f } ?: 0f
        var total = 0f
        for (p in allPairings) {
            if (p.result == PairingResult.PENDING || p.blackPlayerId == null) continue
            if (isForfeit(p)) continue
            val oppId = when {
                p.whitePlayerId == playerId -> p.blackPlayerId
                p.blackPlayerId == playerId -> p.whitePlayerId
                else -> null
            } ?: continue
            if ((scores[oppId] ?: 0f) >= halfRounds) {
                total += if (p.whitePlayerId == playerId) scoreForPairing(p).first else scoreForPairing(p).second
            }
        }
        return total
    }
}
