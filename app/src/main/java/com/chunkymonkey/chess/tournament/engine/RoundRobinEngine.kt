package com.chunkymonkey.chess.tournament.engine

import com.chunkymonkey.chess.tournament.db.entity.Pairing
import com.chunkymonkey.chess.tournament.db.entity.PairingResult
import com.chunkymonkey.chess.tournament.db.entity.PlayerStanding

/**
 * Standard round-robin tournament pairing using the circle/polygon method.
 *
 * For n players (with a BYE slot added for odd counts):
 * - Generates n-1 rounds where every player meets every other player exactly once
 * - Player 1 is fixed; remaining players rotate clockwise each round
 * - Colors alternate based on round and board position
 */
class RoundRobinEngine : PairingEngine {

    override fun generatePairings(
        tournamentId: Long,
        roundNumber: Int,
        players: List<PlayerStanding>,
        previousPairings: List<Pairing>
    ): List<Pairing> {
        val playerIds = players.sortedByDescending { it.rating }.map { it.playerId }.toMutableList()

        val hasBye = playerIds.size % 2 != 0
        if (hasBye) {
            playerIds.add(BYE_PLAYER_ID)
        }

        val n = playerIds.size
        val fixed = playerIds[0]
        val rotating = playerIds.subList(1, n).toMutableList()

        repeat(roundNumber - 1) {
            rotating.add(0, rotating.removeAt(rotating.size - 1))
        }

        val round = mutableListOf(fixed) + rotating
        val pairings = mutableListOf<Pairing>()

        for (i in 0 until n / 2) {
            val p1 = round[i]
            val p2 = round[n - 1 - i]

            if (p1 == BYE_PLAYER_ID || p2 == BYE_PLAYER_ID) {
                val realPlayer = if (p1 == BYE_PLAYER_ID) p2 else p1
                pairings.add(
                    Pairing(
                        tournamentId = tournamentId,
                        roundNumber = roundNumber,
                        board = pairings.size + 1,
                        whitePlayerId = realPlayer,
                        blackPlayerId = null,
                        result = PairingResult.BYE
                    )
                )
            } else {
                val (white, black) = if ((i + roundNumber) % 2 == 0) Pair(p1, p2) else Pair(p2, p1)
                pairings.add(
                    Pairing(
                        tournamentId = tournamentId,
                        roundNumber = roundNumber,
                        board = pairings.size + 1,
                        whitePlayerId = white,
                        blackPlayerId = black
                    )
                )
            }
        }

        return pairings
    }

    companion object {
        private const val BYE_PLAYER_ID = -1L

        fun requiredRounds(playerCount: Int): Int {
            return if (playerCount % 2 == 0) playerCount - 1 else playerCount
        }
    }
}
