package com.quantalgotrade.crypto.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.Strategy
import kotlinx.coroutines.launch

@Composable
fun CoinsScreen(container: AppContainer) {
    var coins by remember { mutableStateOf<List<String>>(emptyList()) }
    var byCoin by remember { mutableStateOf<Map<String, List<Strategy>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick) {
        loading = true
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
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        HeroHeader("Futures coins", "INR-margined instruments & strategy coverage")
        TextButton(onClick = { scope.launch { tick++ } }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Refresh")
        }
        if (loading) {
            Column(Modifier.padding(24.dp)) { CircularProgressIndicator() }
            return
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(coins, key = { it }) { coin ->
                val strats = byCoin[coin].orEmpty()
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(coin, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (strats.isEmpty()) "No active strategy yet"
                            else "${strats.size} strategies · ${strats.joinToString { it.status.replace('_', ' ') }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.65f),
                        )
                    }
                }
            }
        }
    }
}
