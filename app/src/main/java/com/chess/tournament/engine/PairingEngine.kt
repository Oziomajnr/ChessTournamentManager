package com.chess.tournament.engine

import com.chess.tournament.db.entity.Pairing
import com.chess.tournament.db.entity.PlayerStanding

interface PairingEngine {
    fun generatePairings(
        tournamentId: Long,
        roundNumber: Int,
        players: List<PlayerStanding>,
        previousPairings: List<Pairing>
    ): List<Pairing>
}
