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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    val openLive = positions.count { it.status.equals("OPEN", ignoreCase = true) }

    LaunchedEffect(openLive) {
        reload()
        val ms = if (openLive > 0) 2_000L else 12_000L
        while (isActive) {
            delay(ms)
            reload()
        }
    }

    val pending = orders.filter { isPendingOrder(it) }
    val success = orders.filter { isSuccessOrder(it) }
    val failed = orders.filter { isFailedOrder(it) }
    val filtered = when (orderFilter) {
        OrderFilter.All -> orders
        OrderFilter.Pending -> pending
        OrderFilter.Success -> success
        OrderFilter.Failed -> failed
    }

    val walletBalance = wallet?.let {
        it.walletBalance ?: it.walletEquity ?: (it.available + (it.locked ?: 0.0))
    }
    val activePnl = wallet?.activePnl ?: live?.unrealizedPnl
    val currentValue = wallet?.currentValue
        ?: walletBalance?.let { it + (activePnl ?: 0.0) }
    val estTotal = wallet?.estTotalFutures
        ?: currentValue?.let { it + (wallet?.usdtValueInr ?: 0.0) }

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
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("CoinDCX wallet", fontWeight = FontWeight.SemiBold)
                            if (wallet != null) {
                                WalletRow("Available", fmtInr(wallet!!.available, signed = false), scheme.primary)
                                WalletRow("Locked", fmtInr(wallet!!.locked, signed = false))
                                WalletRow("Wallet balance", fmtInr(walletBalance, signed = false))
                                WalletRow(
                                    "Active PnL",
                                    fmtInr(activePnl),
                                    if ((activePnl ?: 0.0) >= 0) scheme.secondary else scheme.error,
                                )
                                WalletRow("Current value", fmtInr(currentValue, signed = false), scheme.primary)
                                WalletRow("Est. futures", fmtInr(estTotal, signed = false))
                                Text(wallet!!.source, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            } else {
                                Text(walletErr ?: "No wallet", color = scheme.error)
                            }
                        }
                    }
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
                                StatRow("Realized PnL", fmtInr(s.realizedPnl))
                                StatRow("Win rate", String.format(Locale.US, "%.1f%%", s.winRate * 100))
                            }
                            if (paper != null) {
                                Spacer(Modifier.height(10.dp))
                                Text("Paper", fontWeight = FontWeight.SemiBold, color = scheme.secondary)
                                StatRow("Paper closed", paper!!.closedPositions.toString())
                                StatRow("Paper PnL", fmtInr(paper!!.realizedPnl))
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
                        val openPnl = p.pnl ?: p.unrealizedPnl ?: p.realizedPnl
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChart(p.pair, p.id) },
                            colors = CardDefaults.cardColors(containerColor = scheme.surface),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        "${p.pair} · ${p.side}${p.leverage?.let { " · ${it.toInt()}x" } ?: ""}",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(p.status, color = scheme.onSurfaceVariant)
                                }
                                if (p.status.equals("OPEN", ignoreCase = true)) {
                                    Text(
                                        "Active PnL ${fmtInr(openPnl)}${p.roePct?.let { " (${String.format(Locale.US, "%+.2f%%", it)})" } ?: ""}",
                                        color = if ((openPnl ?: 0.0) >= 0) scheme.secondary else scheme.error,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text("Margin ${fmtInr(p.marginInr, signed = false)} · Size ${fmtInr(p.sizeInr, signed = false)}", color = scheme.onSurfaceVariant)
                                    Text("Entry ${fmtPrice(p.entryPrice)} · Mark ${fmtPrice(p.markPrice)}", color = scheme.onSurfaceVariant)
                                    Text("SL ${fmtPrice(p.slPrice)} · TP ${fmtPrice(p.targetPrice)}", color = scheme.onSurfaceVariant)
                                } else {
                                    Text("Entry ${fmtPrice(p.entryPrice)}", color = scheme.onSurfaceVariant)
                                    p.exitPrice?.let { Text("Exit ${fmtPrice(it)}", color = scheme.onSurfaceVariant) }
                                    openPnl?.let {
                                        Text(
                                            "PnL ${fmtInr(it)}",
                                            color = if (it >= 0) scheme.secondary else scheme.error,
                                        )
                                    }
                                }
                                Text(
                                    "Opened ${formatDateTime(p.openedAt)}",
                                    color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
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
                                val size = o.sizeInr?.let { fmtInr(it, signed = false) }
                                Text(
                                    listOfNotNull(
                                        size?.let { "Size $it" },
                                        "Qty ${o.quantity}",
                                    ).joinToString(" · "),
                                    color = scheme.onSurfaceVariant,
                                )
                                val detail = o.error?.takeIf { it.isNotBlank() }
                                    ?: o.avgPrice?.let { fmtPrice(it) }
                                    ?: o.price?.let { fmtPrice(it) }
                                    ?: "—"
                                Text(detail, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                o.pnl?.let {
                                    Text(
                                        "PnL ${fmtInr(it)}",
                                        color = if (it >= 0) scheme.secondary else scheme.error,
                                    )
                                }
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
private fun WalletRow(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = color, fontWeight = FontWeight.SemiBold)
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

private fun fmtInr(v: Double?, signed: Boolean = true): String {
    if (v == null) return "—"
    val abs = String.format(Locale.US, "%,.2f", kotlin.math.abs(v))
    return when {
        !signed -> "₹$abs"
        v > 0 -> "+₹$abs"
        v < 0 -> "-₹$abs"
        else -> "₹$abs"
    }
}

private fun fmtPrice(v: Double?): String {
    if (v == null || v <= 0) return "—"
    return when {
        v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 1 -> String.format(Locale.US, "%.4f", v)
        v >= 0.01 -> String.format(Locale.US, "%.6f", v)
        else -> String.format(Locale.US, "%.8f", v)
    }
}

private fun isFailedOrder(o: TradeOrder): Boolean {
    val s = o.status.uppercase(Locale.US)
    return s == "FAILED" || s == "REJECTED" || s == "CANCELLED"
}

private fun isSuccessOrder(o: TradeOrder): Boolean =
    o.status.uppercase(Locale.US) == "FILLED"

private fun isPendingOrder(o: TradeOrder): Boolean =
    !isFailedOrder(o) && !isSuccessOrder(o)
