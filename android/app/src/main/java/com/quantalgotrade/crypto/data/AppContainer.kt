package com.quantalgotrade.crypto.data

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.quantalgotrade.crypto.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val sessionStore = SessionStore(appContext, json).also { store ->
        // seed default API if empty
        runBlocking {
            if (store.currentApiBase().isBlank()) {
                store.saveApiBase(BuildConfig.DEFAULT_API_BASE)
            }
        }
    }

    @Volatile
    private var retrofitBase: String = BuildConfig.DEFAULT_API_BASE

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    private fun buildApi(baseUrl: String): ApiService {
        val normalized = baseUrl.trim().trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }

    @Volatile
    private var apiInternal: ApiService = buildApi(BuildConfig.DEFAULT_API_BASE)

    val api: ApiService
        get() {
            val base = runBlocking { sessionStore.currentApiBase() }
            if (base != retrofitBase) {
                synchronized(this) {
                    if (base != retrofitBase) {
                        retrofitBase = base
                        apiInternal = buildApi(base)
                    }
                }
            }
            return apiInternal
        }

    suspend fun updateApiBase(base: String) {
        sessionStore.saveApiBase(base)
        synchronized(this) {
            retrofitBase = base.trim().trimEnd('/')
            apiInternal = buildApi(retrofitBase)
        }
    }
}
