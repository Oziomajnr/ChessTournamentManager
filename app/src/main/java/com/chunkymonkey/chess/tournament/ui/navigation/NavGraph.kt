package com.chunkymonkey.chess.tournament.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.chunkymonkey.chess.tournament.review.PlayReviewPrompt
import com.chunkymonkey.chess.tournament.ui.screens.BackupScreen
import com.chunkymonkey.chess.tournament.ui.screens.CreateTournamentScreen
import com.chunkymonkey.chess.tournament.ui.screens.HomeScreen
import com.chunkymonkey.chess.tournament.ui.screens.RoundDetailScreen
import com.chunkymonkey.chess.tournament.ui.screens.TournamentDetailScreen
import com.chunkymonkey.chess.tournament.ui.screens.TournamentSettingsScreen
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

    fun tournamentDetail(id: Long) = "tournament/$id"
    fun roundDetail(tournamentId: Long, roundNumber: Int) = "tournament/$tournamentId/round/$roundNumber"
    fun tournamentSettings(id: Long) = "tournament/$id/settings"
}

@Composable
internal fun ChessTournamentNavGraph(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    tournamentViewModel: TournamentViewModel,
    backupViewModel: BackupViewModel,
    reviewPrompt: PlayReviewPrompt
) {
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
            CreateTournamentScreen(
                playerViewModel = playerViewModel,
                tournamentViewModel = tournamentViewModel,
                onNavigateBack = { navController.popBackStack() }
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
                reviewPrompt = reviewPrompt,
                onNavigateBack = { navController.popBackStack() },
                onRoundClick = { roundNumber ->
                    navController.navigate(Routes.roundDetail(tournamentId, roundNumber))
                },
                onSettingsClick = {
                    navController.navigate(Routes.tournamentSettings(tournamentId))
                }
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
