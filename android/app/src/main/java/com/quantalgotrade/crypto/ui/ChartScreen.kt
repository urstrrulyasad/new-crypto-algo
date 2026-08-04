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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.Candle
import com.quantalgotrade.crypto.data.Position
import com.quantalgotrade.crypto.data.PriceLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    var activePos by remember { mutableStateOf<Position?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastClose by remember { mutableStateOf<Double?>(null) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(pair, timeframe, mode, strategyId, positionId) {
        loading = candles.isEmpty()
        error = null
        while (isActive) {
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
                var pos: Position? = null
                when (mode) {
                    "live", "paper" -> {
                        val portfolioMode = if (mode == "paper") "PAPER" else "LIVE"
                        val positions = runCatching { container.api.positions(portfolioMode) }
                            .getOrDefault(emptyList())
                        pos = when {
                            !positionId.isNullOrBlank() -> positions.find { it.id == positionId }
                            else -> positions
                                .filter { it.pair == pair && it.status.equals("OPEN", ignoreCase = true) }
                                .maxByOrNull { it.openedAt.orEmpty() }
                        }
                        if (pos != null) {
                            val pnl = pos.pnl ?: pos.unrealizedPnl
                            val entryLabel = if (pnl != null) {
                                val qty = if (pos.quantity > 0) String.format(Locale.US, "%.0f ", pos.quantity) else ""
                                "$qty${String.format(Locale.US, "%+.2f", pnl)} INR"
                            } else {
                                "Entry"
                            }
                            overlay += PriceLine(pos.entryPrice, entryLabel, 0xFF22D3EE)
                            pos.markPrice?.takeIf { it > 0 }?.let {
                                overlay += PriceLine(it, "Mark", 0xFF94A3B8)
                            }
                            pos.exitPrice?.takeIf { it > 0 }?.let {
                                overlay += PriceLine(it, "Exit", 0xFFA78BFA)
                            }
                            pos.slPrice?.takeIf { it > 0 }?.let {
                                overlay += PriceLine(it, "SL", 0xFFFB7185)
                            }
                            pos.targetPrice?.takeIf { it > 0 }?.let {
                                overlay += PriceLine(it, "TP", 0xFF34D399)
                            }
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
                activePos = pos
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (candles.isEmpty()) {
                    error = e.message ?: "Failed to load chart"
                }
            } finally {
                loading = false
            }
            val open = activePos?.status.equals("OPEN", ignoreCase = true) == true
            delay(if (mode == "live" || mode == "paper") {
                if (open) 2_000L else 8_000L
            } else {
                15_000L
            })
        }
    }

    val activePnl = activePos?.pnl ?: activePos?.unrealizedPnl

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
                            " · ${fmtChartPrice(it)}"
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
                error != null && candles.isEmpty() -> Text(error!!, color = scheme.error, modifier = Modifier.padding(16.dp))
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

        if (activePos != null && (mode == "live" || mode == "paper")) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surface)
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val sideShort = if (activePos!!.side.equals("SHORT", true) || activePos!!.side == "S") "S" else "L"
                val name = activePos!!.pair.removePrefix("B-").replace('_', '-')
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "$name $sideShort${activePos!!.leverage?.let { " · ${it.toInt()}x" } ?: ""}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${fmtChartInr(activePnl)}${activePos!!.roePct?.let { " (${String.format(Locale.US, "%+.2f%%", it)})" } ?: ""}",
                        color = if ((activePnl ?: 0.0) >= 0) scheme.secondary else scheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text("Active PnL", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                PosRow("Margin", fmtChartInr(activePos!!.marginInr, signed = false))
                PosRow("Size", fmtChartInr(activePos!!.sizeInr, signed = false))
                PosRow("Avg. entry", fmtChartPrice(activePos!!.entryPrice))
                PosRow("Mark", fmtChartPrice(activePos!!.markPrice))
                PosRow("SL", fmtChartPrice(activePos!!.slPrice))
                PosRow("TP", fmtChartPrice(activePos!!.targetPrice))
            }
        } else {
            Text(
                "Pinch to zoom · drag to pan",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PosRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmtChartInr(v: Double?, signed: Boolean = true): String {
    if (v == null) return "—"
    val abs = String.format(Locale.US, "%,.2f", kotlin.math.abs(v))
    return when {
        !signed -> "₹$abs"
        v > 0 -> "+₹$abs"
        v < 0 -> "-₹$abs"
        else -> "₹$abs"
    }
}

private fun fmtChartPrice(v: Double?): String {
    if (v == null || v <= 0) return "—"
    return when {
        v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 1 -> String.format(Locale.US, "%.4f", v)
        v >= 0.01 -> String.format(Locale.US, "%.6f", v)
        else -> String.format(Locale.US, "%.8f", v)
    }
}
