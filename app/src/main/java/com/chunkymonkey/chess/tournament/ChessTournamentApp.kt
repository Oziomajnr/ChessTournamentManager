package com.chunkymonkey.chess.tournament

import android.app.Application
import com.chunkymonkey.chess.tournament.analytics.AppAnalytics
import com.chunkymonkey.chess.tournament.billing.BillingManager
import com.google.firebase.analytics.FirebaseAnalytics
import com.chunkymonkey.chess.tournament.billing.ProStateManager
import com.chunkymonkey.chess.tournament.config.RemoteConfigManager
import com.chunkymonkey.chess.tournament.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ChessTournamentApp : Application() {

    lateinit var analytics: AppAnalytics
        private set

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val remoteConfigManager: RemoteConfigManager by lazy { RemoteConfigManager() }
    lateinit var billingManager: BillingManager
        private set
    lateinit var proStateManager: ProStateManager
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        analytics = AppAnalytics(FirebaseAnalytics.getInstance(this))
        billingManager = BillingManager(this)
        proStateManager = ProStateManager(this, billingManager, remoteConfigManager)
        billingManager.connect()

        scope.launch {
            remoteConfigManager.fetchAndActivate()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        billingManager.destroy()
    }

    companion object {
        fun getDatabase(application: Application): AppDatabase {
            return (application as ChessTournamentApp).database
        }

        fun getBillingManager(application: Application): BillingManager {
            return (application as ChessTournamentApp).billingManager
        }

        fun getProStateManager(application: Application): ProStateManager {
            return (application as ChessTournamentApp).proStateManager
        }

        fun getAnalytics(application: Application): AppAnalytics {
            return (application as ChessTournamentApp).analytics
        }
    }
}
