package com.quantalgotrade.crypto.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserInfo,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class UserInfo(
    val id: String,
    val tenantId: String,
    val email: String,
    val displayName: String? = null,
    val role: String,
)

@Serializable
data class Wallet(
    val currency: String,
    val available: Double,
    val source: String,
)

@Serializable
data class Summary(
    val mode: String? = null,
    val openPositions: Int = 0,
    val closedPositions: Int = 0,
    val realizedPnl: Double = 0.0,
    val unrealizedPnl: Double = 0.0,
    val winRate: Double = 0.0,
)

@Serializable
data class Position(
    val id: String,
    val pair: String,
    val side: String,
    val quantity: Double,
    val entryPrice: Double,
    val exitPrice: Double? = null,
    val status: String,
    val realizedPnl: Double? = null,
    val openedAt: String? = null,
)

@Serializable
data class PaperProgress(
    val closedTrades: Long = 0,
    val wins: Long = 0,
    val winRate: Double = 0.0,
    val totalPnl: Double = 0.0,
    val requiredTrades: Int = 0,
    val requiredWinRate: Double = 0.0,
)

@Serializable
data class Strategy(
    val id: String,
    val name: String,
    val status: String,
    val origin: String? = null,
    val instrument: String? = null,
    val marketType: String? = null,
    val marginCurrency: String? = null,
    val createdAt: String? = null,
    val sourceCode: String? = null,
    val config: JsonObject? = null,
    val paper: PaperProgress? = null,
)

@Serializable
data class StrategyTrade(
    val id: String,
    val mode: String? = null,
    val pair: String,
    val side: String,
    val quantity: Double = 0.0,
    val entryPrice: Double = 0.0,
    val exitPrice: Double? = null,
    val status: String,
    val realizedPnl: Double? = null,
    val openedAt: String? = null,
    val closedAt: String? = null,
)

@Serializable
data class InstrumentsResponse(
    val instruments: List<String> = emptyList(),
)

@Serializable
data class ApiError(val message: String? = null, val status: Int? = null)
