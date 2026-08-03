package com.quantalgotrade.crypto.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("quant_algo_trade_session")

class SessionStore(private val context: Context, private val json: Json) {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val userKey = stringPreferencesKey("user_json")
    private val emailKey = stringPreferencesKey("saved_email")
    private val biometricKey = booleanPreferencesKey("biometric_enabled")
    private val lastAlertKey = stringPreferencesKey("last_seen_alert_id")
    private val alertsBootstrappedKey = booleanPreferencesKey("alerts_bootstrapped")

    val accessToken: Flow<String?> = context.dataStore.data.map { it[accessKey] }
    val user: Flow<UserInfo?> = context.dataStore.data.map { prefs ->
        prefs[userKey]?.let { runCatching { json.decodeFromString<UserInfo>(it) }.getOrNull() }
    }
    val biometricEnabled: Flow<Boolean> = context.dataStore.data.map { it[biometricKey] == true }

    suspend fun currentAccessToken(): String? = context.dataStore.data.first()[accessKey]
    suspend fun currentRefreshToken(): String? = context.dataStore.data.first()[refreshKey]
    suspend fun savedEmail(): String? = context.dataStore.data.first()[emailKey]
    suspend fun isBiometricEnabled(): Boolean = context.dataStore.data.first()[biometricKey] == true
    suspend fun hasSession(): Boolean = !currentRefreshToken().isNullOrBlank()
    suspend fun lastSeenAlertId(): String? = context.dataStore.data.first()[lastAlertKey]
    suspend fun alertsBootstrapped(): Boolean = context.dataStore.data.first()[alertsBootstrappedKey] == true

    suspend fun saveSession(access: String, refresh: String, user: UserInfo) {
        context.dataStore.edit {
            it[accessKey] = access
            it[refreshKey] = refresh
            it[userKey] = json.encodeToString(user)
            it[emailKey] = user.email
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { it[biometricKey] = enabled }
    }

    suspend fun markAlertsBootstrapped(latestId: String?) {
        context.dataStore.edit {
            it[alertsBootstrappedKey] = true
            if (!latestId.isNullOrBlank()) it[lastAlertKey] = latestId
        }
    }

    suspend fun setLastSeenAlertId(id: String) {
        context.dataStore.edit { it[lastAlertKey] = id }
    }

    /** Drop access token but keep refresh so biometric can restore the session. */
    suspend fun clearAccessOnly() {
        context.dataStore.edit {
            it.remove(accessKey)
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(accessKey)
            it.remove(refreshKey)
            it.remove(userKey)
            it.remove(lastAlertKey)
            it.remove(alertsBootstrappedKey)
            // keep email + biometric preference for next unlock
        }
    }
}
