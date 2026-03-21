package com.chess.tournament.backup

import android.content.Context
import android.net.Uri
import com.chess.tournament.db.AppDatabase
import com.chess.tournament.db.entity.Pairing
import com.chess.tournament.db.entity.PairingResult
import com.chess.tournament.db.entity.Player
import com.chess.tournament.db.entity.PlayerTournamentStatus
import com.chess.tournament.db.entity.Tournament
import com.chess.tournament.db.entity.TournamentPlayer
import com.chess.tournament.db.entity.TournamentStatus
import com.chess.tournament.db.entity.TournamentType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupResult(
    val success: Boolean,
    val message: String,
    val playerCount: Int = 0,
    val tournamentCount: Int = 0
)

class BackupManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val playerDao = db.playerDao()
    private val tournamentDao = db.tournamentDao()
    private val pairingDao = db.pairingDao()

    suspend fun exportToJson(): Pair<String, String> {
        val root = JSONObject()
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appId", "com.chess.tournament")

        val playersArray = JSONArray()
        val allPlayers = playerDao.getAllPlayersOnce()
        for (p in allPlayers) {
            playersArray.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("fideId", p.fideId)
                put("rating", p.rating)
                put("createdAt", p.createdAt)
            })
        }
        root.put("players", playersArray)

        val tournamentsArray = JSONArray()
        val allTournaments = tournamentDao.getAllTournamentsOnce()
        for (t in allTournaments) {
            val tObj = JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("type", t.type.name)
                put("totalRounds", t.totalRounds)
                put("currentRound", t.currentRound)
                put("status", t.status.name)
                put("createdAt", t.createdAt)
                put("timeControl", t.timeControl ?: "")
                put("tiebreakConfig", t.tiebreakConfig)
                put("shareCode", t.shareCode ?: "")
            }

            val tpArray = JSONArray()
            val tPlayers = tournamentDao.getTournamentPlayersOnce(t.id)
            for (tp in tPlayers) {
                tpArray.put(JSONObject().apply {
                    put("playerId", tp.playerId)
                    put("score", tp.score.toDouble())
                    put("tiebreak", tp.tiebreak.toDouble())
                    put("tiebreak2", tp.tiebreak2.toDouble())
                    put("tiebreak3", tp.tiebreak3.toDouble())
                    put("tiebreak4", tp.tiebreak4.toDouble())
                    put("seedNumber", tp.seedNumber)
                    put("status", tp.status.name)
                })
            }
            tObj.put("players", tpArray)

            val pairingsArray = JSONArray()
            val pairings = pairingDao.getAllPairingsForTournament(t.id)
            for (pair in pairings) {
                pairingsArray.put(JSONObject().apply {
                    put("id", pair.id)
                    put("roundNumber", pair.roundNumber)
                    put("board", pair.board)
                    put("whitePlayerId", pair.whitePlayerId)
                    if (pair.blackPlayerId != null) put("blackPlayerId", pair.blackPlayerId)
                    put("result", pair.result.name)
                })
            }
            tObj.put("pairings", pairingsArray)

            tournamentsArray.put(tObj)
        }
        root.put("tournaments", tournamentsArray)

        val json = root.toString(2)
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "chess_backup_$dateStr.json"
        return Pair(json, filename)
    }

    suspend fun exportToUri(uri: Uri): BackupResult {
        return try {
            val (json, _) = exportToJson()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }

            val root = JSONObject(json)
            val pc = root.getJSONArray("players").length()
            val tc = root.getJSONArray("tournaments").length()
            BackupResult(true, "Backup saved successfully", pc, tc)
        } catch (e: Exception) {
            BackupResult(false, "Export failed: ${e.message}")
        }
    }

    suspend fun importFromUri(uri: Uri): BackupResult {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: return BackupResult(false, "Could not read file")

            importFromJson(json)
        } catch (e: Exception) {
            BackupResult(false, "Import failed: ${e.message}")
        }
    }

    suspend fun importFromJson(json: String): BackupResult {
        val root = JSONObject(json)

        if (!root.has("appId") || root.getString("appId") != "com.chess.tournament") {
            return BackupResult(false, "Invalid backup file — not a Chess Tournament backup")
        }

        val playersArray = root.getJSONArray("players")
        val tournamentsArray = root.getJSONArray("tournaments")

        val oldToNewPlayerId = mutableMapOf<Long, Long>()

        for (i in 0 until playersArray.length()) {
            val pObj = playersArray.getJSONObject(i)
            val oldId = pObj.getLong("id")
            val player = Player(
                name = pObj.getString("name"),
                fideId = pObj.optString("fideId", ""),
                rating = pObj.optInt("rating", 0),
                createdAt = pObj.optLong("createdAt", System.currentTimeMillis())
            )
            val newId = playerDao.insert(player)
            oldToNewPlayerId[oldId] = newId
        }

        var tournamentCount = 0
        for (i in 0 until tournamentsArray.length()) {
            val tObj = tournamentsArray.getJSONObject(i)
            val tournament = Tournament(
                name = tObj.getString("name"),
                type = TournamentType.valueOf(tObj.getString("type")),
                totalRounds = tObj.getInt("totalRounds"),
                currentRound = tObj.optInt("currentRound", 0),
                status = TournamentStatus.valueOf(tObj.optString("status", "CREATED")),
                createdAt = tObj.optLong("createdAt", System.currentTimeMillis()),
                timeControl = tObj.optString("timeControl", "").ifBlank { null },
                tiebreakConfig = tObj.optString("tiebreakConfig", "BUCHHOLZ"),
                shareCode = tObj.optString("shareCode", "").ifBlank { null }
            )
            val newTournamentId = tournamentDao.insert(tournament)

            val tpArray = tObj.getJSONArray("players")
            val tournamentPlayers = mutableListOf<TournamentPlayer>()
            for (j in 0 until tpArray.length()) {
                val tpObj = tpArray.getJSONObject(j)
                val oldPlayerId = tpObj.getLong("playerId")
                val newPlayerId = oldToNewPlayerId[oldPlayerId] ?: continue
                val statusStr = tpObj.optString("status", "ACTIVE")
                val status = try {
                    PlayerTournamentStatus.valueOf(statusStr)
                } catch (_: Exception) {
                    PlayerTournamentStatus.ACTIVE
                }
                tournamentPlayers.add(
                    TournamentPlayer(
                        tournamentId = newTournamentId,
                        playerId = newPlayerId,
                        score = tpObj.optDouble("score", 0.0).toFloat(),
                        tiebreak = tpObj.optDouble("tiebreak", 0.0).toFloat(),
                        tiebreak2 = tpObj.optDouble("tiebreak2", 0.0).toFloat(),
                        tiebreak3 = tpObj.optDouble("tiebreak3", 0.0).toFloat(),
                        tiebreak4 = tpObj.optDouble("tiebreak4", 0.0).toFloat(),
                        seedNumber = tpObj.optInt("seedNumber", 0),
                        status = status
                    )
                )
            }
            if (tournamentPlayers.isNotEmpty()) {
                tournamentDao.insertTournamentPlayers(tournamentPlayers)
            }

            val pairingsArray = tObj.getJSONArray("pairings")
            val pairings = mutableListOf<Pairing>()
            for (j in 0 until pairingsArray.length()) {
                val pObj = pairingsArray.getJSONObject(j)
                val oldWhite = pObj.getLong("whitePlayerId")
                val oldBlack = if (pObj.has("blackPlayerId")) pObj.getLong("blackPlayerId") else null
                val resultStr = pObj.optString("result", "PENDING")
                val result = try {
                    PairingResult.valueOf(resultStr)
                } catch (_: Exception) {
                    PairingResult.PENDING
                }
                pairings.add(
                    Pairing(
                        tournamentId = newTournamentId,
                        roundNumber = pObj.getInt("roundNumber"),
                        board = pObj.getInt("board"),
                        whitePlayerId = oldToNewPlayerId[oldWhite] ?: oldWhite,
                        blackPlayerId = oldBlack?.let { oldToNewPlayerId[it] ?: it },
                        result = result
                    )
                )
            }
            if (pairings.isNotEmpty()) {
                pairingDao.insertAll(pairings)
            }
            tournamentCount++
        }

        return BackupResult(
            true,
            "Restored ${oldToNewPlayerId.size} players and $tournamentCount tournaments",
            oldToNewPlayerId.size,
            tournamentCount
        )
    }
}
