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
import com.quantalgotrade.crypto.data.Position
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun PaperScreen(
    container: AppContainer,
    onOpenChart: (pair: String, positionId: String) -> Unit = { _, _ -> },
) {
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    suspend fun reload() {
        error = null
        try {
            positions = container.api.positions("PAPER")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load paper"
        } finally {
            initialLoading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val open = positions.filter { it.status == "OPEN" }
    val closed = positions.filter { it.status != "OPEN" }

    Column(Modifier.fillMaxSize()) {
        HeroHeader("Paper trading", "Open & closed trades · entry / exit · pull to refresh")
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
                item(key = "summary") {
                    Text(
                        "${open.size} open · ${closed.size} closed",
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                item(key = "open-h") { Text("Open positions", fontWeight = FontWeight.SemiBold) }
                if (open.isEmpty()) {
                    item(key = "open-empty") {
                        Text("No open paper positions", color = scheme.onSurfaceVariant)
                    }
                } else {
                    items(open, key = { "paper-open-${it.id}" }) { p ->
                        PositionCard(
                            p = p,
                            surface = scheme.surface,
                            muted = scheme.onSurfaceVariant,
                            up = scheme.secondary,
                            down = scheme.error,
                            onClick = { onOpenChart(p.pair, p.id) },
                        )
                    }
                }

                item(key = "closed-h") {
                    Spacer(Modifier.height(4.dp))
                    Text("Closed positions", fontWeight = FontWeight.SemiBold)
                }
                if (closed.isEmpty()) {
                    item(key = "closed-empty") {
                        Text("No closed paper positions yet", color = scheme.onSurfaceVariant)
                    }
                } else {
                    items(closed, key = { "paper-closed-${it.id}" }) { p ->
                        PositionCard(
                            p = p,
                            surface = scheme.surface,
                            muted = scheme.onSurfaceVariant,
                            up = scheme.secondary,
                            down = scheme.error,
                            onClick = { onOpenChart(p.pair, p.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionCard(
    p: Position,
    surface: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
    up: androidx.compose.ui.graphics.Color,
    down: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${p.pair} · ${p.side}", fontWeight = FontWeight.SemiBold)
                Text(p.status, color = muted)
            }
            Spacer(Modifier.height(6.dp))
            Text(String.format(Locale.US, "Entry ₹%,.6f", p.entryPrice), color = muted)
            Text(
                if (p.exitPrice != null) String.format(Locale.US, "Exit ₹%,.6f", p.exitPrice)
                else "Exit —",
                color = muted,
            )
            Text("Qty ${p.quantity}", color = muted)
            p.realizedPnl?.let {
                Text(
                    String.format(Locale.US, "PnL ₹%,.2f", it),
                    color = if (it >= 0) up else down,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text("Tap for chart", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
