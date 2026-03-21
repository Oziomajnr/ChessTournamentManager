package com.chunkymonkey.chess.tournament.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chunkymonkey.chess.tournament.billing.BillingManager
import com.chunkymonkey.chess.tournament.billing.ProStateManager
import com.chunkymonkey.chess.tournament.ui.screens.BackupScreen
import com.chunkymonkey.chess.tournament.ui.screens.CreateTournamentScreen
import com.chunkymonkey.chess.tournament.ui.screens.HomeScreen
import com.chunkymonkey.chess.tournament.ui.screens.RoundDetailScreen
import com.chunkymonkey.chess.tournament.ui.screens.TournamentDetailScreen
import com.chunkymonkey.chess.tournament.ui.screens.TournamentSettingsScreen
import com.chunkymonkey.chess.tournament.ui.screens.UpgradeScreen
import com.chunkymonkey.chess.tournament.viewmodel.BackupViewModel
import com.chunkymonkey.chess.tournament.viewmodel.PlayerViewModel
import com.chunkymonkey.chess.tournament.viewmodel.TournamentViewModel

object Routes {
    const val HOME = "home"
    const val CREATE_TOURNAMENT = "create_tournament"
    const val TOURNAMENT_DETAIL = "tournament/{tournamentId}"
    const val ROUND_DETAIL = "tournament/{tournamentId}/round/{roundNumber}"
    const val BACKUP = "backup"
    const val TOURNAMENT_SETTINGS = "tournament/{tournamentId}/settings"
    const val UPGRADE = "upgrade"

    fun tournamentDetail(id: Long) = "tournament/$id"
    fun roundDetail(tournamentId: Long, roundNumber: Int) = "tournament/$tournamentId/round/$roundNumber"
    fun tournamentSettings(id: Long) = "tournament/$id/settings"
}

@Composable
fun ChessTournamentNavGraph(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    tournamentViewModel: TournamentViewModel,
    backupViewModel: BackupViewModel,
    billingManager: BillingManager,
    proStateManager: ProStateManager
) {
    val proFeaturesLocked by proStateManager.proFeaturesLocked.collectAsState()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                playerViewModel = playerViewModel,
                tournamentViewModel = tournamentViewModel,
                onCreateTournament = { navController.navigate(Routes.CREATE_TOURNAMENT) },
                onTournamentClick = { id ->
                    navController.navigate(Routes.tournamentDetail(id))
                },
                onBackupClick = { navController.navigate(Routes.BACKUP) }
            )
        }

        composable(Routes.CREATE_TOURNAMENT) {
            val remainingFree by proStateManager.remainingFree.collectAsState()
            CreateTournamentScreen(
                playerViewModel = playerViewModel,
                tournamentViewModel = tournamentViewModel,
                onNavigateBack = { navController.popBackStack() },
                remainingFree = remainingFree,
                proFeaturesLocked = proFeaturesLocked,
                onUpgradeClick = { navController.navigate(Routes.UPGRADE) }
            )
        }

        composable(
            route = Routes.TOURNAMENT_DETAIL,
            arguments = listOf(navArgument("tournamentId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tournamentId = backStackEntry.arguments?.getLong("tournamentId") ?: return@composable
            TournamentDetailScreen(
                tournamentId = tournamentId,
                tournamentViewModel = tournamentViewModel,
                onNavigateBack = { navController.popBackStack() },
                onRoundClick = { roundNumber ->
                    navController.navigate(Routes.roundDetail(tournamentId, roundNumber))
                },
                onSettingsClick = {
                    navController.navigate(Routes.tournamentSettings(tournamentId))
                },
                proFeaturesLocked = proFeaturesLocked,
                onUpgradeClick = { navController.navigate(Routes.UPGRADE) }
            )
        }

        composable(
            route = Routes.ROUND_DETAIL,
            arguments = listOf(
                navArgument("tournamentId") { type = NavType.LongType },
                navArgument("roundNumber") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val tournamentId = backStackEntry.arguments?.getLong("tournamentId") ?: return@composable
            val roundNumber = backStackEntry.arguments?.getInt("roundNumber") ?: return@composable
            RoundDetailScreen(
                tournamentId = tournamentId,
                roundNumber = roundNumber,
                tournamentViewModel = tournamentViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.BACKUP) {
            BackupScreen(
                backupViewModel = backupViewModel,
                onNavigateBack = { navController.popBackStack() },
                proFeaturesLocked = proFeaturesLocked,
                onUpgradeClick = { navController.navigate(Routes.UPGRADE) }
            )
        }

        composable(Routes.UPGRADE) {
            UpgradeScreen(
                billingManager = billingManager,
                proStateManager = proStateManager,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.TOURNAMENT_SETTINGS,
            arguments = listOf(navArgument("tournamentId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tournamentId = backStackEntry.arguments?.getLong("tournamentId") ?: return@composable
            TournamentSettingsScreen(
                tournamentId = tournamentId,
                tournamentViewModel = tournamentViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
