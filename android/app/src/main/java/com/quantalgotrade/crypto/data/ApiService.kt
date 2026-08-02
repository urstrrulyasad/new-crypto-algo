package com.quantalgotrade.crypto.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("/api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("/api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): LoginResponse

    @GET("/api/v1/portfolio/wallet")
    suspend fun wallet(): Wallet

    @GET("/api/v1/portfolio/summary")
    suspend fun summary(@Query("mode") mode: String = "LIVE"): Summary

    @GET("/api/v1/portfolio/positions")
    suspend fun positions(@Query("mode") mode: String = "LIVE"): List<Position>

    @GET("/api/v1/strategies")
    suspend fun strategies(@Query("marketType") marketType: String = "FUTURES"): List<Strategy>

    @GET("/api/v1/strategies/{id}")
    suspend fun strategy(@Path("id") id: String): Strategy

    @GET("/api/v1/strategies/{id}/trades")
    suspend fun strategyTrades(
        @Path("id") id: String,
        @Query("mode") mode: String? = "PAPER",
    ): List<StrategyTrade>

    @GET("/api/v1/market/futures/instruments")
    suspend fun futuresInstruments(): InstrumentsResponse

    @GET("/api/v1/market/candles")
    suspend fun candles(
        @Query("pair") pair: String,
        @Query("timeframe") timeframe: String = "5m",
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("limit") limit: Int = 400,
        @Query("marketType") marketType: String = "FUTURES",
    ): List<Candle>

    @GET("/api/v1/keys")
    suspend fun keys(): List<ExchangeKey>

    @POST("/api/v1/keys")
    suspend fun createKey(@Body body: CreateKeyRequest): ExchangeKey

    @DELETE("/api/v1/keys/{id}")
    suspend fun deleteKey(@Path("id") id: String)

    @GET("/api/v1/ai/providers/catalog")
    suspend fun aiCatalog(): List<AiCatalogEntry>

    @GET("/api/v1/ai/providers")
    suspend fun aiProviders(): List<AiProvider>

    @POST("/api/v1/ai/providers")
    suspend fun upsertAiProvider(@Body body: UpsertProviderRequest): AiProvider

    @PUT("/api/v1/ai/providers/{id}")
    suspend fun updateAiProvider(@Path("id") id: String, @Body body: UpsertProviderRequest): AiProvider

    @DELETE("/api/v1/ai/providers/{id}")
    suspend fun deleteAiProvider(@Path("id") id: String)

    @GET("/api/v1/ai/health")
    suspend fun aiHealth(): AiHealth

    @GET("/api/v1/alerts")
    suspend fun alerts(@Query("limit") limit: Int = 40): List<AlertItem>
}
