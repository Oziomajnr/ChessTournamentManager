package com.chunkymonkey.chess.tournament.engine

import com.chunkymonkey.chess.tournament.db.entity.Pairing
import com.chunkymonkey.chess.tournament.db.entity.PlayerStanding

interface PairingEngine {
    fun generatePairings(
        tournamentId: Long,
        roundNumber: Int,
        players: List<PlayerStanding>,
        previousPairings: List<Pairing>
    ): List<Pairing>
}
