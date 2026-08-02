package com.quantalgotrade.crypto.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
}
