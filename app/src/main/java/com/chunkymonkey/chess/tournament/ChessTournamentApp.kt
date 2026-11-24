package com.chunkymonkey.chess.tournament

import android.app.Application
import com.chunkymonkey.chess.tournament.analytics.AppAnalytics
import com.chunkymonkey.chess.tournament.db.AppDatabase
import com.chunkymonkey.chess.tournament.review.PlayReviewPrompt
import com.chunkymonkey.chess.tournament.review.ReviewAttemptStore
import com.google.firebase.analytics.FirebaseAnalytics

class ChessTournamentApp : Application() {

    lateinit var analytics: AppAnalytics
        private set

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    internal val reviewPrompt by lazy {
        PlayReviewPrompt(ReviewAttemptStore(noBackupFilesDir.resolve("review-attempts")))
    }

    override fun onCreate() {
        super.onCreate()
        analytics = AppAnalytics(FirebaseAnalytics.getInstance(this))
    }

    companion object {
        fun getDatabase(application: Application): AppDatabase {
            return (application as ChessTournamentApp).database
        }

        fun getAnalytics(application: Application): AppAnalytics {
            return (application as ChessTournamentApp).analytics
        }
    }
}
