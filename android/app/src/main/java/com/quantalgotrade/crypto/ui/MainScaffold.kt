package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.quantalgotrade.crypto.data.AppContainer

@Composable
fun MainScaffold(container: AppContainer, onLoggedOut: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("Dashboard", "Strategies", "Settings")
    val icons = listOf(
        Icons.Filled.AccountBalanceWallet,
        Icons.Filled.CandlestickChart,
        Icons.Filled.Settings,
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icons[index], contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> DashboardScreen(container)
                1 -> StrategiesScreen(container)
                else -> SettingsScreen(container, onLoggedOut)
            }
        }
    }
}
