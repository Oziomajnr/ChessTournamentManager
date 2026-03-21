package com.chess.tournament

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import com.chess.tournament.ui.navigation.ChessTournamentNavGraph
import com.chess.tournament.ui.theme.ChessTournamentTheme
import com.chess.tournament.viewmodel.BackupViewModel
import com.chess.tournament.viewmodel.PlayerViewModel
import com.chess.tournament.viewmodel.TournamentViewModel

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
