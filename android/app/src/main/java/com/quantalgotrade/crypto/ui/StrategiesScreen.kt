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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.Strategy
import com.quantalgotrade.crypto.ui.theme.QuantColors
import kotlinx.coroutines.launch
import java.util.Locale

private val STATUS_FILTERS = listOf("All", "Live", "Paper", "Backtested")

@Composable
fun StrategiesScreen(
    container: AppContainer,
    onOpenStrategy: (String) -> Unit,
) {
    var strategies by remember { mutableStateOf<List<Strategy>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var filterIdx by remember { mutableIntStateOf(0) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        error = null
        try {
            strategies = container.api.strategies("FUTURES")
                .filter { it.status !in setOf("REJECTED", "ARCHIVED") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load strategies"
        } finally {
            initialLoading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val filtered = remember(strategies, searchQuery, filterIdx) {
        val needle = searchQuery.trim().uppercase(Locale.US)
        strategies.filter { s ->
            val matchFilter = when (filterIdx) {
                1 -> s.status == "LIVE_APPROVED"
                2 -> s.status == "PAPER_TRADING"
                3 -> s.status == "BACKTESTED" || s.status == "GENERATED"
                else -> true
            }
            val matchSearch = needle.isEmpty() ||
                s.name.uppercase(Locale.US).contains(needle) ||
                (s.instrument ?: "").uppercase(Locale.US).contains(needle)
            matchFilter && matchSearch
        }
    }

    Column(Modifier.fillMaxSize()) {
        HeroHeader(
            title = "Futures Strategies",
            subtitle = "Regime-gated AI strategies · tap to inspect pipeline & trades",
            kicker = "Strategy Engine",
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
                item(key = "controls") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search coin or strategy…") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = QuantColors.TextMuted) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = QuantColors.TextMuted)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = QuantColors.Cyan,
                                unfocusedBorderColor = QuantColors.Hairline,
                                focusedContainerColor = QuantColors.Surface,
                                unfocusedContainerColor = QuantColors.Surface,
                            ),
                        )
                        SegmentedTabBar(
                            options = STATUS_FILTERS,
                            selectedIndex = filterIdx,
                            onSelect = { filterIdx = it },
                        )
                    }
                }

                items(filtered, key = { "strat-${it.id}" }) { s ->
                    val statusAccent = when (s.status) {
                        "LIVE_APPROVED" -> QuantColors.Emerald
                        "PAPER_TRADING", "BACKTESTED" -> QuantColors.Cyan
                        else -> QuantColors.Amber
                    }
                    DeskCard(
                        modifier = Modifier.animateItem(),
                        onClick = { onOpenStrategy(s.id) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                s.name,
                                fontWeight = FontWeight.SemiBold,
                                color = QuantColors.TextPrimary,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                            )
                            StatusTag(
                                text = s.status.replace('_', ' '),
                                accent = statusAccent,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${s.instrument ?: "—"} · ${s.marginCurrency ?: "INR"} · ${s.paper?.openPositions ?: 0} open",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = QuantColors.TextMuted,
                        )
                        val paper = s.paper
                        if (paper != null && (s.status == "PAPER_TRADING" || s.status == "LIVE_APPROVED")) {
                            Spacer(Modifier.height(10.dp))
                            val need = paper.requiredTrades.coerceAtLeast(1)
                            val prog = (paper.closedTrades.toFloat() / need).coerceIn(0f, 1f)
                            AnimatedProgressBar(
                                progress = prog,
                                color = if (paper.winRate >= paper.requiredWinRate) QuantColors.Emerald else QuantColors.Cyan,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    String.format(
                                        Locale.US,
                                        "Paper %d/%d · WR %.0f%% · PnL ₹%.2f",
                                        paper.closedTrades,
                                        paper.requiredTrades,
                                        paper.winRate * 100,
                                        paper.totalPnl,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (paper.totalPnl >= 0) QuantColors.Emerald else QuantColors.Rose,
                                )
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = "Inspect",
                                    tint = QuantColors.Cyan,
                                )
                            }
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Inspect pipeline",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = QuantColors.Cyan,
                                )
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = QuantColors.Cyan,
                                )
                            }
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        DeskCard {
                            Text(
                                if (strategies.isEmpty()) "No active strategies yet — auto-gen is running."
                                else "No strategies match this filter.",
                                color = QuantColors.TextMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}
