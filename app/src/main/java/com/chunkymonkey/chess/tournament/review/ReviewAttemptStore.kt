package com.chunkymonkey.chess.tournament.review

import androidx.core.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ReviewAttemptStore(
    file: File,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val file = AtomicFile(file)
    private val mutex = Mutex()

    // startRequest runs on the caller's dispatcher. False means the overview left
    // before the SDK could be called, so that opportunity does not cost an attempt.
    suspend fun requestIfEligible(tournamentId: Long, startRequest: () -> Boolean) {
        val caller = currentCoroutineContext()
        mutex.withLock {
            try {
                val previous = withContext(Dispatchers.IO) { readHistory() }
                val now = nowMillis()
                if (!previous.canRequest(tournamentId, now)) return

                // Once written, either start the SDK request or restore the old record.
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        writeHistory(History(now, previous.tournamentIds + tournamentId))
                    }
                    if (!caller.isActive || !startRequest()) {
                        withContext(Dispatchers.IO) { writeHistory(previous) }
                    }
                }
            } catch (_: IOException) {
                // An unreadable or unwritable record cannot authorize a prompt.
            }
        }
    }

    private data class History(val lastRequestMillis: Long, val tournamentIds: Set<Long>) {
        fun canRequest(tournamentId: Long, now: Long): Boolean {
            if (tournamentId in tournamentIds) return false
            val days = when (tournamentIds.size) {
                0 -> return true
                1 -> 15L
                2 -> 30L
                else -> 90L
            }
            return now - lastRequestMillis >= TimeUnit.DAYS.toMillis(days)
        }
    }

    private fun readHistory(): History {
        val stream = try {
            file.openRead()
        } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists()) throw missing
            return History(0L, emptySet())
        }
        return DataInputStream(stream.buffered()).use { input ->
            val count = input.readInt()
            val lastRequest = input.readLong()
            if (count < 0) throw IOException("Invalid review attempt count")
            val ids = mutableSetOf<Long>()
            repeat(count) { ids.add(input.readLong()) }
            if (ids.size != count || input.read() != -1) throw IOException("Invalid review history")
            History(lastRequest, ids)
        }
    }

    private fun writeHistory(history: History) {
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream.buffered())
            output.writeInt(history.tournamentIds.size)
            output.writeLong(history.lastRequestMillis)
            history.tournamentIds.forEach(output::writeLong)
            output.flush()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
        // AtomicFile can log a failed rename without throwing from finishWrite.
        if (readHistory() != history) throw IOException("Review history was not committed")
    }
}
