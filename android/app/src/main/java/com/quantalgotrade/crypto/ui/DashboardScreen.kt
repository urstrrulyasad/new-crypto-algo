package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.Position
import com.quantalgotrade.crypto.data.Summary
import com.quantalgotrade.crypto.data.Wallet
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(container: AppContainer) {
    var wallet by remember { mutableStateOf<Wallet?>(null) }
    var walletErr by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<Summary?>(null) }
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadTick) {
        loading = true
        error = null
        try {
            summary = container.api.summary("LIVE")
            positions = container.api.positions("LIVE")
            try {
                wallet = container.api.wallet()
                walletErr = null
            } catch (e: Exception) {
                wallet = null
                walletErr = e.message ?: "Wallet unavailable"
            }
        } catch (e: Exception) {
            error = e.message ?: "Failed to load dashboard"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Dashboard") },
            actions = {
                TextButton(onClick = { scope.launch { reloadTick++ } }) {
                    Text("Refresh")
                }
            },
        )
        if (loading) {
            Column(Modifier.padding(24.dp)) { CircularProgressIndicator() }
            return
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("CoinDCX wallet", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        if (wallet != null) {
                            Text(
                                String.format(Locale.US, "%,.2f %s", wallet!!.available, wallet!!.currency),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(wallet!!.source, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(walletErr ?: "No wallet", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("LIVE summary", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        val s = summary
                        if (s == null) {
                            Text("No summary")
                        } else {
                            StatRow("Open", s.openPositions.toString())
                            StatRow("Closed", s.closedPositions.toString())
                            StatRow("Realized PnL", String.format(Locale.US, "%,.2f", s.realizedPnl))
                            StatRow("Win rate", String.format(Locale.US, "%.1f%%", s.winRate * 100))
                        }
                    }
                }
            }
            item {
                Text("LIVE positions", fontWeight = FontWeight.SemiBold)
            }
            if (positions.isEmpty()) {
                item { Text("No LIVE positions") }
            } else {
                items(positions, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${p.pair} · ${p.side}", fontWeight = FontWeight.Medium)
                            Text("Qty ${p.quantity} @ ${p.entryPrice}")
                            Text("Status ${p.status}")
                            p.realizedPnl?.let {
                                Text("PnL ${String.format(Locale.US, "%,.2f", it)}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
