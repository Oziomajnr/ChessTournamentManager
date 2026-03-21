package com.chunkymonkey.chess.tournament.web

import com.chunkymonkey.chess.tournament.db.entity.Pairing
import com.chunkymonkey.chess.tournament.db.entity.PlayerStanding
import com.chunkymonkey.chess.tournament.db.entity.Tournament
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

sealed class PublishResult {
    data class Success(val url: String, val shareCode: String) : PublishResult()
    data class Error(val message: String) : PublishResult()
}

class WebPublisher {

    suspend fun publish(
        tournament: Tournament,
        standings: List<PlayerStanding>,
        allPairings: List<Pairing>,
        existingShareCode: String?
    ): PublishResult = withContext(Dispatchers.IO) {
        if (!FirebaseConfig.isConfigured) {
            return@withContext PublishResult.Error(
                "Firebase not configured. Set PROJECT_ID and API_KEY in FirebaseConfig.kt"
            )
        }

        val shareCode = existingShareCode ?: generateShareCode()
        val document = buildFirestoreDocument(tournament, standings, allPairings)

        try {
            val url = FirebaseConfig.firestoreUrl("tournaments/$shareCode")
            val error = patchDocument(url, document)
            if (error == null) {
                PublishResult.Success(
                    url = FirebaseConfig.webViewerUrl(shareCode),
                    shareCode = shareCode
                )
            } else {
                PublishResult.Error("Upload failed: $error")
            }
        } catch (e: Exception) {
            PublishResult.Error("Publish failed: ${e.message}")
        }
    }

    private fun buildFirestoreDocument(
        tournament: Tournament,
        standings: List<PlayerStanding>,
        allPairings: List<Pairing>
    ): JSONObject {
        val fields = JSONObject()

        fields.put("writeKey", stringValue(FirebaseConfig.writeKey))
        fields.put("name", stringValue(tournament.name))
        fields.put("type", stringValue(tournament.type.name))
        fields.put("timeControl", stringValue(tournament.timeControl ?: ""))
        fields.put("totalRounds", intValue(tournament.totalRounds.toLong()))
        fields.put("currentRound", intValue(tournament.currentRound.toLong()))
        fields.put("status", stringValue(tournament.status.name))
        fields.put("tiebreakConfig", stringValue(tournament.tiebreakConfig))
        fields.put("updatedAt", intValue(System.currentTimeMillis()))

        val playersArray = JSONArray()
        for (s in standings) {
            val playerFields = JSONObject()
            playerFields.put("id", intValue(s.playerId))
            playerFields.put("name", stringValue(s.name))
            playerFields.put("fideId", stringValue(s.fideId))
            playerFields.put("rating", intValue(s.rating.toLong()))
            playerFields.put("score", doubleValue(s.score.toDouble()))
            playerFields.put("tiebreak", doubleValue(s.tiebreak.toDouble()))
            playerFields.put("tiebreak2", doubleValue(s.tiebreak2.toDouble()))
            playerFields.put("tiebreak3", doubleValue(s.tiebreak3.toDouble()))
            playerFields.put("tiebreak4", doubleValue(s.tiebreak4.toDouble()))
            playerFields.put("status", stringValue(s.status.name))
            playerFields.put("seedNumber", intValue(s.seedNumber.toLong()))
            playersArray.put(JSONObject().put("mapValue", JSONObject().put("fields", playerFields)))
        }
        fields.put("players", JSONObject().put("arrayValue", JSONObject().put("values", playersArray)))

        val pairingsArray = JSONArray()
        for (p in allPairings) {
            val pFields = JSONObject()
            pFields.put("roundNumber", intValue(p.roundNumber.toLong()))
            pFields.put("board", intValue(p.board.toLong()))
            pFields.put("whitePlayerId", intValue(p.whitePlayerId))
            if (p.blackPlayerId != null) {
                pFields.put("blackPlayerId", intValue(p.blackPlayerId))
            } else {
                pFields.put("blackPlayerId", JSONObject().put("nullValue", JSONObject.NULL))
            }
            pFields.put("result", stringValue(p.result.name))
            pairingsArray.put(JSONObject().put("mapValue", JSONObject().put("fields", pFields)))
        }
        fields.put("pairings", JSONObject().put("arrayValue", JSONObject().put("values", pairingsArray)))

        return JSONObject().put("fields", fields)
    }

    private fun stringValue(v: String) = JSONObject().put("stringValue", v)
    private fun intValue(v: Long) = JSONObject().put("integerValue", v.toString())
    private fun doubleValue(v: Double) = JSONObject().put("doubleValue", v)

    private fun patchDocument(urlStr: String, document: JSONObject): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(document.toString())
            writer.flush()
        }

        val code = conn.responseCode
        if (code in 200..299) {
            conn.disconnect()
            return null
        }
        val errorBody = try {
            conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
        conn.disconnect()
        return errorBody
    }

    private fun generateShareCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        return (1..8).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
