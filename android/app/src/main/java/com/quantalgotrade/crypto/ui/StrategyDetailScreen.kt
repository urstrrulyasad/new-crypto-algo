package com.quantalgotrade.crypto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.Strategy
import com.quantalgotrade.crypto.data.StrategyTrade
import com.quantalgotrade.crypto.ui.theme.QuantColors
import kotlinx.coroutines.CancellationException
import java.util.Locale

private val PIPELINE_STEPS = listOf("GENERATED", "BACKTESTED", "PAPER_TRADING", "LIVE_APPROVED")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyDetailScreen(
    container: AppContainer,
    strategyId: String,
    onBack: () -> Unit,
    onOpenChart: (pair: String, strategyId: String) -> Unit,
) {
    BackHandler(onBack = onBack)
    var strategy by remember { mutableStateOf<Strategy?>(null) }
    var trades by remember { mutableStateOf<List<StrategyTrade>>(emptyList()) }
    var tradeTab by remember { mutableIntStateOf(0) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshSignal by remember { mutableIntStateOf(0) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(strategyId, refreshSignal) {
        error = null
        try {
            strategy = container.api.strategy(strategyId)
            trades = runCatching { container.api.strategyTrades(strategyId, "PAPER") }
                .getOrDefault(emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load strategy"
        } finally {
            initialLoading = false
            refreshing = false
        }
    }

    val open = trades.filter { it.status == "OPEN" }
    val closed = trades.filter { it.status != "OPEN" }
    val shownTrades = if (tradeTab == 0) open else closed
    val pair = strategy?.instrument

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    strategy?.name ?: "Strategy",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = QuantColors.Surface,
                titleContentColor = QuantColors.TextPrimary,
                navigationIconContentColor = QuantColors.TextPrimary,
            ),
        )
        HorizontalDivider(thickness = 1.dp, color = QuantColors.Hairline)

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
                val s = strategy
                if (s != null) {
                    item(key = "header") {
                        DeskCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${s.instrument ?: "—"} · ${s.marginCurrency ?: "INR"}",
                                    fontFamily = FontFamily.Monospace,
                                    color = QuantColors.TextMuted,
                                )
                                StatusTag(
                                    text = s.status.replace('_', ' '),
                                    accent = if (s.status == "LIVE_APPROVED") QuantColors.Emerald else QuantColors.Cyan,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            PipelineStepperRow(s.status)

                            val paper = s.paper
                            if (paper != null) {
                                Spacer(Modifier.height(14.dp))
                                val need = paper.requiredTrades.coerceAtLeast(1)
                                val prog = (paper.closedTrades.toFloat() / need).coerceIn(0f, 1f)
                                AnimatedProgressBar(
                                    progress = prog,
                                    color = if (paper.winRate >= paper.requiredWinRate) QuantColors.Emerald else QuantColors.Cyan,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatTile(
                                        label = "Trades",
                                        value = "${paper.closedTrades}/${paper.requiredTrades}",
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatTile(
                                        label = "Win Rate",
                                        value = String.format(Locale.US, "%.0f%%", paper.winRate * 100),
                                        accent = if (paper.winRate >= paper.requiredWinRate) QuantColors.Emerald else QuantColors.Cyan,
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatTile(
                                        label = "Paper PnL",
                                        value = String.format(Locale.US, "₹%.0f", paper.totalPnl),
                                        accent = if (paper.totalPnl >= 0) QuantColors.Emerald else QuantColors.Rose,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            if (s.status == "PAPER_TRADING") {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Automatic LIVE promotion at ${s.paper?.requiredTrades ?: 100} paper trades with ≥${((s.paper?.requiredWinRate ?: 0.6) * 100).toInt()}% win rate.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = QuantColors.TextMuted,
                                )
                            }

                            if (!pair.isNullOrBlank()) {
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = { onOpenChart(pair, s.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = scheme.primary,
                                        contentColor = scheme.onPrimary,
                                    ),
                                ) {
                                    Text("Open live chart", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                item(key = "trade-tabs") {
                    SegmentedTabBar(
                        options = listOf("Open (${open.size})", "Closed (${closed.size})"),
                        selectedIndex = tradeTab,
                        onSelect = { tradeTab = it },
                    )
                }

                if (shownTrades.isEmpty()) {
                    item(key = "trades-empty") {
                        DeskCard {
                            Text(
                                if (tradeTab == 0) "No open paper trades." else "No closed paper trades yet.",
                                color = QuantColors.TextMuted,
                            )
                        }
                    }
                } else {
                    items(shownTrades, key = { "tr-${it.id}" }) { t ->
                        TradeCard(t, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineStepperRow(status: String) {
    val current = PIPELINE_STEPS.indexOf(status)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PIPELINE_STEPS.forEachIndexed { idx, step ->
            val done = idx < current
            val active = idx == current
            val accent = when {
                done -> QuantColors.Emerald
                active -> QuantColors.Cyan
                else -> QuantColors.TextMuted
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (done || active) accent.copy(alpha = 0.12f) else QuantColors.Canvas)
                    .border(
                        1.dp,
                        if (done || active) accent.copy(alpha = 0.4f) else QuantColors.Hairline,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = step.replace("_TRADING", "").replace("_APPROVED", "").replace('_', ' '),
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TradeCard(t: StrategyTrade, modifier: Modifier = Modifier) {
    val sideAccent = if (t.side.equals("LONG", true)) QuantColors.Emerald else QuantColors.Rose
    DeskCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.pair, fontWeight = FontWeight.SemiBold, color = QuantColors.TextPrimary)
                StatusTag(text = t.side, accent = sideAccent)
            }
            StatusTag(
                text = t.status,
                accent = if (t.status == "OPEN") QuantColors.Cyan else QuantColors.TextMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        StatRow("Entry", String.format(Locale.US, "₹%,.4f", t.entryPrice))
        StatRow("Exit", if (t.exitPrice != null) String.format(Locale.US, "₹%,.4f", t.exitPrice) else "—")
        StatRow("Quantity", t.quantity.toString())
        t.realizedPnl?.let {
            StatRow(
                "Realized PnL",
                String.format(Locale.US, "%s₹%,.2f", if (it >= 0) "+" else "-", kotlin.math.abs(it)),
                if (it >= 0) QuantColors.Emerald else QuantColors.Rose,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Opened ${formatDateTime(t.openedAt)}${if (!t.closedAt.isNullOrBlank()) " · Closed ${formatDateTime(t.closedAt)}" else ""}",
            color = QuantColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
