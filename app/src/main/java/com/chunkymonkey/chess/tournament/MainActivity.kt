package com.chunkymonkey.chess.tournament

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.chunkymonkey.chess.tournament.analytics.AppAnalytics
import com.chunkymonkey.chess.tournament.ui.navigation.ChessTournamentNavGraph
import com.chunkymonkey.chess.tournament.ui.theme.ChessTournamentTheme
import com.chunkymonkey.chess.tournament.viewmodel.BackupViewModel
import com.chunkymonkey.chess.tournament.viewmodel.PlayerViewModel
import com.chunkymonkey.chess.tournament.viewmodel.TournamentViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ChessTournamentApp
        val playerViewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        val tournamentViewModel = ViewModelProvider(this)[TournamentViewModel::class.java]
        val backupViewModel = ViewModelProvider(this)[BackupViewModel::class.java]

        setContent {
            ChessTournamentTheme(dynamicColor = false) {
                val navController = rememberNavController()
                val analytics = app.analytics
                DisposableEffect(navController) {
                    val listener =
                        NavController.OnDestinationChangedListener { _, destination, _ ->
                            val screenName = AppAnalytics.screenNameForRoute(destination.route)
                            analytics.logScreenView(screenName)
                        }
                    navController.addOnDestinationChangedListener(listener)
                    onDispose {
                        navController.removeOnDestinationChangedListener(listener)
                    }
                }
                ChessTournamentNavGraph(
                    navController = navController,
                    playerViewModel = playerViewModel,
                    tournamentViewModel = tournamentViewModel,
                    backupViewModel = backupViewModel,
                    billingManager = app.billingManager,
                    proStateManager = app.proStateManager
                )
            }
        }
    }
}
