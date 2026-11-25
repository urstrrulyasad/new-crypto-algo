package com.quantalgotrade.crypto.data

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.quantalgotrade.crypto.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val sessionStore = SessionStore(appContext, json)
    private val baseUrlNormalized = BuildConfig.DEFAULT_API_BASE.trim().trimEnd('/') + "/"
    private val refreshLock = Any()

    private val rawClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val authInterceptor = Interceptor { chain ->
        val token = runBlocking { sessionStore.currentAccessToken() }
        val req = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        chain.proceed(req)
    }

    private val tokenAuthenticator = Authenticator { _: Route?, response: Response ->
        if (response.request.header("X-Auth-Retried") != null) return@Authenticator null
        val path = response.request.url.encodedPath
        if (path.endsWith("/auth/login") || path.endsWith("/auth/refresh")) return@Authenticator null

        synchronized(refreshLock) {
            val latestAccess = runBlocking { sessionStore.currentAccessToken() }
            val sentAuth = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            if (!latestAccess.isNullOrBlank() && latestAccess != sentAuth) {
                return@synchronized response.request.newBuilder()
                    .header("Authorization", "Bearer $latestAccess")
                    .header("X-Auth-Retried", "1")
                    .build()
            }

            val refreshTok = runBlocking { sessionStore.currentRefreshToken() } ?: return@synchronized null
            val payload = json.encodeToString(RefreshRequest(refreshTok))
                .toRequestBody("application/json".toMediaType())
            val refreshReq = Request.Builder()
                .url("${baseUrlNormalized}api/v1/auth/refresh")
                .post(payload)
                .build()

            val refreshed = runCatching {
                rawClient.newCall(refreshReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    json.decodeFromString<LoginResponse>(body)
                }
            }.getOrNull()

            if (refreshed != null) {
                runBlocking {
                    sessionStore.saveSession(refreshed.accessToken, refreshed.refreshToken, refreshed.user)
                }
                return@synchronized response.request.newBuilder()
                    .header("Authorization", "Bearer ${refreshed.accessToken}")
                    .header("X-Auth-Retried", "1")
                    .build()
            } else {
                runBlocking { sessionStore.clearAccessOnly() }
                return@synchronized null
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .build()

    val api: ApiService = Retrofit.Builder()
        .baseUrl(baseUrlNormalized)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)
}
