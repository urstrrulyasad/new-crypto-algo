package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategiesScreen(container: AppContainer) {
    var strategies by remember { mutableStateOf<List<Strategy>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadTick) {
        loading = true
        error = null
        try {
            strategies = container.api.strategies("FUTURES")
        } catch (e: Exception) {
            error = e.message ?: "Failed to load strategies"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Strategies") },
            actions = {
                TextButton(onClick = { scope.launch { reloadTick++ } }) {
                    Text("Refresh")
                }
            },
        )
        if (loading) {
            Column(Modifier.padding(24.dp)) { CircularProgressIndicator() }
            return
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (strategies.isEmpty()) {
                item { Text("No strategies yet") }
            } else {
                items(strategies, key = { it.id }) { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("${s.instrument ?: "—"} · ${s.status}")
                            Text("Market ${s.marketType ?: "—"}")
                            s.paper?.let { p ->
                                Text(
                                    "Paper ${p.closedTrades}/${p.requiredTrades} · " +
                                        "WR ${String.format(Locale.US, "%.1f%%", p.winRate * 100)}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
