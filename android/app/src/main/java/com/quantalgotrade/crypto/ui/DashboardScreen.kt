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
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.Position
import com.quantalgotrade.crypto.data.Summary
import com.quantalgotrade.crypto.data.Wallet
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DashboardScreen(container: AppContainer) {
    var wallet by remember { mutableStateOf<Wallet?>(null) }
    var walletErr by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf<Summary?>(null) }
    var paper by remember { mutableStateOf<Summary?>(null) }
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick) {
        loading = true
        error = null
        try {
            live = container.api.summary("LIVE")
            paper = runCatching { container.api.summary("PAPER") }.getOrNull()
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
        HeroHeader("Dashboard", "LIVE wallet · paper progress · positions")
        TextButton(
            onClick = { scope.launch { tick++ } },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text("Refresh") }

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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(2.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("CoinDCX wallet", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        if (wallet != null) {
                            Text(
                                String.format(Locale.US, "%,.2f %s", wallet!!.available, wallet!!.currency),
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color(0xFF0F766E),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(wallet!!.source, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(walletErr ?: "No wallet", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                StatChip(
                    "LIVE PnL",
                    live?.let { String.format(Locale.US, "%,.2f", it.realizedPnl) } ?: "—",
                    if ((live?.realizedPnl ?: 0.0) >= 0) Color(0xFF059669) else Color(0xFFDC2626),
                )
            }
            item {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("LIVE summary", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        val s = live
                        if (s == null) Text("No summary") else {
                            StatRow("Open", s.openPositions.toString())
                            StatRow("Closed", s.closedPositions.toString())
                            StatRow("Win rate", String.format(Locale.US, "%.1f%%", s.winRate * 100))
                        }
                        if (paper != null) {
                            Spacer(Modifier.height(10.dp))
                            Text("Paper", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F766E))
                            StatRow("Paper closed", paper!!.closedPositions.toString())
                            StatRow("Paper PnL", String.format(Locale.US, "%,.2f", paper!!.realizedPnl))
                        }
                    }
                }
            }
            item { Text("LIVE positions", fontWeight = FontWeight.SemiBold) }
            if (positions.isEmpty()) {
                item { Text("No LIVE positions yet", color = MaterialTheme.colorScheme.onSurface.copy(0.6f)) }
            } else {
                items(positions, key = { it.id }) { p ->
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
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
