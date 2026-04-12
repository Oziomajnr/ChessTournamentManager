package com.chunkymonkey.chess.tournament.config

import android.util.Log
import com.chunkymonkey.chess.tournament.R
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Manages Firebase Remote Config for feature flags.
 *
 * Key: premium_required (Boolean)
 * - false (default): Premium features are free for everyone
 * - true: Premium features require purchase
 *
 * To enable premium later, set premium_required to true in Firebase Console.
 */
class RemoteConfigManager {

    companion object {
        private const val TAG = "RemoteConfig"
        private const val KEY_PREMIUM_REQUIRED = "premium_required"
        private const val FETCH_INTERVAL_SECONDS = 60L
    }

    private val remoteConfig = FirebaseRemoteConfig.getInstance()

    private val _premiumRequired = MutableStateFlow(false)

    /** True when premium purchase is required for Pro features. False = everyone gets Pro. */
    val premiumRequired: StateFlow<Boolean> = _premiumRequired

    init {
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(FETCH_INTERVAL_SECONDS)
            .build()
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults)
        _premiumRequired.value = remoteConfig.getBoolean(KEY_PREMIUM_REQUIRED)
    }

    /**
     * Fetches and activates config from Firebase.
     * Uses cached/default values if fetch fails.
     */
    suspend fun fetchAndActivate() {
        try {
            val updated = remoteConfig.fetchAndActivate().await()
            _premiumRequired.value = remoteConfig.getBoolean(KEY_PREMIUM_REQUIRED)
            Log.d(TAG, "Remote Config: premium_required=${_premiumRequired.value}, fetched=${updated}")
        } catch (e: Exception) {
            _premiumRequired.value = remoteConfig.getBoolean(KEY_PREMIUM_REQUIRED)
            Log.w(TAG, "Remote Config fetch failed, using cached/default: ${e.message}")
        }
    }
}
