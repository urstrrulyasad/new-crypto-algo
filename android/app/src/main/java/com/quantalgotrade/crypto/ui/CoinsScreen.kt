package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

@Composable
fun CoinsScreen(
    container: AppContainer,
    onOpenChart: (pair: String) -> Unit,
) {
    var coins by remember { mutableStateOf<List<String>>(emptyList()) }
    var byCoin by remember { mutableStateOf<Map<String, List<Strategy>>>(emptyMap()) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    suspend fun reload() {
        error = null
        try {
            val instruments = runCatching {
                container.api.futuresInstruments().instruments
            }.getOrDefault(emptyList())
            val strategies = container.api.strategies("FUTURES")
            byCoin = strategies.groupBy { it.instrument ?: "—" }
            coins = (instruments + byCoin.keys).distinct().sorted()
        } catch (e: Exception) {
            error = e.message ?: "Failed to load coins"
        } finally {
            initialLoading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        HeroHeader("Futures coins", "Tap a coin to open its live chart")
        PullRefreshColumn(
            refreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; reload() } },
            initialLoading = initialLoading,
            error = error,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(coins, key = { "coin-$it" }) { coin ->
                    val strats = byCoin[coin].orEmpty()
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChart(coin) },
                        colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(coin, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (strats.isEmpty()) "No active strategy yet · tap for chart"
                                else "${strats.size} strategies · tap for chart",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
