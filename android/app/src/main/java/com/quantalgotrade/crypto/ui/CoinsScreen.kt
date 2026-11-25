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
import androidx.compose.foundation.layout.size
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

@Composable
fun CoinsScreen(
    container: AppContainer,
    onOpenChart: (pair: String) -> Unit,
) {
    var coins by remember { mutableStateOf<List<String>>(emptyList()) }
    var byCoin by remember { mutableStateOf<Map<String, List<Strategy>>>(emptyMap()) }
    var search by remember { mutableStateOf("") }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        error = null
        try {
            val instruments = runCatching {
                container.api.futuresInstruments().instruments
            }.getOrDefault(emptyList())
            val strategies = container.api.strategies("FUTURES")
            byCoin = strategies.groupBy { it.instrument ?: "—" }
            coins = (instruments + byCoin.keys).distinct().sorted()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load coins"
        } finally {
            initialLoading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val filtered = remember(coins, search) {
        val q = search.trim()
        if (q.isBlank()) coins
        else coins.filter { it.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        HeroHeader(
            title = "Futures coins",
            subtitle = "${coins.size} INR-margined contracts · tap any coin for live chart",
            kicker = "CoinDCX Futures",
        )
        PullRefreshColumn(
            refreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; reload() } },
            initialLoading = initialLoading,
            error = error,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Filter contracts (e.g. BTC, ETH, SOL)") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = QuantColors.TextMuted,
                        )
                    },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Clear",
                                    tint = QuantColors.TextMuted,
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QuantColors.Cyan,
                        unfocusedBorderColor = QuantColors.Hairline,
                        focusedContainerColor = QuantColors.Surface,
                        unfocusedContainerColor = QuantColors.Surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (filtered.isEmpty()) {
                DeskCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        if (search.isBlank()) "No futures contracts returned." else "No contracts matching \"$search\".",
                        color = QuantColors.TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { "coin-$it" }) { coin ->
                        val strats = byCoin[coin].orEmpty()
                        val displayName = coin.removePrefix("B-").replace('_', '/')
                        val liveCount = strats.count { it.status == "LIVE_APPROVED" }
                        DeskCard(
                            onClick = { onOpenChart(coin) },
                            modifier = Modifier.animateItem(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = QuantColors.TextPrimary,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        coin,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = QuantColors.TextMuted,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (strats.isNotEmpty()) {
                                        val label = if (liveCount > 0) {
                                            "${strats.size} strat · $liveCount live"
                                        } else {
                                            "${strats.size} strat"
                                        }
                                        StatusTag(
                                            text = label,
                                            accent = if (liveCount > 0) QuantColors.Emerald else QuantColors.Cyan,
                                        )
                                    }
                                    Icon(
                                        Icons.Filled.ChevronRight,
                                        contentDescription = "Open chart",
                                        tint = QuantColors.TextMuted,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
