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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.quantalgotrade.crypto.data.Strategy
import com.quantalgotrade.crypto.data.StrategyTrade
import kotlinx.coroutines.launch
import java.util.Locale

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
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var approveBusy by remember { mutableStateOf(false) }
    var approveMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    suspend fun reload() {
        error = null
        try {
            strategy = container.api.strategy(strategyId)
            trades = runCatching { container.api.strategyTrades(strategyId, "PAPER") }
                .getOrDefault(emptyList())
        } catch (e: Exception) {
            error = e.message ?: "Failed to load strategy"
        } finally {
            initialLoading = false
            refreshing = false
        }
    }

    LaunchedEffect(strategyId) { reload() }

    val open = trades.filter { it.status == "OPEN" }
    val closed = trades.filter { it.status != "OPEN" }
    val pair = strategy?.instrument

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(strategy?.name ?: "Strategy", maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = scheme.surface,
                titleContentColor = scheme.onSurface,
                navigationIconContentColor = scheme.onSurface,
            ),
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
                val s = strategy
                if (s != null) {
                    item(key = "header") {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = scheme.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(s.status.replace('_', ' '), color = scheme.primary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${s.instrument ?: "—"} · ${s.marginCurrency ?: "INR"} · ${s.paper?.openPositions ?: 0} open",
                                    color = scheme.onSurfaceVariant,
                                )
                                val paper = s.paper
                                if (paper != null) {
                                    Spacer(Modifier.height(10.dp))
                                    val need = paper.requiredTrades.coerceAtLeast(1)
                                    val prog = (paper.closedTrades.toFloat() / need).coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { prog },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = scheme.primary,
                                        trackColor = scheme.surfaceVariant,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        String.format(
                                            Locale.US,
                                            "Open %d · Paper %d/%d · WR %.0f%% · PnL %.2f",
                                            paper.openPositions,
                                            paper.closedTrades,
                                            paper.requiredTrades,
                                            paper.winRate * 100,
                                            paper.totalPnl,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.secondary,
                                    )
                                }
                                if (s.status == "PAPER_TRADING") {
                                    Spacer(Modifier.height(12.dp))
                                    val gateOk = paperGateLikelyMet(s.paper)
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                approveBusy = true
                                                approveMsg = null
                                                try {
                                                    val resp = container.api.approveLive(s.id)
                                                    approveMsg = if (resp.ok) {
                                                        "Approved for LIVE"
                                                    } else {
                                                        resp.reason ?: "Approve failed"
                                                    }
                                                    reload()
                                                } catch (e: Exception) {
                                                    approveMsg = e.message ?: "Approve failed"
                                                } finally {
                                                    approveBusy = false
                                                }
                                            }
                                        },
                                        enabled = !approveBusy && gateOk,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text(
                                            when {
                                                approveBusy -> "Approving…"
                                                !gateOk -> "Paper gate not met yet"
                                                else -> "Approve for LIVE"
                                            },
                                        )
                                    }
                                    if (!gateOk) {
                                        Text(
                                            "Need ${s.paper?.requiredTrades ?: 100} closed paper trades and ≥${((s.paper?.requiredWinRate ?: 0.6) * 100).toInt()}% win rate. Below that WR is auto-rejected.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    approveMsg?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (it.contains("Approved")) scheme.secondary else scheme.error,
                                        )
                                    }
                                }
                                if (!pair.isNullOrBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = { onOpenChart(pair, s.id) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text("Open live chart")
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "open-title") {
                    Text("Open trades (${open.size})", fontWeight = FontWeight.SemiBold)
                }
                if (open.isEmpty()) {
                    item(key = "open-empty") {
                        Text("No open paper trades", color = scheme.onSurfaceVariant)
                    }
                } else {
                    items(open, key = { "open-${it.id}" }) { t ->
                        TradeCard(t, scheme.surface, scheme.onSurfaceVariant, scheme.secondary, scheme.error)
                    }
                }

                item(key = "closed-title") {
                    Spacer(Modifier.height(4.dp))
                    Text("Closed trades (${closed.size})", fontWeight = FontWeight.SemiBold)
                }
                if (closed.isEmpty()) {
                    item(key = "closed-empty") {
                        Text("No closed paper trades yet", color = scheme.onSurfaceVariant)
                    }
                } else {
                    items(closed, key = { "closed-${it.id}" }) { t ->
                        TradeCard(t, scheme.surface, scheme.onSurfaceVariant, scheme.secondary, scheme.error)
                    }
                }
            }
        }
    }
}

private fun paperGateLikelyMet(paper: com.quantalgotrade.crypto.data.PaperProgress?): Boolean {
    if (paper == null) return false
    val need = paper.requiredTrades.coerceAtLeast(1)
    if (paper.closedTrades < need) return false
    return paper.winRate >= paper.requiredWinRate
}

@Composable
private fun TradeCard(
    t: StrategyTrade,
    surface: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
    up: androidx.compose.ui.graphics.Color,
    down: androidx.compose.ui.graphics.Color,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${t.pair} · ${t.side}", fontWeight = FontWeight.SemiBold)
                Text(t.status, color = muted)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                String.format(Locale.US, "Entry ₹%,.6f", t.entryPrice),
                color = muted,
            )
            Text(
                if (t.exitPrice != null) String.format(Locale.US, "Exit ₹%,.6f", t.exitPrice)
                else "Exit —",
                color = muted,
            )
            Text("Qty ${t.quantity}", color = muted)
            Text("Opened ${formatDateTime(t.openedAt)}", color = muted)
            if (!t.closedAt.isNullOrBlank()) {
                Text("Closed ${formatDateTime(t.closedAt)}", color = muted)
            }
            t.realizedPnl?.let {
                Text(
                    String.format(Locale.US, "PnL ₹%,.2f", it),
                    color = if (it >= 0) up else down,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
