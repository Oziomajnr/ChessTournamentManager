package com.chess.tournament.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PairingResult {
    PENDING,
    WHITE_WINS,
    BLACK_WINS,
    DRAW,
    BYE,
    FORFEIT_WHITE,
    FORFEIT_BLACK
}

@Entity(
    tableName = "pairings",
    indices = [Index("tournamentId")],
    foreignKeys = [
        ForeignKey(
            entity = Tournament::class,
            parentColumns = ["id"],
            childColumns = ["tournamentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Pairing(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tournamentId: Long,
    val roundNumber: Int,
    val board: Int,
    val whitePlayerId: Long,
    val blackPlayerId: Long?,
    val result: PairingResult = PairingResult.PENDING
)
