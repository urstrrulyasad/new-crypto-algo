package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.ExchangeKey
import com.quantalgotrade.crypto.data.Position
import com.quantalgotrade.crypto.data.Summary
import com.quantalgotrade.crypto.data.TradeOrder
import com.quantalgotrade.crypto.data.Wallet
import com.quantalgotrade.crypto.ui.theme.QuantColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

private enum class OrderFilter { All, Pending, Success, Failed }

@Composable
fun DashboardScreen(
    container: AppContainer,
    onOpenChart: (pair: String, positionId: String) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {},
    onOpenPaper: () -> Unit = {},
) {
    var keys by remember { mutableStateOf<List<ExchangeKey>?>(null) }
    var wallet by remember { mutableStateOf<Wallet?>(null) }
    var walletErr by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf<Summary?>(null) }
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var orders by remember { mutableStateOf<List<TradeOrder>>(emptyList()) }
    var orderFilter by remember { mutableStateOf(OrderFilter.All) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshSignal by remember { mutableIntStateOf(0) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(refreshSignal) {
        while (true) {
            error = null
            try {
                val keyList = container.api.keys()
                keys = keyList
                val active = keyList.any { it.status.equals("ACTIVE", ignoreCase = true) }
                if (!active) {
                    wallet = null
                    walletErr = null
                    live = null
                    positions = emptyList()
                    orders = emptyList()
                } else {
                    live = container.api.summary("LIVE")
                    positions = container.api.positions("LIVE")
                    orders = runCatching { container.api.orders("LIVE") }.getOrDefault(emptyList())
                    try {
                        wallet = container.api.wallet()
                        walletErr = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        wallet = null
                        walletErr = e.message ?: "Wallet unavailable"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Failed to load Real Trades"
            } finally {
                initialLoading = false
                refreshing = false
            }
            val openCount = positions.count { it.status.equals("OPEN", ignoreCase = true) }
            delay(if (openCount > 0) 2_500L else 12_000L)
        }
    }

    val hasActiveKey = keys?.any { it.status.equals("ACTIVE", ignoreCase = true) } == true
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
        HeroHeader(
            title = "Real Trades (LIVE)",
            subtitle = "CoinDCX INR equity · verified LIVE positions · orders",
            kicker = "Live CoinDCX Account",
        )
        PullRefreshColumn(
            refreshing = refreshing,
            onRefresh = {
                refreshing = true
                refreshSignal++
            },
            initialLoading = initialLoading,
            error = error,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (keys != null && !hasActiveKey) {
                    item(key = "api-key-onboarding") {
                        DeskCard(modifier = Modifier.animateItem()) {
                            StatusTag(text = "API KEY REQUIRED", accent = QuantColors.GoldBright)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "To get started, add your CoinDCX API key and secret",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = QuantColors.TextPrimary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "No active CoinDCX API credentials are configured, so no false LIVE positions or balances are shown. Add your CoinDCX API key and secret to trade live, or use the separate Paper tab for risk-free simulation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = QuantColors.TextMuted,
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = onOpenSettings,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = QuantColors.GoldBright,
                                    contentColor = QuantColors.Canvas,
                                ),
                            ) {
                                Text("Add CoinDCX API Key & Secret", fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onOpenPaper,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, QuantColors.Hairline),
                            ) {
                                Text("Open Paper Trading", color = QuantColors.TextPrimary)
                            }
                        }
                    }
                } else {
                    item(key = "wallet") {
                        DeskCard(modifier = Modifier.animateItem()) {
                            Text(
                                "EST. FUTURES EQUITY",
                                style = MaterialTheme.typography.labelMedium,
                                color = QuantColors.TextMuted,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (wallet != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    Text(
                                        fmtInr(estTotal, signed = false),
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = QuantColors.TextPrimary,
                                    )
                                    StatusTag(
                                        text = fmtInr(activePnl),
                                        accent = if ((activePnl ?: 0.0) >= 0) QuantColors.Emerald else QuantColors.Rose,
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                val curvePoints = remember(positions) {
                                    val sorted = positions.sortedBy { it.openedAt }
                                    if (sorted.isEmpty()) emptyList() else buildList {
                                        add(0.0)
                                        var cum = 0.0
                                        sorted.forEach { pos ->
                                            val delta = if (pos.status.equals("CLOSED", true)) {
                                                pos.realizedPnl ?: 0.0
                                            } else {
                                                pos.pnl ?: pos.unrealizedPnl ?: 0.0
                                            }
                                            cum += delta
                                            add(cum)
                                        }
                                    }
                                }
                                if (curvePoints.size >= 2) {
                                    Text(
                                        "SESSION PNL TRAJECTORY",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = QuantColors.TextMuted,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    EquitySparkline(curvePoints)
                                    Spacer(Modifier.height(10.dp))
                                }
                                HorizontalDivider(thickness = 1.dp, color = QuantColors.Hairline)
                                Spacer(Modifier.height(10.dp))
                                StatRow("Available", fmtInr(wallet!!.available, signed = false), QuantColors.GoldBright)
                                StatRow("Locked Margin", fmtInr(wallet!!.locked, signed = false))
                                StatRow("Wallet Balance", fmtInr(walletBalance, signed = false))
                                StatRow("Current Value", fmtInr(currentValue, signed = false), QuantColors.GoldBright)
                                if (wallet!!.source.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(wallet!!.source, style = MaterialTheme.typography.bodySmall, color = QuantColors.TextMuted)
                                }
                            } else {
                                Text(walletErr ?: "Wallet unavailable", color = scheme.error)
                            }
                        }
                    }

                    item(key = "summary-grid") {
                        val s = live
                        Column(
                            modifier = Modifier.animateItem(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatTile(
                                    label = "Open LIVE",
                                    value = s?.openPositions?.toString() ?: "—",
                                    accent = QuantColors.Cyan,
                                    modifier = Modifier.weight(1f),
                                )
                                StatTile(
                                    label = "Closed LIVE",
                                    value = s?.closedPositions?.toString() ?: "—",
                                    accent = QuantColors.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatTile(
                                    label = "Realized PnL",
                                    value = fmtInr(s?.realizedPnl),
                                    accent = if ((s?.realizedPnl ?: 0.0) >= 0) QuantColors.Emerald else QuantColors.Rose,
                                    modifier = Modifier.weight(1f),
                                )
                                StatTile(
                                    label = "LIVE Win Rate",
                                    value = s?.let { String.format(Locale.US, "%.1f%%", it.winRate * 100) } ?: "—",
                                    accent = if ((s?.winRate ?: 0.0) >= 0.6) QuantColors.Emerald else QuantColors.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    item(key = "pos-h") {
                        Text(
                            "LIVE POSITIONS (${positions.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = QuantColors.TextMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    if (positions.isEmpty()) {
                        item(key = "pos-empty") {
                            DeskCard {
                                Text("No LIVE positions on CoinDCX.", color = QuantColors.TextMuted)
                            }
                        }
                    } else {
                    items(positions, key = { "live-${it.id}" }) { p ->
                        val openPnl = p.pnl ?: p.unrealizedPnl ?: p.realizedPnl
                        val sideAccent = if (p.side.equals("LONG", true)) QuantColors.Emerald else QuantColors.Rose
                        DeskCard(
                            modifier = Modifier.animateItem(),
                            onClick = { onOpenChart(p.pair, p.id) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.pair, fontWeight = FontWeight.Bold, color = QuantColors.TextPrimary)
                                    StatusTag(
                                        text = "${p.side}${p.leverage?.let { " ${it.toInt()}x" } ?: ""}",
                                        accent = sideAccent,
                                    )
                                }
                                StatusTag(
                                    text = p.status,
                                    accent = if (p.status.equals("OPEN", true)) QuantColors.Cyan else QuantColors.TextMuted,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            if (p.status.equals("OPEN", ignoreCase = true)) {
                                Text(
                                    "Active PnL ${fmtInr(openPnl)}${p.roePct?.let { " (${String.format(Locale.US, "%+.2f%%", it)})" } ?: ""}",
                                    color = if ((openPnl ?: 0.0) >= 0) QuantColors.Emerald else QuantColors.Rose,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(6.dp))
                                StatRow("Entry / Mark", "${fmtPrice(p.entryPrice)} / ${fmtPrice(p.markPrice)}")
                                StatRow("Margin / Size", "${fmtInr(p.marginInr, false)} / ${fmtInr(p.sizeInr, false)}")
                                StatRow("SL / TP", "${fmtPrice(p.slPrice)} / ${fmtPrice(p.targetPrice)}")
                            } else {
                                StatRow("Entry / Exit", "${fmtPrice(p.entryPrice)} / ${fmtPrice(p.exitPrice)}")
                                openPnl?.let {
                                    StatRow(
                                        "Realized PnL",
                                        fmtInr(it),
                                        if (it >= 0) QuantColors.Emerald else QuantColors.Rose,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Opened ${formatDateTime(p.openedAt)}",
                                color = QuantColors.TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                item(key = "ord-h") {
                    Text(
                        "LIVE ORDERS",
                        style = MaterialTheme.typography.labelMedium,
                        color = QuantColors.TextMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                item(key = "ord-tabs") {
                    SegmentedTabBar(
                        options = listOf(
                            "All (${orders.size})",
                            "Pending (${pending.size})",
                            "Filled (${success.size})",
                            "Failed (${failed.size})",
                        ),
                        selectedIndex = OrderFilter.entries.indexOf(orderFilter),
                        onSelect = { idx -> orderFilter = OrderFilter.entries[idx] },
                    )
                }

                if (filtered.isEmpty()) {
                    item(key = "ord-empty") {
                        DeskCard {
                            Text("No ${orderFilter.name.lowercase()} LIVE orders.", color = QuantColors.TextMuted)
                        }
                    }
                } else {
                    items(filtered.take(40), key = { "ord-${it.id}" }) { o ->
                        val statusAccent = when {
                            isFailedOrder(o) -> QuantColors.Rose
                            isSuccessOrder(o) -> QuantColors.Emerald
                            else -> QuantColors.Amber
                        }
                        DeskCard(
                            modifier = Modifier.animateItem(),
                            onClick = { onOpenChart(o.pair, o.id) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${o.pair} · ${o.side}",
                                    fontWeight = FontWeight.SemiBold,
                                    color = QuantColors.TextPrimary,
                                )
                                StatusTag(text = o.status, accent = statusAccent)
                            }
                            Spacer(Modifier.height(6.dp))
                            val size = o.sizeInr?.let { fmtInr(it, signed = false) }
                            val fillPrice = o.avgPrice?.let { fmtPrice(it) } ?: o.price?.let { fmtPrice(it) } ?: "—"
                            StatRow("Avg Fill / Qty", "$fillPrice · ${o.quantity}")
                            if (size != null) StatRow("Notional Size", size)
                            if (!o.error.isNullOrBlank()) {
                                Text(o.error, color = QuantColors.Rose, style = MaterialTheme.typography.bodySmall)
                            }
                            o.pnl?.let {
                                StatRow(
                                    "Order PnL",
                                    fmtInr(it),
                                    if (it >= 0) QuantColors.Emerald else QuantColors.Rose,
                                )
                            }
                            Text(
                                formatDateTime(o.createdAt),
                                color = QuantColors.TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                }
            }
        }
    }
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
