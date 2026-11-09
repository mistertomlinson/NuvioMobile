package com.nuvio.app.features.rewatch

import android.content.Context
import android.content.SharedPreferences

/**
 * Exists to compile the shared common code on Android. This 0.6.7 fork's
 * current work targets iOS; before wiring its Android player, call initialize
 * from Android startup as is done for WatchProgressStorage.
 */
internal actual object RewatchSessionStorage {
    private const val preferencesName = "nuvio_rewatch_sessions_v1"
    private const val payloadKey = "sessions"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(
            preferencesName, Context.MODE_PRIVATE
        )
    }

    actual fun loadPayload(profileId: Int): String? {
        require(profileId > 0)
        return checkNotNull(preferences) {
            "RewatchSessionStorage must be initialized"
        }.getString("${payloadKey}_$profileId", null)
    }

    actual fun savePayload(profileId: Int, payload: String) {
        require(profileId > 0)
        checkNotNull(preferences) {
            "RewatchSessionStorage must be initialized"
        }.edit().putString("${payloadKey}_$profileId", payload).apply()
    }
}
