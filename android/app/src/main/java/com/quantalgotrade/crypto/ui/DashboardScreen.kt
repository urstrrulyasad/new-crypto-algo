package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.quantalgotrade.crypto.data.TradeOrder
import com.quantalgotrade.crypto.data.Wallet
import kotlinx.coroutines.launch
import java.util.Locale

private enum class OrderFilter { All, Pending, Success, Failed }

@Composable
fun DashboardScreen(
    container: AppContainer,
    onOpenChart: (pair: String, positionId: String) -> Unit = { _, _ -> },
) {
    var wallet by remember { mutableStateOf<Wallet?>(null) }
    var walletErr by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf<Summary?>(null) }
    var paper by remember { mutableStateOf<Summary?>(null) }
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var orders by remember { mutableStateOf<List<TradeOrder>>(emptyList()) }
    var orderFilter by remember { mutableStateOf(OrderFilter.All) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    suspend fun reload() {
        error = null
        try {
            live = container.api.summary("LIVE")
            paper = runCatching { container.api.summary("PAPER") }.getOrNull()
            positions = container.api.positions("LIVE")
            orders = runCatching { container.api.orders("LIVE") }.getOrDefault(emptyList())
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
            initialLoading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val pending = orders.filter { isPendingOrder(it) }
    val success = orders.filter { isSuccessOrder(it) }
    val failed = orders.filter { isFailedOrder(it) }
    val filtered = when (orderFilter) {
        OrderFilter.All -> orders
        OrderFilter.Pending -> pending
        OrderFilter.Success -> success
        OrderFilter.Failed -> failed
    }

    Column(Modifier.fillMaxSize()) {
        HeroHeader("Dashboard", "LIVE wallet · positions · orders")
        PullRefreshColumn(
            refreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; reload() } },
            initialLoading = initialLoading,
            error = error,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "wallet") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("CoinDCX wallet", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            if (wallet != null) {
                                Text(
                                    String.format(Locale.US, "%,.2f %s", wallet!!.available, wallet!!.currency),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = scheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(wallet!!.source, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(walletErr ?: "No wallet", color = scheme.error)
                            }
                        }
                    }
                }
                item(key = "pnl") {
                    StatChip(
                        "LIVE PnL",
                        live?.let { String.format(Locale.US, "%,.2f", it.realizedPnl) } ?: "—",
                        if ((live?.realizedPnl ?: 0.0) >= 0) scheme.secondary else scheme.error,
                    )
                }
                item(key = "summary") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("LIVE summary", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            val s = live
                            if (s == null) {
                                Text("No summary", color = scheme.onSurfaceVariant)
                            } else {
                                StatRow("Open", s.openPositions.toString())
                                StatRow("Closed", s.closedPositions.toString())
                                StatRow("Win rate", String.format(Locale.US, "%.1f%%", s.winRate * 100))
                            }
                            if (paper != null) {
                                Spacer(Modifier.height(10.dp))
                                Text("Paper", fontWeight = FontWeight.SemiBold, color = scheme.secondary)
                                StatRow("Paper closed", paper!!.closedPositions.toString())
                                StatRow("Paper PnL", String.format(Locale.US, "%,.2f", paper!!.realizedPnl))
                            }
                        }
                    }
                }
                item(key = "pos-h") { Text("LIVE positions", fontWeight = FontWeight.SemiBold) }
                if (positions.isEmpty()) {
                    item(key = "pos-empty") {
                        Text("No LIVE positions yet", color = scheme.onSurfaceVariant)
                    }
                } else {
                    items(positions, key = { "live-${it.id}" }) { p ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChart(p.pair, p.id) },
                            colors = CardDefaults.cardColors(containerColor = scheme.surface),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${p.pair} · ${p.side}", fontWeight = FontWeight.Medium)
                                    Text(p.status, color = scheme.onSurfaceVariant)
                                }
                                Text(String.format(Locale.US, "Entry ₹%,.6f", p.entryPrice), color = scheme.onSurfaceVariant)
                                Text(
                                    if (p.exitPrice != null) String.format(Locale.US, "Exit ₹%,.6f", p.exitPrice)
                                    else "Exit —",
                                    color = scheme.onSurfaceVariant,
                                )
                                Text("Qty ${p.quantity}", color = scheme.onSurfaceVariant)
                                Text(
                                    "Opened ${formatDateTime(p.openedAt)}",
                                    color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Closed ${formatDateTime(p.closedAt)}",
                                    color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                p.realizedPnl?.let {
                                    Text(
                                        String.format(Locale.US, "PnL ₹%,.2f", it),
                                        color = if (it >= 0) scheme.secondary else scheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "ord-h") {
                    Spacer(Modifier.height(4.dp))
                    Text("LIVE orders", fontWeight = FontWeight.SemiBold)
                }
                item(key = "ord-chips") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrderChip("All", orders.size, orderFilter == OrderFilter.All, scheme) {
                            orderFilter = OrderFilter.All
                        }
                        OrderChip("Pending", pending.size, orderFilter == OrderFilter.Pending, scheme) {
                            orderFilter = OrderFilter.Pending
                        }
                        OrderChip("Success", success.size, orderFilter == OrderFilter.Success, scheme) {
                            orderFilter = OrderFilter.Success
                        }
                        OrderChip("Failed", failed.size, orderFilter == OrderFilter.Failed, scheme) {
                            orderFilter = OrderFilter.Failed
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item(key = "ord-empty") {
                        Text("No ${orderFilter.name.lowercase()} LIVE orders", color = scheme.onSurfaceVariant)
                    }
                } else {
                    items(filtered.take(40), key = { "ord-${it.id}" }) { o ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChart(o.pair, o.id) },
                            colors = CardDefaults.cardColors(containerColor = scheme.surface),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${o.pair} · ${o.side}", fontWeight = FontWeight.Medium)
                                    Text(
                                        o.status,
                                        color = when {
                                            isFailedOrder(o) -> scheme.error
                                            isSuccessOrder(o) -> scheme.secondary
                                            else -> scheme.primary
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text("Qty ${o.quantity}", color = scheme.onSurfaceVariant)
                                val detail = o.error?.takeIf { it.isNotBlank() }
                                    ?: o.price?.let { String.format(Locale.US, "₹%,.6f", it) }
                                    ?: "—"
                                Text(detail, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Order ${formatDateTime(o.createdAt)}",
                                    color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderChip(
    label: String,
    count: Int,
    selected: Boolean,
    scheme: androidx.compose.material3.ColorScheme,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$label · $count") },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = scheme.primary.copy(alpha = 0.22f),
            selectedLabelColor = scheme.primary,
        ),
    )
}

private fun isFailedOrder(o: TradeOrder): Boolean {
    val s = o.status.uppercase(Locale.US)
    return s == "FAILED" || s == "REJECTED" || s == "CANCELLED"
}

private fun isSuccessOrder(o: TradeOrder): Boolean =
    o.status.uppercase(Locale.US) == "FILLED"

private fun isPendingOrder(o: TradeOrder): Boolean =
    !isFailedOrder(o) && !isSuccessOrder(o)
