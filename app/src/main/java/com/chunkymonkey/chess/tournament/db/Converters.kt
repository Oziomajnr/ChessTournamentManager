package com.chunkymonkey.chess.tournament.db

import androidx.room.TypeConverter
import com.chunkymonkey.chess.tournament.db.entity.PairingResult
import com.chunkymonkey.chess.tournament.db.entity.PlayerTournamentStatus
import com.chunkymonkey.chess.tournament.db.entity.TournamentStatus
import com.chunkymonkey.chess.tournament.db.entity.TournamentType

class Converters {

    @TypeConverter
    fun fromTournamentType(value: TournamentType): String = value.name

    @TypeConverter
    fun toTournamentType(value: String): TournamentType = TournamentType.valueOf(value)

    @TypeConverter
    fun fromTournamentStatus(value: TournamentStatus): String = value.name

    @TypeConverter
    fun toTournamentStatus(value: String): TournamentStatus = TournamentStatus.valueOf(value)

    @TypeConverter
    fun fromPairingResult(value: PairingResult): String = value.name

    @TypeConverter
    fun toPairingResult(value: String): PairingResult = PairingResult.valueOf(value)

    @TypeConverter
    fun fromPlayerTournamentStatus(value: PlayerTournamentStatus): String = value.name

    @TypeConverter
    fun toPlayerTournamentStatus(value: String): PlayerTournamentStatus =
        PlayerTournamentStatus.valueOf(value)
}
