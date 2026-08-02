package com.quantalgotrade.crypto.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quantalgotrade.crypto.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("quant_algo_trade_session")

class SessionStore(private val context: Context, private val json: Json) {
    private val apiBaseKey = stringPreferencesKey("api_base")
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val userKey = stringPreferencesKey("user_json")

    val apiBase: Flow<String> = context.dataStore.data.map {
        migrateApiBase(it[apiBaseKey])
    }

    val accessToken: Flow<String?> = context.dataStore.data.map { it[accessKey] }
    val user: Flow<UserInfo?> = context.dataStore.data.map { prefs ->
        prefs[userKey]?.let { runCatching { json.decodeFromString<UserInfo>(it) }.getOrNull() }
    }

    suspend fun currentApiBase(): String {
        val stored = context.dataStore.data.first()[apiBaseKey]
        val migrated = migrateApiBase(stored)
        if (stored != null && migrated != stored) {
            saveApiBase(migrated)
        }
        return migrated
    }

    /** Prefer API Gateway URL when an old EC2 direct URL is still cached. */
    private fun migrateApiBase(stored: String?): String {
        val fallback = BuildConfig.DEFAULT_API_BASE.trim().trimEnd('/')
        if (stored.isNullOrBlank()) return fallback
        val normalized = stored.trim().trimEnd('/')
        if (normalized.contains("13.127.111.41")) {
            return fallback
        }
        return normalized
    }

    suspend fun currentAccessToken(): String? = context.dataStore.data.first()[accessKey]
    suspend fun currentRefreshToken(): String? = context.dataStore.data.first()[refreshKey]

    suspend fun saveApiBase(base: String) {
        context.dataStore.edit { it[apiBaseKey] = base.trim().trimEnd('/') }
    }

    suspend fun saveSession(access: String, refresh: String, user: UserInfo) {
        context.dataStore.edit {
            it[accessKey] = access
            it[refreshKey] = refresh
            it[userKey] = json.encodeToString(user)
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(accessKey)
            it.remove(refreshKey)
            it.remove(userKey)
        }
    }
}
