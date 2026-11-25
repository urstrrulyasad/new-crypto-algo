package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.PaperOrderRequest
import com.quantalgotrade.crypto.data.Position
import com.quantalgotrade.crypto.data.TradeOrder
import com.quantalgotrade.crypto.ui.theme.QuantColors
import kotlinx.coroutines.launch
import java.util.Locale

private val PAPER_PAIRS = listOf("B-BTC_USDT", "B-ETH_USDT", "B-SOL_USDT", "B-XRP_USDT")

@Composable
fun PaperScreen(
    container: AppContainer,
    onOpenChart: (pair: String, positionId: String) -> Unit = { _, _ -> },
) {
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var orders by remember { mutableStateOf<List<TradeOrder>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var showTicket by remember { mutableStateOf(false) }
    var pairIdx by remember { mutableIntStateOf(0) }
    var sideIdx by remember { mutableIntStateOf(0) }
    var qtyInput by remember { mutableStateOf("0.01") }
    var placing by remember { mutableStateOf(false) }
    var closingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        error = null
        try {
            positions = container.api.positions("PAPER")
            orders = runCatching { container.api.orders("PAPER") }.getOrDefault(emptyList())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load paper trading"
        } finally {
            initialLoading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val open = positions.filter { it.status == "OPEN" }
    val closed = positions.filter { it.status != "OPEN" }
    val shownPositions = if (tab == 0) open else closed
    val totalClosedPnl = closed.sumOf { it.realizedPnl ?: 0.0 }
    val totalOpenPnl = open.sumOf { it.pnl ?: it.unrealizedPnl ?: 0.0 }

    Column(Modifier.fillMaxSize()) {
        HeroHeader(
            title = "Paper Trading",
            subtitle = "Isolated simulated trades at live CoinDCX mark prices",
            kicker = "Zero Real Funds",
        )
        PullRefreshColumn(
            refreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; reload() } },
            initialLoading = initialLoading,
            error = error,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "stats") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(label = "Open", value = open.size.toString(), accent = QuantColors.Cyan, modifier = Modifier.weight(1f))
                            StatTile(label = "Closed", value = closed.size.toString(), modifier = Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(
                                label = "Unrealized",
                                value = String.format(Locale.US, "%s₹%.0f", if (totalOpenPnl >= 0) "+" else "-", kotlin.math.abs(totalOpenPnl)),
                                accent = if (totalOpenPnl >= 0) QuantColors.Emerald else QuantColors.Rose,
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = "Realized",
                                value = String.format(Locale.US, "%s₹%.0f", if (totalClosedPnl >= 0) "+" else "-", kotlin.math.abs(totalClosedPnl)),
                                accent = if (totalClosedPnl >= 0) QuantColors.Emerald else QuantColors.Rose,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                item(key = "ticket-toggle") {
                    DeskCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Simulated Paper Order", fontWeight = FontWeight.SemiBold, color = QuantColors.TextPrimary)
                                Text(
                                    "Executes at live CoinDCX price without touching real balance",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = QuantColors.TextMuted,
                                )
                            }
                            TextButton(onClick = { showTicket = !showTicket }) {
                                Text(if (showTicket) "Hide" else "+ Trade", color = QuantColors.Cyan)
                            }
                        }
                        if (showTicket) {
                            Spacer(Modifier.height(10.dp))
                            SegmentedTabBar(
                                options = listOf("BTC", "ETH", "SOL", "XRP"),
                                selectedIndex = pairIdx,
                                onSelect = { pairIdx = it },
                            )
                            Spacer(Modifier.height(8.dp))
                            SegmentedTabBar(
                                options = listOf("LONG", "SHORT"),
                                selectedIndex = sideIdx,
                                onSelect = { sideIdx = it },
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = qtyInput,
                                onValueChange = { qtyInput = it },
                                label = { Text("Quantity") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = QuantColors.Cyan,
                                    unfocusedBorderColor = QuantColors.Hairline,
                                    focusedContainerColor = QuantColors.Canvas,
                                    unfocusedContainerColor = QuantColors.Canvas,
                                ),
                            )
                            Spacer(Modifier.height(10.dp))
                            val qtyVal = qtyInput.toDoubleOrNull() ?: 0.0
                            val chosenSide = if (sideIdx == 0) "LONG" else "SHORT"
                            Button(
                                onClick = {
                                    scope.launch {
                                        placing = true
                                        error = null
                                        try {
                                            container.api.placePaperOrder(
                                                PaperOrderRequest(
                                                    pair = PAPER_PAIRS[pairIdx],
                                                    side = chosenSide,
                                                    quantity = qtyVal,
                                                    leverage = 3,
                                                ),
                                            )
                                            showTicket = false
                                            reload()
                                        } catch (e: Exception) {
                                            error = e.message ?: "Paper order failed"
                                        } finally {
                                            placing = false
                                        }
                                    }
                                },
                                enabled = !placing && qtyVal > 0.0,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = QuantColors.Cyan,
                                    contentColor = QuantColors.Canvas,
                                ),
                            ) {
                                Text(if (placing) "Executing…" else "Open Paper $chosenSide", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                item(key = "tabs") {
                    SegmentedTabBar(
                        options = listOf("Open (${open.size})", "Closed (${closed.size})", "Orders (${orders.size})"),
                        selectedIndex = tab,
                        onSelect = { tab = it },
                    )
                }

                if (tab == 2) {
                    if (orders.isEmpty()) {
                        item(key = "ord-empty") {
                            DeskCard { Text("No paper orders yet.", color = QuantColors.TextMuted) }
                        }
                    } else {
                        items(orders, key = { "paper-ord-${it.id}" }) { o ->
                            DeskCard(
                                modifier = Modifier.animateItem(),
                                onClick = { onOpenChart(o.pair, o.id) },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${o.pair} · ${o.side}", fontWeight = FontWeight.SemiBold, color = QuantColors.TextPrimary)
                                    StatusTag(text = o.status, accent = QuantColors.Emerald)
                                }
                                Spacer(Modifier.height(6.dp))
                                StatRow("Fill Price / Qty", "${o.avgPrice ?: o.price ?: "—"} · ${o.quantity}")
                                o.sizeInr?.let {
                                    StatRow("Notional Size", String.format(Locale.US, "₹%,.2f", it))
                                }
                                Text(
                                    formatDateTime(o.createdAt),
                                    color = QuantColors.TextMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                } else if (shownPositions.isEmpty()) {
                    item(key = "empty") {
                        DeskCard {
                            Text(
                                if (tab == 0) "No open paper positions." else "No closed paper positions yet.",
                                color = QuantColors.TextMuted,
                            )
                        }
                    }
                } else {
                    items(shownPositions, key = { "paper-${it.id}" }) { p ->
                        PaperPositionCard(
                            p = p,
                            closing = closingId == p.id,
                            onClose = if (p.status == "OPEN") {
                                {
                                    scope.launch {
                                        closingId = p.id
                                        error = null
                                        try {
                                            container.api.closePaperPosition(p.id)
                                            reload()
                                        } catch (e: Exception) {
                                            error = e.message ?: "Failed to close paper position"
                                        } finally {
                                            closingId = null
                                        }
                                    }
                                }
                            } else null,
                            modifier = Modifier.animateItem(),
                            onClick = { onOpenChart(p.pair, p.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaperPositionCard(
    p: Position,
    closing: Boolean,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val sideAccent = if (p.side.equals("LONG", true)) QuantColors.Emerald else QuantColors.Rose
    DeskCard(modifier = modifier, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.pair, fontWeight = FontWeight.SemiBold, color = QuantColors.TextPrimary)
                StatusTag(text = p.side, accent = sideAccent)
            }
            StatusTag(
                text = p.status,
                accent = if (p.status == "OPEN") QuantColors.Cyan else QuantColors.TextMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        StatRow("Entry", String.format(Locale.US, "%.4f", p.entryPrice))
        if (p.status == "OPEN") {
            StatRow("Mark", if (p.markPrice != null) String.format(Locale.US, "%.4f", p.markPrice) else "—")
        } else {
            StatRow("Exit", if (p.exitPrice != null) String.format(Locale.US, "%.4f", p.exitPrice) else "—")
        }
        StatRow("Quantity", p.quantity.toString())
        val pnl = p.pnl ?: p.unrealizedPnl ?: p.realizedPnl
        pnl?.let {
            StatRow(
                "PnL",
                String.format(Locale.US, "%s₹%,.2f", if (it >= 0) "+" else "-", kotlin.math.abs(it)),
                if (it >= 0) QuantColors.Emerald else QuantColors.Rose,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tap to inspect chart →", style = MaterialTheme.typography.labelMedium, color = QuantColors.Cyan)
            if (onClose != null) {
                TextButton(onClick = onClose, enabled = !closing) {
                    Text(
                        if (closing) "Closing…" else "Close",
                        color = QuantColors.Rose,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
