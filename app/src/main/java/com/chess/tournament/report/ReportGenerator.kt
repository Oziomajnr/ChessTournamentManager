package com.chess.tournament.report

import com.chess.tournament.db.entity.Pairing
import com.chess.tournament.db.entity.PairingResult
import com.chess.tournament.db.entity.PlayerStanding
import com.chess.tournament.db.entity.TiebreakType
import com.chess.tournament.db.entity.Tournament
import com.chess.tournament.db.entity.TournamentType

class ReportGenerator {

    @Suppress("UNUSED_PARAMETER")
    fun generateStandingsReport(
        tournament: Tournament,
        standings: List<PlayerStanding>,
        playerNames: Map<Long, String>
    ): String {
        val sb = StringBuilder()
        val tiebreakChain = tournament.getTiebreakChain()

        sb.appendLine("=" .repeat(60))
        sb.appendLine(tournament.name)
        sb.appendLine("=" .repeat(60))
        sb.appendLine()
        sb.appendLine("Type: ${if (tournament.type == TournamentType.SWISS) "Swiss" else "Round Robin"}")
        if (!tournament.timeControl.isNullOrBlank()) {
            sb.appendLine("Time Control: ${tournament.timeControl}")
        }
        sb.appendLine("Rounds: ${tournament.currentRound} / ${tournament.totalRounds}")
        sb.appendLine()

        val tbHeaders = tiebreakChain.take(4).joinToString("  ") { tiebreakShort(it).padStart(5) }
        sb.appendLine(
            "${"#".padStart(3)}  ${"Player".padEnd(25)} ${"Rtg".padStart(5)}  ${"Pts".padStart(5)}  $tbHeaders"
        )
        sb.appendLine("-".repeat(60))

        standings.forEachIndexed { idx, s ->
            val name = s.name.take(25).padEnd(25)
            val tbValues = listOf(s.tiebreak, s.tiebreak2, s.tiebreak3, s.tiebreak4)
            val tbStr = tiebreakChain.take(4).mapIndexed { i, _ ->
                formatScore(tbValues.getOrElse(i) { 0f }).padStart(5)
            }.joinToString("  ")
            sb.appendLine(
                "${(idx + 1).toString().padStart(3)}  $name ${s.rating.toString().padStart(5)}  ${formatScore(s.score).padStart(5)}  $tbStr"
            )
        }

        sb.appendLine()
        sb.appendLine("Tiebreak order: ${tiebreakChain.joinToString(" > ") { it.displayName() }}")
        return sb.toString()
    }

    fun generatePairingsReport(
        tournament: Tournament,
        roundNumber: Int,
        pairings: List<Pairing>,
        playerNames: Map<Long, String>
    ): String {
        val sb = StringBuilder()

        sb.appendLine("=" .repeat(50))
        sb.appendLine("${tournament.name} — Round $roundNumber")
        sb.appendLine("=" .repeat(50))
        sb.appendLine()

        sb.appendLine(
            "${"Bd".padStart(3)}  ${"White".padEnd(22)} ${"Result".padStart(7)}  ${"Black".padEnd(22)}"
        )
        sb.appendLine("-".repeat(60))

        for (p in pairings) {
            val white = (playerNames[p.whitePlayerId] ?: "?").take(22).padEnd(22)
            val black = if (p.blackPlayerId != null)
                (playerNames[p.blackPlayerId] ?: "?").take(22).padEnd(22)
            else "BYE".padEnd(22)

            val result = when (p.result) {
                PairingResult.WHITE_WINS -> "  1-0  "
                PairingResult.BLACK_WINS -> "  0-1  "
                PairingResult.DRAW -> " \u00BD-\u00BD  "
                PairingResult.BYE -> "  BYE  "
                PairingResult.FORFEIT_WHITE -> " F 1-0 "
                PairingResult.FORFEIT_BLACK -> " F 0-1 "
                PairingResult.PENDING -> "   *   "
            }

            sb.appendLine("${p.board.toString().padStart(3)}  $white $result  $black")
        }

        return sb.toString()
    }

    fun generateFullReport(
        tournament: Tournament,
        standings: List<PlayerStanding>,
        allPairings: List<Pairing>,
        playerNames: Map<Long, String>
    ): String {
        val sb = StringBuilder()
        sb.append(generateStandingsReport(tournament, standings, playerNames))
        sb.appendLine()

        val rounds = allPairings.groupBy { it.roundNumber }.toSortedMap()
        for ((round, pairings) in rounds) {
            sb.appendLine()
            sb.append(generatePairingsReport(tournament, round, pairings, playerNames))
        }

        return sb.toString()
    }

    private fun tiebreakShort(type: TiebreakType): String = when (type) {
        TiebreakType.BUCHHOLZ -> "BH"
        TiebreakType.BUCHHOLZ_CUT1 -> "BC1"
        TiebreakType.MEDIAN_BUCHHOLZ -> "MBH"
        TiebreakType.SONNEBORN_BERGER -> "SB"
        TiebreakType.DIRECT_ENCOUNTER -> "DE"
        TiebreakType.WINS -> "W"
        TiebreakType.BLACK_WINS -> "BW"
        TiebreakType.KOYA -> "KY"
    }

    private fun formatScore(score: Float): String {
        return if (score == score.toLong().toFloat()) {
            score.toLong().toString()
        } else {
            String.format("%.1f", score)
        }
    }
}
