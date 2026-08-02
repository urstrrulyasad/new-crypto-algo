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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import com.quantalgotrade.crypto.data.Strategy
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StrategiesScreen(
    container: AppContainer,
    onOpenStrategy: (String) -> Unit,
) {
    var strategies by remember { mutableStateOf<List<Strategy>>(emptyList()) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    suspend fun reload() {
        error = null
        try {
            strategies = container.api.strategies("FUTURES")
                .filter { it.status !in setOf("REJECTED", "ARCHIVED") }
        } catch (e: Exception) {
            error = e.message ?: "Failed to load strategies"
        } finally {
            initialLoading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        HeroHeader("Strategies", "Tap a strategy for trades, chart & paper detail")
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
                items(strategies, key = { "strat-${it.id}" }) { s ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenStrategy(s.id) },
                        colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(s.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                AssistChip(
                                    onClick = { onOpenStrategy(s.id) },
                                    label = { Text(s.status.replace('_', ' ')) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = scheme.primary.copy(alpha = 0.14f),
                                        labelColor = scheme.primary,
                                    ),
                                )
                            }
                            Text(
                                "${s.instrument ?: "—"} · ${s.marginCurrency ?: "INR"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                            val paper = s.paper
                            if (paper != null && (s.status == "PAPER_TRADING" || s.status == "LIVE_APPROVED")) {
                                Spacer(Modifier.height(8.dp))
                                val need = paper.requiredTrades.coerceAtLeast(1)
                                val prog = (paper.closedTrades.toFloat() / need).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { prog },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = scheme.primary,
                                    trackColor = scheme.surfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    String.format(
                                        Locale.US,
                                        "Paper %d/%d · WR %.0f%% · PnL %.2f",
                                        paper.closedTrades,
                                        paper.requiredTrades,
                                        paper.winRate * 100,
                                        paper.totalPnl,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.secondary,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Open detail →",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.primary,
                            )
                        }
                    }
                }
                if (strategies.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No active strategies yet — auto-gen is running.",
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
