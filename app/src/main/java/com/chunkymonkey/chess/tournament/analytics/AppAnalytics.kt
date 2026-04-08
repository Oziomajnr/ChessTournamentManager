package com.chunkymonkey.chess.tournament.analytics

import android.os.Bundle
import com.chunkymonkey.chess.tournament.db.entity.PairingResult
import com.chunkymonkey.chess.tournament.db.entity.TournamentType
import com.google.firebase.analytics.FirebaseAnalytics

class AppAnalytics(private val firebase: FirebaseAnalytics) {

    fun logScreenView(screenName: String) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenName)
        }
        firebase.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
    }

    fun logPlayerAdded() {
        firebase.logEvent("player_added", null)
    }

    fun logPlayerDeleted() {
        firebase.logEvent("player_deleted", null)
    }

    fun logTournamentCreated(format: TournamentType, playerCount: Int, totalRounds: Int) {
        val b = Bundle().apply {
            putString("format", format.name.lowercase())
            putLong("player_count", playerCount.toLong())
            putLong("total_rounds", totalRounds.toLong())
        }
        firebase.logEvent("tournament_created", b)
    }

    fun logTournamentDeleted() {
        firebase.logEvent("tournament_deleted", null)
    }

    fun logRoundGenerated(roundNumber: Int, format: TournamentType) {
        val b = Bundle().apply {
            putLong("round_number", roundNumber.toLong())
            putString("format", format.name.lowercase())
        }
        firebase.logEvent("round_generated", b)
    }

    fun logGameResultSaved(result: PairingResult) {
        val b = Bundle().apply {
            putString("result", result.name.lowercase())
        }
        firebase.logEvent("game_result_saved", b)
    }

    fun logTournamentCompleted() {
        firebase.logEvent("tournament_completed", null)
    }

    fun logWebPublishSuccess(firstPublish: Boolean) {
        val b = Bundle().apply {
            putInt("first_publish", if (firstPublish) 1 else 0)
        }
        firebase.logEvent("web_publish_success", b)
    }

    fun logWebPublishFailure(reasonCode: String) {
        val b = Bundle().apply {
            putString("reason_code", reasonCode)
        }
        firebase.logEvent("web_publish_failure", b)
    }

    fun logBackupExport(success: Boolean, playerCount: Int, tournamentCount: Int) {
        val b = Bundle().apply {
            putInt("success", if (success) 1 else 0)
            putLong("player_count", playerCount.toLong())
            putLong("tournament_count", tournamentCount.toLong())
        }
        firebase.logEvent("backup_export", b)
    }

    fun logBackupImport(success: Boolean, playerCount: Int, tournamentCount: Int) {
        val b = Bundle().apply {
            putInt("success", if (success) 1 else 0)
            putLong("player_count", playerCount.toLong())
            putLong("tournament_count", tournamentCount.toLong())
        }
        firebase.logEvent("backup_import", b)
    }

    fun logPurchaseFlowStarted(productId: String) {
        val b = Bundle().apply {
            putString("product_id", productId)
        }
        firebase.logEvent("purchase_flow_started", b)
    }

    fun logPurchaseCompleted(productId: String) {
        val b = Bundle().apply {
            putString("product_id", productId)
        }
        firebase.logEvent("purchase_completed", b)
    }

    fun logRestorePurchasesTapped() {
        firebase.logEvent("restore_purchases_tapped", null)
    }

    companion object {
        fun publishFailureReasonCode(message: String): String = when {
            message == "PRO_REQUIRED" -> "pro_required"
            message == "No tournament selected" -> "no_tournament"
            message.contains("not configured", ignoreCase = true) -> "not_configured"
            message.startsWith("Upload failed", ignoreCase = true) -> "upload_failed"
            message.startsWith("Publish failed", ignoreCase = true) -> "network_or_unknown"
            else -> "unknown"
        }

        fun screenNameForRoute(route: String?): String {
            if (route.isNullOrEmpty()) return "unknown"
            return when {
                route == "home" -> "home"
                route == "create_tournament" -> "create_tournament"
                route.contains("/round/") -> "round_detail"
                route.endsWith("/settings") -> "tournament_settings"
                route.startsWith("tournament/") -> "tournament_detail"
                route == "backup" -> "backup"
                route == "upgrade" -> "upgrade"
                else -> "unknown"
            }
        }
    }
}
