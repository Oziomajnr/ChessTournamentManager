package com.chess.tournament

import android.app.Application
import com.chess.tournament.billing.BillingManager
import com.chess.tournament.billing.ProStateManager
import com.chess.tournament.db.AppDatabase

class ChessTournamentApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    lateinit var billingManager: BillingManager
        private set
    lateinit var proStateManager: ProStateManager
        private set

    override fun onCreate() {
        super.onCreate()
        billingManager = BillingManager(this)
        proStateManager = ProStateManager(this, billingManager)
        billingManager.connect()
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
    }
}
