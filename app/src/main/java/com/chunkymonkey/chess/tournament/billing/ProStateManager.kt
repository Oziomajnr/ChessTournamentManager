package com.chunkymonkey.chess.tournament.billing

import android.content.Context
import android.content.SharedPreferences
import com.chunkymonkey.chess.tournament.config.RemoteConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ProStateManager(
    context: Context,
    private val billingManager: BillingManager,
    private val remoteConfigManager: RemoteConfigManager
) {
    companion object {
        private const val PREFS_NAME = "chess_pro_state"
        private const val KEY_TOURNAMENT_COUNT = "lifetime_tournament_count"
        const val FREE_TOURNAMENT_LIMIT = 10
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tournamentCount = MutableStateFlow(prefs.getInt(KEY_TOURNAMENT_COUNT, 0))
    val tournamentCount: StateFlow<Int> = _tournamentCount

    /**
     * When premiumRequired is false (Firebase config), everyone gets Pro.
     * When true, normal billing + free tier logic applies.
     */
    val isPro: StateFlow<Boolean> = combine(
        remoteConfigManager.premiumRequired,
        billingManager.hasLifetime,
        billingManager.hasSubscription,
        _tournamentCount
    ) { premiumRequired, lifetime, subscription, count ->
        !premiumRequired || lifetime || subscription || count <= FREE_TOURNAMENT_LIMIT
    }.stateIn(scope, SharingStarted.Eagerly, true)

    val proFeaturesLocked: StateFlow<Boolean> = combine(
        remoteConfigManager.premiumRequired,
        billingManager.hasLifetime,
        billingManager.hasSubscription,
        _tournamentCount
    ) { premiumRequired, lifetime, subscription, count ->
        premiumRequired && !lifetime && !subscription && count > FREE_TOURNAMENT_LIMIT
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val remainingFree: StateFlow<Int> = combine(
        _tournamentCount
    ) { values ->
        val count = values[0]
        (FREE_TOURNAMENT_LIMIT - count).coerceAtLeast(0)
    }.stateIn(scope, SharingStarted.Eagerly, FREE_TOURNAMENT_LIMIT)

    fun incrementTournamentCount() {
        val newCount = _tournamentCount.value + 1
        _tournamentCount.value = newCount
        prefs.edit().putInt(KEY_TOURNAMENT_COUNT, newCount).apply()
    }
}
