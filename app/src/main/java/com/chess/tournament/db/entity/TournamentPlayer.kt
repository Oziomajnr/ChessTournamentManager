package com.chess.tournament.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class PlayerTournamentStatus {
    ACTIVE,
    WITHDRAWN,
    LATE_ENTRY
}

@Entity(
    tableName = "tournament_players",
    primaryKeys = ["tournamentId", "playerId"],
    indices = [Index("playerId")],
    foreignKeys = [
        ForeignKey(
            entity = Tournament::class,
            parentColumns = ["id"],
            childColumns = ["tournamentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Player::class,
            parentColumns = ["id"],
            childColumns = ["playerId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TournamentPlayer(
    val tournamentId: Long,
    val playerId: Long,
    val score: Float = 0f,
    val tiebreak: Float = 0f,
    val seedNumber: Int = 0,
    val status: PlayerTournamentStatus = PlayerTournamentStatus.ACTIVE,
    val tiebreak2: Float = 0f,
    val tiebreak3: Float = 0f,
    val tiebreak4: Float = 0f
)
