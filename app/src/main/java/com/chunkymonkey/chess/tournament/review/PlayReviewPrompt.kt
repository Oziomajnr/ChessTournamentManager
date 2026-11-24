package com.chunkymonkey.chess.tournament.review

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class PlayReviewPrompt(
    private val attempts: ReviewAttemptStore,
    private val managerFactory: (Context) -> ReviewManager = ReviewManagerFactory::create
) {
    suspend fun request(activity: Activity, tournamentId: Long, canPresent: () -> Boolean) {
        if (!canPresent()) return
        coroutineScope {
            attempts.requestIfEligible(tournamentId) {
                if (!canPresent()) return@requestIfEligible false
                // Start the SDK call before releasing the claim, but wait for Play
                // in the overview's cancellable scope, outside the storage lock.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val manager = managerFactory(activity.applicationContext)
                        val info = manager.requestReview()
                        if (canPresent()) manager.launchReview(activity, info)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (unavailable: Exception) {
                        Log.w("RoundReview", "Play review unavailable", unavailable)
                    }
                }
                true
            }
        }
    }
}
