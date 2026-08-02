package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.quantalgotrade.crypto.data.Strategy
import com.quantalgotrade.crypto.data.StrategyTrade
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun PaperScreen(container: AppContainer) {
    var strategies by remember { mutableStateOf<List<Strategy>>(emptyList()) }
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var recent by remember { mutableStateOf<List<StrategyTrade>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick) {
        loading = true
        error = null
        try {
            strategies = container.api.strategies("FUTURES")
                .filter { it.status == "PAPER_TRADING" || it.status == "LIVE_APPROVED" }
            positions = container.api.positions("PAPER")
            recent = strategies.take(5).flatMap { s ->
                runCatching { container.api.strategyTrades(s.id, "PAPER") }.getOrDefault(emptyList())
            }.sortedByDescending { it.openedAt }.take(40)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load paper"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        HeroHeader("Paper trading", "Simulated fills at live CoinDCX prices")
        TextButton(onClick = { scope.launch { tick++ } }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Refresh")
        }
        if (loading) {
            Column(Modifier.padding(24.dp)) { CircularProgressIndicator() }
            return
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("${strategies.size} paper/LIVE strategies", fontWeight = FontWeight.SemiBold)
            }
            items(strategies, key = { it.id }) { s ->
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.name, fontWeight = FontWeight.Medium)
                        val p = s.paper
                        if (p != null) {
                            Text(
                                String.format(
                                    Locale.US,
                                    "%d trades · WR %.0f%% · PnL %.2f",
                                    p.closedTrades,
                                    p.winRate * 100,
                                    p.totalPnl,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Text("Open paper positions", fontWeight = FontWeight.SemiBold)
            }
            if (positions.isEmpty()) {
                item { Text("No open paper positions") }
            } else {
                items(positions, key = { it.id }) { p ->
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${p.pair} · ${p.side}", fontWeight = FontWeight.Medium)
                            Text("Qty ${p.quantity} @ ${p.entryPrice}")
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Text("Recent paper trades", fontWeight = FontWeight.SemiBold)
            }
            items(recent, key = { it.id }) { t ->
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${t.pair} · ${t.side} · ${t.status}", fontWeight = FontWeight.Medium)
                        Text(
                            String.format(
                                Locale.US,
                                "Entry %.4f → Exit %s · PnL %s",
                                t.entryPrice,
                                t.exitPrice?.toString() ?: "—",
                                t.realizedPnl?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
