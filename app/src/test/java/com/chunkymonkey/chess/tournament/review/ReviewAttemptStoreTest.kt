package com.chunkymonkey.chess.tournament.review

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewAttemptStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = folder.root.resolve("attempts")
    private var now = 0L
    private fun reopen() = ReviewAttemptStore(file) { now }

    private suspend fun ReviewAttemptStore.tryRequest(id: Long): Boolean {
        var requested = false
        requestIfEligible(id) {
            requested = true
            true
        }
        return requested
    }

    @Test fun cooldownBoundariesSurviveRestartsAndInterleavedTournaments() = runBlocking {
        assertTrue(reopen().tryRequest(1))
        for ((index, day) in listOf(15L, 45L, 135L, 225L).withIndex()) {
            val nextTournament = index + 2L
            now = TimeUnit.DAYS.toMillis(day) - 1
            assertFalse(reopen().tryRequest(nextTournament))
            now++
            assertFalse(reopen().tryRequest(1))
            assertTrue(reopen().tryRequest(nextTournament))
            assertFalse(reopen().tryRequest(nextTournament + 1))
        }
        now += TimeUnit.DAYS.toMillis(365)
        for (id in 1L..5L) assertFalse(reopen().tryRequest(id))
    }

    @Test fun waitsFromActualRequestAndAllowsALaterRoundOfASkippedTournament() = runBlocking {
        assertTrue(reopen().tryRequest(1))
        now = TimeUnit.DAYS.toMillis(10)
        assertFalse(reopen().tryRequest(2))
        now = TimeUnit.DAYS.toMillis(23)
        assertTrue(reopen().tryRequest(2))
        now = TimeUnit.DAYS.toMillis(45)
        assertFalse(reopen().tryRequest(3))
        now = TimeUnit.DAYS.toMillis(53)
        assertTrue(reopen().tryRequest(3))
    }

    @Test fun concurrentClaimsStartOnlyOneRequest() = runBlocking {
        val store = reopen()
        val calls = AtomicInteger()
        coroutineScope {
            repeat(20) { index ->
                launch(Dispatchers.Default) {
                    store.requestIfEligible(index.toLong()) {
                        calls.incrementAndGet()
                        true
                    }
                }
            }
        }
        assertEquals(1, calls.get())
    }

    @Test fun losingTheOverviewBeforeCallingPlayRestoresThePreviousRecord() = runBlocking {
        reopen().requestIfEligible(1) { false }
        assertTrue(reopen().tryRequest(1))
        now = TimeUnit.DAYS.toMillis(15)
        reopen().requestIfEligible(2) { false }
        assertTrue(reopen().tryRequest(2))
        assertFalse(reopen().tryRequest(3))
    }

    @Test fun damagedHistoryCannotAuthorizeAnotherRequest() = runBlocking {
        file.writeBytes(byteArrayOf(0, 0, 0, 1))
        assertFalse(reopen().tryRequest(1))
        assertEquals(4, file.length())
    }
}
