package com.chess.tournament.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.chess.tournament.db.entity.Player
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {

    @Query("SELECT * FROM players ORDER BY rating DESC")
    fun getAllPlayers(): Flow<List<Player>>

    @Query("SELECT * FROM players WHERE id = :id")
    suspend fun getPlayerById(id: Long): Player?

    @Insert
    suspend fun insert(player: Player): Long

    @Update
    suspend fun update(player: Player)

    @Delete
    suspend fun delete(player: Player)

    @Query("SELECT * FROM players WHERE id IN (:ids) ORDER BY rating DESC")
    suspend fun getPlayersByIds(ids: List<Long>): List<Player>

    @Query("SELECT * FROM players ORDER BY rating DESC")
    suspend fun getAllPlayersOnce(): List<Player>
}
