package com.chunkymonkey.chess.tournament.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TournamentType { SWISS, ROUND_ROBIN }

enum class TournamentStatus { CREATED, IN_PROGRESS, COMPLETED }

enum class TiebreakType {
    BUCHHOLZ,
    BUCHHOLZ_CUT1,
    MEDIAN_BUCHHOLZ,
    SONNEBORN_BERGER,
    DIRECT_ENCOUNTER,
    WINS,
    BLACK_WINS,
    KOYA;

    fun displayName(): String = when (this) {
        BUCHHOLZ -> "Buchholz"
        BUCHHOLZ_CUT1 -> "Buchholz Cut-1"
        MEDIAN_BUCHHOLZ -> "Median Buchholz"
        SONNEBORN_BERGER -> "Sonneborn-Berger"
        DIRECT_ENCOUNTER -> "Direct Encounter"
        WINS -> "Wins"
        BLACK_WINS -> "Black Wins"
        KOYA -> "Koya"
    }
}

@Entity(tableName = "tournaments")
data class Tournament(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: TournamentType,
    val totalRounds: Int,
    val currentRound: Int = 0,
    val status: TournamentStatus = TournamentStatus.CREATED,
    val createdAt: Long = System.currentTimeMillis(),
    val timeControl: String? = null,
    val tiebreakConfig: String = "BUCHHOLZ",
    val shareCode: String? = null
) {
    fun getTiebreakChain(): List<TiebreakType> {
        if (tiebreakConfig.isBlank()) return listOf(TiebreakType.BUCHHOLZ)
        return tiebreakConfig.split(",").mapNotNull { name ->
            try { TiebreakType.valueOf(name.trim()) } catch (_: Exception) { null }
        }.ifEmpty { listOf(TiebreakType.BUCHHOLZ) }
    }
}
