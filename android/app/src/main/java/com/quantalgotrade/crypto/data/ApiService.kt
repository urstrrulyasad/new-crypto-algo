package com.quantalgotrade.crypto.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
}
