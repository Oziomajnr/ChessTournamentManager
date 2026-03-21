package com.chunkymonkey.chess.tournament.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.chunkymonkey.chess.tournament.db.entity.Pairing
import com.chunkymonkey.chess.tournament.db.entity.PairingResult
import kotlinx.coroutines.flow.Flow

@Dao
interface PairingDao {

    @Query(
        """
        SELECT * FROM pairings 
        WHERE tournamentId = :tournamentId AND roundNumber = :roundNumber 
        ORDER BY board ASC
        """
    )
    fun getPairingsForRound(tournamentId: Long, roundNumber: Int): Flow<List<Pairing>>

    @Query(
        """
        SELECT * FROM pairings 
        WHERE tournamentId = :tournamentId 
        ORDER BY roundNumber ASC, board ASC
        """
    )
    suspend fun getAllPairingsForTournament(tournamentId: Long): List<Pairing>

    @Query(
        """
        SELECT * FROM pairings 
        WHERE tournamentId = :tournamentId 
        ORDER BY roundNumber ASC, board ASC
        """
    )
    fun getAllPairingsFlow(tournamentId: Long): Flow<List<Pairing>>

    @Insert
    suspend fun insertAll(pairings: List<Pairing>)

    @Update
    suspend fun update(pairing: Pairing)

    @Query("UPDATE pairings SET result = :result WHERE id = :id")
    suspend fun updateResult(id: Long, result: PairingResult)

    @Query(
        """
        SELECT DISTINCT roundNumber FROM pairings 
        WHERE tournamentId = :tournamentId 
        ORDER BY roundNumber ASC
        """
    )
    fun getRoundNumbers(tournamentId: Long): Flow<List<Int>>

    @Query(
        """
        SELECT COUNT(*) FROM pairings 
        WHERE tournamentId = :tournamentId AND roundNumber = :roundNumber AND result = 'PENDING'
        """
    )
    suspend fun getPendingCount(tournamentId: Long, roundNumber: Int): Int
}
