package com.chunkymonkey.chess.tournament.db.entity

data class PlayerStanding(
    val tournamentId: Long,
    val playerId: Long,
    val score: Float,
    val tiebreak: Float,
    val seedNumber: Int,
    val name: String,
    val fideId: String,
    val rating: Int,
    val status: PlayerTournamentStatus = PlayerTournamentStatus.ACTIVE,
    val tiebreak2: Float = 0f,
    val tiebreak3: Float = 0f,
    val tiebreak4: Float = 0f
)
