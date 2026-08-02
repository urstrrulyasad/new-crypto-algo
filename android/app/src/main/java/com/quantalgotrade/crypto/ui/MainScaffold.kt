package com.quantalgotrade.crypto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.quantalgotrade.crypto.data.AppContainer

@Composable
fun MainScaffold(container: AppContainer, onLoggedOut: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stack by remember { mutableStateOf(listOf<AppScreen>(AppScreen.Tabs)) }
    val labels = listOf("Home", "Strategies", "Paper", "Coins", "Settings")
    val icons = listOf(
        Icons.Filled.AccountBalanceWallet,
        Icons.Filled.CandlestickChart,
        Icons.AutoMirrored.Filled.ShowChart,
        Icons.Filled.CurrencyBitcoin,
        Icons.Filled.Settings,
    )

    fun push(screen: AppScreen) {
        stack = stack + screen
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val dest = stack.last()) {
        is AppScreen.StrategyDetail -> {
            StrategyDetailScreen(
                container = container,
                strategyId = dest.strategyId,
                onBack = { pop() },
                onOpenChart = { pair, strategyId ->
                    push(AppScreen.Chart(pair = pair, mode = "strategy", strategyId = strategyId))
                },
            )
        }
        is AppScreen.Chart -> {
            ChartScreen(
                container = container,
                pair = dest.pair,
                mode = dest.mode,
                strategyId = dest.strategyId,
                positionId = dest.positionId,
                onBack = { pop() },
            )
        }
        AppScreen.Tabs -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(icons[index], contentDescription = label) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val forward = targetState > initialState
                            val enter = fadeIn(tween(280)) + slideInHorizontally(tween(320)) {
                                if (forward) it / 6 else -it / 6
                            }
                            val exit = fadeOut(tween(200)) + slideOutHorizontally(tween(260)) {
                                if (forward) -it / 8 else it / 8
                            }
                            enter togetherWith exit
                        },
                        label = "tab",
                    ) { current ->
                        when (current) {
                            0 -> DashboardScreen(
                                container = container,
                                onOpenChart = { pair, positionId ->
                                    push(AppScreen.Chart(pair = pair, mode = "live", positionId = positionId))
                                },
                            )
                            1 -> StrategiesScreen(
                                container = container,
                                onOpenStrategy = { id -> push(AppScreen.StrategyDetail(id)) },
                            )
                            2 -> PaperScreen(
                                container = container,
                                onOpenChart = { pair, positionId ->
                                    push(AppScreen.Chart(pair = pair, mode = "paper", positionId = positionId))
                                },
                            )
                            3 -> CoinsScreen(
                                container = container,
                                onOpenChart = { pair ->
                                    push(AppScreen.Chart(pair = pair, mode = "clean"))
                                },
                            )
                            else -> SettingsScreen(container, onLoggedOut)
                        }
                    }
                }
            }
        }
    }
}
