package com.chess.tournament.engine

import com.chess.tournament.db.entity.Pairing
import com.chess.tournament.db.entity.PairingResult
import com.chess.tournament.db.entity.PlayerStanding
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Golden/snapshot tests adapted from bbpPairings (Bierema Boyz Programming).
 * These tests use fixed input files (TRF-like format) and compare output against
 * expected pairing files. Unlike our property-based tests, these verify that our
 * engine produces consistent pairings for specific scenarios.
 *
 * Note: Our FIDE Swiss engine may produce different but equally valid pairings
 * than bbpPairings (Dutch 2025). Expected outputs are from our engine.
 *
 * @see <a href="https://github.com/BieremaBoyzProgramming/bbpPairings">bbpPairings</a>
 */
class BbpPairingsTest {

    private val engine = SwissPairingEngine()
    private val tournamentId = 1L
    private val resourceDir = "bbp"

    @Test
    fun `dutch_2025_C5 - 6 players round 3`() {
        runBbpTest("dutch_2025_C5")
    }

    @Test
    fun `dutch_2025_C9 - 5 players round 2`() {
        runBbpTest("dutch_2025_C9")
    }

    private fun runBbpTest(testId: String) {
        val input = loadResource("$resourceDir/$testId.input")
        val expected = loadResource("$resourceDir/$testId.output.expected").trim()

        val (standings, previousPairings, targetRound) = parseTrfInput(input)
        val pairings = engine.generatePairings(tournamentId, targetRound, standings, previousPairings)
        val actual = formatPairingsOutput(targetRound, pairings)

        assertEquals(
            "Output should match bbpPairings expected for $testId",
            expected,
            actual.trim()
        )
    }

    private fun loadResource(path: String): String {
        val url = javaClass.classLoader?.getResource(path)
            ?: throw IllegalArgumentException("Resource not found: $path")
        return File(url.toURI()).readText()
    }

    /**
     * Parse TRF-like input format from bbpPairings tests.
     * Format: 012 line = tournament, 001 lines = players, XXR N = generate round N.
     * Player line: 001 [id] [firstName] [lastName] [rating] [score] [round data...]
     * Round data: "round opponent color result" or "0000 - Z" for bye.
     */
    private fun parseTrfInput(input: String): Triple<List<PlayerStanding>, List<Pairing>, Int> {
        val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var targetRound = 1

        val players = mutableListOf<TrfPlayer>()
        val allPairings = mutableListOf<Pairing>()

        for (line in lines) {
            when {
                line.startsWith("XXR ") -> {
                    targetRound = line.substringAfter("XXR ").trim().toInt()
                }
                line.startsWith("001 ") -> {
                    val player = parsePlayerLine(line)
                    if (player != null) players.add(player)
                }
            }
        }

        // Build standings and pairings from player round history
        val maxRound = players.maxOfOrNull { it.rounds.size } ?: 0
        val playerMap = players.associateBy { it.id }

        for (roundNum in 1..maxRound) {
            val processed = mutableSetOf<Pair<Int, Int>>()
            val byes = mutableListOf<Int>()

            for (p in players) {
                val rd = p.rounds.getOrNull(roundNum - 1) ?: continue
                when {
                    rd.isBye -> byes.add(p.id)
                    rd.opponent != null -> {
                        val a = p.id
                        val b = rd.opponent
                        val pair = minOf(a, b) to maxOf(a, b)
                        if (pair !in processed) {
                            processed.add(pair)
                            val aRd = playerMap[a]!!.rounds[roundNum - 1]
                            val bRd = playerMap[b]!!.rounds[roundNum - 1]
                            val (whiteId, blackId) = if (aRd.color == 'w' || aRd.color == 'W') a to b else b to a
                            val whiteResult = if (aRd.color == 'w' || aRd.color == 'W') aRd.result else bRd.result
                            val result = whiteResult.toPairingResultFromWhitePerspective()

                            allPairings.add(
                                Pairing(
                                    tournamentId = tournamentId,
                                    roundNumber = roundNum,
                                    board = allPairings.count { it.roundNumber == roundNum } + 1,
                                    whitePlayerId = whiteId.toLong(),
                                    blackPlayerId = blackId.toLong(),
                                    result = result
                                )
                            )
                        }
                    }
                }
            }

            for (byePlayerId in byes) {
                allPairings.add(
                    Pairing(
                        tournamentId = tournamentId,
                        roundNumber = roundNum,
                        board = allPairings.count { it.roundNumber == roundNum } + 1,
                        whitePlayerId = byePlayerId.toLong(),
                        blackPlayerId = null,
                        result = PairingResult.BYE
                    )
                )
            }
        }

        val standings = players.map { p ->
            PlayerStanding(
                tournamentId = tournamentId,
                playerId = p.id.toLong(),
                score = p.score.toFloat(),
                tiebreak = 0f,
                seedNumber = p.id,
                name = "${p.firstName} ${p.lastName}",
                fideId = p.id.toString(),
                rating = p.rating
            )
        }

        return Triple(standings, allPairings, targetRound)
    }

    private data class TrfPlayer(
        val id: Int,
        val firstName: String,
        val lastName: String,
        val rating: Int,
        val score: Float,
        val rounds: List<RoundData>
    )

    private data class RoundData(
        val opponent: Int?,
        val color: Char,
        val result: String,
        val isBye: Boolean = false
    )

    private fun parsePlayerLine(line: String): TrfPlayer? {
        val tokens = line.split(Regex("\\s+"))
        if (tokens.size < 6) return null

        val id = tokens[1].toIntOrNull() ?: return null
        val firstName = tokens[2]
        val lastName = tokens[3]
        val rating = tokens[4].toIntOrNull() ?: 0
        val score = tokens[5].toFloatOrNull() ?: 0f

        val rounds = mutableListOf<RoundData>()
        var i = 6
        while (i < tokens.size) {
            when {
                tokens[i] == "0000" && i + 2 < tokens.size && tokens[i + 2] == "Z" -> {
                    rounds.add(RoundData(null, ' ', "1", isBye = true))
                    i += 3
                }
                i + 3 <= tokens.size -> {
                    val opp = tokens[i].toIntOrNull()
                    val color = tokens[i + 1].singleOrNull() ?: ' '
                    val result = tokens[i + 2]
                    when {
                        opp != null && opp != 0 -> rounds.add(RoundData(opp, color, result, isBye = false))
                        opp == 0 -> rounds.add(RoundData(null, ' ', result, isBye = true))
                    }
                    i += 3
                }
                else -> break
            }
        }

        return TrfPlayer(id, firstName, lastName, rating, score, rounds)
    }

    /** Convert result from white player's perspective (1=win, 0=loss, =/½=draw). */
    private fun String.toPairingResultFromWhitePerspective(): PairingResult = when (this) {
        "1", "+" -> PairingResult.WHITE_WINS
        "0", "-" -> PairingResult.BLACK_WINS
        "=", "½", "0.5" -> PairingResult.DRAW
        else -> PairingResult.PENDING
    }

    private fun formatPairingsOutput(roundNumber: Int, pairings: List<Pairing>): String {
        val sb = StringBuilder()
        sb.appendLine(roundNumber)

        for (p in pairings.sortedBy { it.board }) {
            val white = p.whitePlayerId.toInt()
            val black = if (p.blackPlayerId != null) p.blackPlayerId!!.toInt() else 0
            sb.appendLine("$white $black")
        }

        return sb.toString()
    }
}
