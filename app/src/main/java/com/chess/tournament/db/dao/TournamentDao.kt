package com.chess.tournament.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.chess.tournament.db.entity.PlayerStanding
import com.chess.tournament.db.entity.PlayerTournamentStatus
import com.chess.tournament.db.entity.Tournament
import com.chess.tournament.db.entity.TournamentPlayer
import kotlinx.coroutines.flow.Flow

@Dao
interface TournamentDao {

    @Query("SELECT * FROM tournaments ORDER BY createdAt DESC")
    fun getAllTournaments(): Flow<List<Tournament>>

    @Query("SELECT * FROM tournaments WHERE id = :id")
    fun getTournamentById(id: Long): Flow<Tournament?>

    @Query("SELECT * FROM tournaments WHERE id = :id")
    suspend fun getTournamentByIdOnce(id: Long): Tournament?

    @Insert
    suspend fun insert(tournament: Tournament): Long

    @Update
    suspend fun update(tournament: Tournament)

    @Delete
    suspend fun delete(tournament: Tournament)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTournamentPlayers(players: List<TournamentPlayer>)

    @Query(
        """
        SELECT tp.tournamentId, tp.playerId, tp.score, tp.tiebreak, tp.seedNumber,
               p.name, p.fideId, p.rating, tp.status, tp.tiebreak2, tp.tiebreak3, tp.tiebreak4
        FROM tournament_players tp
        INNER JOIN players p ON tp.playerId = p.id
        WHERE tp.tournamentId = :tournamentId
        ORDER BY tp.score DESC, tp.tiebreak DESC, tp.tiebreak2 DESC, tp.tiebreak3 DESC, p.rating DESC
        """
    )
    fun getTournamentStandings(tournamentId: Long): Flow<List<PlayerStanding>>

    @Query(
        """
        SELECT tp.tournamentId, tp.playerId, tp.score, tp.tiebreak, tp.seedNumber,
               p.name, p.fideId, p.rating, tp.status, tp.tiebreak2, tp.tiebreak3, tp.tiebreak4
        FROM tournament_players tp
        INNER JOIN players p ON tp.playerId = p.id
        WHERE tp.tournamentId = :tournamentId
        ORDER BY tp.score DESC, tp.tiebreak DESC, tp.tiebreak2 DESC, tp.tiebreak3 DESC, p.rating DESC
        """
    )
    suspend fun getTournamentStandingsOnce(tournamentId: Long): List<PlayerStanding>

    @Query("SELECT tp.playerId FROM tournament_players tp WHERE tp.tournamentId = :tournamentId")
    suspend fun getTournamentPlayerIds(tournamentId: Long): List<Long>

    @Query(
        """
        UPDATE tournament_players 
        SET score = :score, tiebreak = :tb1, tiebreak2 = :tb2, tiebreak3 = :tb3, tiebreak4 = :tb4
        WHERE tournamentId = :tournamentId AND playerId = :playerId
        """
    )
    suspend fun updatePlayerScore(
        tournamentId: Long, playerId: Long,
        score: Float, tb1: Float, tb2: Float, tb3: Float, tb4: Float
    )

    @Query(
        """
        UPDATE tournament_players SET status = :status
        WHERE tournamentId = :tournamentId AND playerId = :playerId
        """
    )
    suspend fun updatePlayerStatus(
        tournamentId: Long, playerId: Long, status: PlayerTournamentStatus
    )

    @Query("SELECT * FROM tournament_players WHERE tournamentId = :tournamentId")
    suspend fun getTournamentPlayersOnce(tournamentId: Long): List<TournamentPlayer>

    @Query("SELECT * FROM tournaments ORDER BY createdAt DESC")
    suspend fun getAllTournamentsOnce(): List<Tournament>

    @Query(
        """
        SELECT tp.playerId FROM tournament_players tp
        WHERE tp.tournamentId = :tournamentId AND tp.status = 'ACTIVE'
        """
    )
    suspend fun getActiveTournamentPlayerIds(tournamentId: Long): List<Long>
}
