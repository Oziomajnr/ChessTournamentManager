package com.chunkymonkey.chess.tournament.review

import android.app.Activity
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import com.chunkymonkey.chess.tournament.ChessTournamentApp
import com.chunkymonkey.chess.tournament.MainActivity
import com.chunkymonkey.chess.tournament.db.entity.PairingResult
import com.chunkymonkey.chess.tournament.db.entity.Player
import com.chunkymonkey.chess.tournament.db.entity.Tournament
import com.chunkymonkey.chess.tournament.db.entity.TournamentPlayer
import com.chunkymonkey.chess.tournament.db.entity.TournamentStatus
import com.chunkymonkey.chess.tournament.db.entity.TournamentType
import com.chunkymonkey.chess.tournament.ui.navigation.ChessTournamentNavGraph
import com.chunkymonkey.chess.tournament.ui.navigation.Routes
import com.chunkymonkey.chess.tournament.ui.theme.ChessTournamentTheme
import com.chunkymonkey.chess.tournament.viewmodel.BackupViewModel
import com.chunkymonkey.chess.tournament.viewmodel.PlayerViewModel
import com.chunkymonkey.chess.tournament.viewmodel.TournamentViewModel
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.testing.FakeReviewManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RoundReviewTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app by lazy { ui.activity.application as ChessTournamentApp }
    private val db by lazy { app.database }
    private val history by lazy { app.cacheDir.resolve("review-test-" + UUID.randomUUID()) }
    private val now = AtomicLong()
    private val clockReads = AtomicInteger()
    private val store by lazy { ReviewAttemptStore(history) { now.get().also { clockReads.incrementAndGet() } } }
    private val play by lazy { DelayedPlay(app) }
    private val prompt by lazy { PlayReviewPrompt(store) { play } }
    private val tournaments = mutableListOf<Tournament>()
    private val players = mutableListOf<Player>()

    @After fun removeFixtures() {
        runBlocking {
            tournaments.forEach { db.tournamentDao().delete(it) }
            players.forEach { db.playerDao().delete(it) }
        }
        history.delete()
    }

    @Test fun swissAsksAfterPairingOnceEvenOnRapidTaps() {
        val t = fixture(TournamentType.SWISS, total = 3, current = 0, playerCount = 3)
        openOverview(t)
        generate(1)
        waitText("Round 1 / 3")
        generate(2)
        waitText("pending result", substring = true)
        assertEquals(0, play.requests.get())

        ui.onNodeWithText("Rounds").performClick()
        ui.onNodeWithText("Round 1").performClick()
        ui.onNodeWithText("½ - ½").performClick()
        ui.waitUntil { runBlocking { db.pairingDao().getPendingCount(t.id, 1) } == 0 }
        ui.onNodeWithContentDescription("Back").performClick()
        waitText("Round 1 / 3")
        assertEquals(0, play.requests.get())

        ui.onNodeWithText("Generate Round 2").performTouchInput { doubleClick() }
        waitText("Round 2 / 3")
        ui.waitUntil(5000) { play.requests.get() == 1 }
        val boards = runBlocking { db.pairingDao().getAllPairingsForTournament(t.id) }
        assertEquals(2, boards.count { it.roundNumber == 2 })
        assertEquals(1, boards.count { it.roundNumber == 2 && it.result == PairingResult.BYE })
        play.respond()
        ui.waitUntil(5000) { play.launches.get() == 1 }
    }

    @Test fun roundRobinCanQualifyAtRoundThreeAfterCooldownAndPlayFailureIsCounted() {
        runBlocking { store.requestIfEligible(0) { true } }
        now.set(TimeUnit.DAYS.toMillis(14))
        val t = fixture(TournamentType.ROUND_ROBIN, total = 5)
        openOverview(t)
        generate(2)
        waitText("Round 2 / 5")
        ui.waitUntil(5000) { clockReads.get() == 2 }
        assertEquals(0, play.requests.get())

        now.set(TimeUnit.DAYS.toMillis(15))
        scorePendingBoards(t)
        generate(3)
        waitText("Round 3 / 5")
        ui.waitUntil(5000) { play.requests.get() == 1 }
        play.response.setException(IllegalStateException("Play unavailable"))
        now.set(TimeUnit.DAYS.toMillis(365))
        scorePendingBoards(t)
        generate(4)
        waitText("Round 4 / 5")
        ui.waitUntil(5000) { clockReads.get() == 4 }
        assertEquals(1, play.requests.get())
        assertEquals(0, play.launches.get())
    }

    @Test fun finalRoundsAndByeOnlyRoundsProduceNoRequest() {
        val final = fixture(TournamentType.ROUND_ROBIN, total = 2)
        openOverview(final)
        generate(2)
        waitText("Round 2 / 2")
        val bye = fixture(TournamentType.ROUND_ROBIN, total = 4, playerCount = 1)
        openOverview(bye)
        generate(2)
        waitText("Round 2 / 4")
        val standings = runBlocking { db.tournamentDao().getTournamentStandingsOnce(bye.id) }
        assertEquals(1f, standings.single().score)
        assertEquals(0, play.requests.get())
        assertEquals(0, clockReads.get())
    }

    @Test fun roundFourQualifiesOnlyWhenAnotherRoundFollows() {
        openOverview(fixture(TournamentType.ROUND_ROBIN, total = 4, current = 3))
        generate(4)
        waitText("Round 4 / 4")
        assertEquals(0, play.requests.get())
        openOverview(fixture(TournamentType.ROUND_ROBIN, total = 5, current = 3))
        generate(4)
        ui.waitUntil(5000) { play.requests.get() == 1 }
        play.respond()
        ui.waitUntil(5000) { play.launches.get() == 1 }
    }

    @Test fun standingsFailureRollsBackRoundAndBoardsAndAllowsRetry() {
        val t = fixture(TournamentType.ROUND_ROBIN, total = 4, playerCount = 3)
        openOverview(t)
        val sql = db.openHelper.writableDatabase
        val trigger = "reject_test_score_" + t.id
        sql.execSQL("""
            CREATE TEMP TRIGGER $trigger BEFORE UPDATE OF score ON tournament_players
            WHEN OLD.tournamentId = ${t.id}
            BEGIN SELECT RAISE(ABORT, 'Injected standings failure'); END
        """.trimIndent())
        try {
            generate(2)
            waitText("Injected standings failure", substring = true)
            assertEquals(1, runBlocking { db.tournamentDao().getTournamentByIdOnce(t.id) }?.currentRound)
            assertTrue(runBlocking { db.pairingDao().getAllPairingsForTournament(t.id) }.isEmpty())
            assertEquals(0, play.requests.get())
        } finally {
            sql.execSQL("DROP TRIGGER $trigger")
        }
        generate(2)
        ui.waitUntil(5000) { play.requests.get() == 1 }
    }

    @Test fun leavingAndReturningDiscardsPlayResponse() {
        val t = fixture(TournamentType.ROUND_ROBIN, total = 4)
        openOverview(t)
        generate(2)
        ui.waitUntil(5000) { play.requests.get() == 1 }
        ui.onNodeWithContentDescription("Settings").performClick()
        waitText("Tournament Settings")
        ui.onNodeWithContentDescription("Back").performClick()
        waitText("Round 2 / 4")
        play.respond()
        ui.waitForIdle()
        assertEquals(1, play.requests.get())
        assertEquals(0, play.launches.get())
    }

    @Test fun playerMenuTakesPrecedenceOverDelayedPlayResponse() {
        openOverview(fixture(TournamentType.ROUND_ROBIN, total = 4))
        generate(2)
        ui.waitUntil(5000) { play.requests.get() == 1 }
        ui.onNodeWithText("Player 1").performClick()
        waitText("Withdraw Player")
        play.respond()
        ui.waitForIdle()
        assertEquals(0, play.launches.get())
    }

    @Test fun endingTournamentDiscardsDelayedPlayResponse() {
        val t = fixture(TournamentType.SWISS, total = 4)
        openOverview(t)
        generate(2)
        ui.waitUntil(5000) { play.requests.get() == 1 }
        ui.onNodeWithText("End").performClick()
        play.respond()
        ui.waitUntil(5000) {
            runBlocking { db.tournamentDao().getTournamentByIdOnce(t.id) }?.status == TournamentStatus.COMPLETED
        }
        ui.waitForIdle()
        assertEquals(0, play.launches.get())
    }

    @Test fun recreationDiscardsPendingReviewAndOpeningExistingRoundDoesNotReplayIt() {
        val t = fixture(TournamentType.SWISS, total = 4)
        openOverview(t)
        generate(2)
        ui.waitUntil(5000) { play.requests.get() == 1 }
        ui.activityRule.scenario.recreate()
        openOverview(t.copy(currentRound = 2))
        play.respond()
        ui.waitForIdle()
        assertEquals(1, play.requests.get())
        assertEquals(0, play.launches.get())
    }

    private fun fixture(type: TournamentType, total: Int, current: Int = 1, playerCount: Int = 4): Tournament =
        runBlocking {
            val t = Tournament(name = "Review test", type = type, totalRounds = total, currentRound = current,
                status = if (current == 0) TournamentStatus.CREATED else TournamentStatus.IN_PROGRESS)
            val id = db.tournamentDao().insert(t)
            val saved = t.copy(id = id)
            tournaments.add(saved)
            val entrants = (1..playerCount).map { seed ->
                val player = Player(name = "Player $seed", fideId = "", rating = 1600 - seed)
                val playerId = db.playerDao().insert(player)
                players.add(player.copy(id = playerId))
                TournamentPlayer(tournamentId = id, playerId = playerId, seedNumber = seed)
            }
            db.tournamentDao().insertTournamentPlayers(entrants)
            saved
        }

    private fun openOverview(t: Tournament) {
        ui.activityRule.scenario.onActivity { activity ->
            val models = ViewModelProvider(activity)
            activity.setContent {
                ChessTournamentTheme {
                    val nav = rememberNavController()
                    ChessTournamentNavGraph(nav, models[PlayerViewModel::class.java],
                        models[TournamentViewModel::class.java], models[BackupViewModel::class.java], prompt)
                    LaunchedEffect(t.id) { nav.navigate(Routes.tournamentDetail(t.id)) }
                }
            }
        }
        waitText("Round ${t.currentRound} / ${t.totalRounds}")
    }

    private fun scorePendingBoards(t: Tournament) = runBlocking {
        val dao = db.pairingDao()
        dao.getAllPairingsForTournament(t.id).filter { it.result == PairingResult.PENDING }.forEach {
            dao.updateResult(it.id, PairingResult.DRAW)
        }
    }

    private fun generate(round: Int) = ui.onNodeWithText("Generate Round $round").performClick()

    private fun waitText(text: String, substring: Boolean = false) {
        ui.waitUntil(10000) { ui.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }

    private class DelayedPlay(context: Context) : ReviewManager {
        private val fake = FakeReviewManager(context)
        val requests = AtomicInteger()
        val launches = AtomicInteger()
        val response = TaskCompletionSource<ReviewInfo>()

        fun respond() = response.setResult(fake.requestReviewFlow().result)

        override fun requestReviewFlow(): Task<ReviewInfo> {
            requests.incrementAndGet()
            return response.task
        }

        override fun launchReviewFlow(activity: Activity, info: ReviewInfo): Task<Void> {
            launches.incrementAndGet()
            return fake.launchReviewFlow(activity, info)
        }
    }
}
