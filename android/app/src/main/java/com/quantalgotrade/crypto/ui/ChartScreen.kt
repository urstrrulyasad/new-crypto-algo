package com.quantalgotrade.crypto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.Candle
import com.quantalgotrade.crypto.data.PriceLine
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

private val TIMEFRAMES = listOf("1m", "5m", "15m", "1h")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(
    container: AppContainer,
    pair: String,
    mode: String,
    strategyId: String?,
    positionId: String?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var timeframe by remember { mutableStateOf("5m") }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var lines by remember { mutableStateOf<List<PriceLine>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastClose by remember { mutableStateOf<Double?>(null) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(pair, timeframe, mode, strategyId, positionId) {
        loading = true
        error = null
        try {
            val days = when (timeframe) {
                "1m" -> 2L
                "5m" -> 5L
                "15m" -> 10L
                else -> 30L
            }
            val to = Instant.now()
            val from = to.minus(days, ChronoUnit.DAYS)
            candles = container.api.candles(
                pair = pair,
                timeframe = timeframe,
                from = from.toString(),
                to = to.toString(),
                limit = 500,
                marketType = "FUTURES",
            ).sortedBy { it.ts }
            lastClose = candles.lastOrNull()?.close

            val overlay = mutableListOf<PriceLine>()
            when (mode) {
                "live", "paper" -> {
                    val portfolioMode = if (mode == "paper") "PAPER" else "LIVE"
                    val positions = runCatching { container.api.positions(portfolioMode) }
                        .getOrDefault(emptyList())
                    val pos = when {
                        !positionId.isNullOrBlank() -> positions.find { it.id == positionId }
                        else -> positions.filter { it.pair == pair && it.status == "OPEN" }.maxByOrNull { it.openedAt.orEmpty() }
                    }
                    if (pos != null) {
                        overlay += PriceLine(pos.entryPrice, "Entry", 0xFF22D3EE)
                        pos.exitPrice?.let { overlay += PriceLine(it, "Exit", 0xFFA78BFA) }
                    }
                }
                "strategy" -> {
                    if (!strategyId.isNullOrBlank()) {
                        val trades = runCatching { container.api.strategyTrades(strategyId, null) }
                            .getOrDefault(emptyList())
                            .filter { it.pair == pair }
                        val open = trades.firstOrNull { it.status == "OPEN" }
                        val closed = trades.firstOrNull { it.status == "CLOSED" && it.exitPrice != null }
                        when {
                            open != null -> overlay += PriceLine(open.entryPrice, "Entry", 0xFF22D3EE)
                            closed != null -> {
                                overlay += PriceLine(closed.entryPrice, "Entry", 0xFF22D3EE)
                                closed.exitPrice?.let { overlay += PriceLine(it, "Exit", 0xFFA78BFA) }
                            }
                        }
                    }
                }
            }
            lines = overlay
        } catch (e: Exception) {
            error = e.message ?: "Failed to load chart"
            candles = emptyList()
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(pair, maxLines = 1, fontWeight = FontWeight.SemiBold)
                    Text(
                        mode.uppercase(Locale.US) + (lastClose?.let {
                            " · ₹${String.format(Locale.US, "%,.6f", it)}"
                        } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            },
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TIMEFRAMES.forEach { tf ->
                FilterChip(
                    selected = timeframe == tf,
                    onClick = { timeframe = tf },
                    label = { Text(tf) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = scheme.primary.copy(alpha = 0.2f),
                        selectedLabelColor = scheme.primary,
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(color = scheme.primary)
                error != null -> Text(error!!, color = scheme.error, modifier = Modifier.padding(16.dp))
                candles.isEmpty() -> Text("No candle data", color = scheme.onSurfaceVariant)
                else -> NativeCandleChart(
                    candles = candles,
                    priceLines = lines,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Pinch to zoom · drag to pan",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}
