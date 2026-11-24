package com.chunkymonkey.chess.tournament.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.chunkymonkey.chess.tournament.db.entity.Tournament
import com.chunkymonkey.chess.tournament.db.entity.TournamentStatus
import com.chunkymonkey.chess.tournament.review.PlayReviewPrompt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

@Composable
internal fun rememberRoundReviewHandler(
    tournamentId: Long,
    visibleTournament: () -> Tournament?,
    overviewAvailable: () -> Boolean,
    prompt: PlayReviewPrompt
): (Tournament) -> Unit {
    val activity = LocalContext.current.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentTournament by rememberUpdatedState(visibleTournament)
    val available by rememberUpdatedState(overviewAvailable)

    val onRoundPaired by produceState<(Tournament) -> Unit>({}, tournamentId, activity, lifecycle, prompt) {
        if (activity == null) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Each uninterrupted visit has its own receiver. A late pairing can only
            // reach the visit whose Generate button started it.
            val rounds = Channel<Tournament>(Channel.CONFLATED)
            value = { rounds.trySend(it) }
            try {
                for (paired in rounds) {
                    if (paired.id != tournamentId || !paired.isReviewRound()) continue

                    // Room can deliver the committed round to Compose after the callback.
                    snapshotFlow { currentTournament() }.first { visible ->
                        visible?.id != tournamentId || visible.status == TournamentStatus.COMPLETED ||
                            visible.currentRound >= paired.currentRound
                    }
                    fun canPresent(): Boolean {
                        val visible = currentTournament()
                        return isActive && lifecycle.currentState == Lifecycle.State.RESUMED &&
                            !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() &&
                            available() && visible?.id == tournamentId &&
                            visible.currentRound == paired.currentRound && visible.isReviewRound()
                    }
                    prompt.request(activity, tournamentId, ::canPresent)
                }
            } finally {
                rounds.cancel()
                value = {}
            }
        }
    }
    return onRoundPaired
}

private fun Tournament.isReviewRound() =
    status == TournamentStatus.IN_PROGRESS && currentRound in 2..4 && currentRound < totalRounds

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
