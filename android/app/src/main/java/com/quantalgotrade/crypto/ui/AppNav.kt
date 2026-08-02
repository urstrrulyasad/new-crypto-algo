package com.quantalgotrade.crypto.ui

sealed class AppScreen {
    data object Tabs : AppScreen()
    data class StrategyDetail(val strategyId: String) : AppScreen()
    data class Chart(
        val pair: String,
        val mode: String = "clean",
        val strategyId: String? = null,
        val positionId: String? = null,
    ) : AppScreen()
}
