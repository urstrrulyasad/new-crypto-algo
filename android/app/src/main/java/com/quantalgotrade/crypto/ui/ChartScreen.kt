package com.quantalgotrade.crypto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.Candle
import com.quantalgotrade.crypto.data.Position
import com.quantalgotrade.crypto.data.PriceLine
import com.quantalgotrade.crypto.ui.theme.QuantColors
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
                            overlay += PriceLine(pos.entryPrice, entryLabel, 0xFFF59E0B)
                            pos.markPrice?.takeIf { it > 0 }?.let {
                                overlay += PriceLine(it, "Mark", 0xFF94A3B8)
                            }
                            pos.exitPrice?.takeIf { it > 0 }?.let {
                                overlay += PriceLine(it, "Exit", 0xFFA78BFA)
                            }
                            pos.slPrice?.takeIf { it > 0 }?.let {
                                overlay += PriceLine(it, "SL", 0xFFF43F5E)
                            }
                            pos.targetPrice?.takeIf { it > 0 }?.let {
                                overlay += PriceLine(it, "TP", 0xFF10B981)
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
                                open != null -> overlay += PriceLine(open.entryPrice, "Entry", 0xFFF59E0B)
                                closed != null -> {
                                    overlay += PriceLine(closed.entryPrice, "Entry", 0xFFF59E0B)
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
    val modeAccent = when (mode.lowercase(Locale.US)) {
        "live" -> QuantColors.Emerald
        "paper" -> QuantColors.Amber
        else -> QuantColors.Cyan
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QuantColors.Canvas),
    ) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            pair.removePrefix("B-").replace('_', '/'),
                            maxLines = 1,
                            fontWeight = FontWeight.Bold,
                            color = QuantColors.TextPrimary,
                        )
                        Text(
                            lastClose?.let { fmtChartPrice(it) } ?: pair,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = QuantColors.TextMuted,
                        )
                    }
                    StatusTag(text = mode.uppercase(Locale.US), accent = modeAccent)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = QuantColors.Surface,
                titleContentColor = QuantColors.TextPrimary,
                navigationIconContentColor = QuantColors.TextPrimary,
            ),
        )
        HorizontalDivider(thickness = 1.dp, color = QuantColors.Hairline)

        SegmentedTabBar(
            options = TIMEFRAMES,
            selectedIndex = TIMEFRAMES.indexOf(timeframe).coerceAtLeast(0),
            onSelect = { idx -> timeframe = TIMEFRAMES[idx] },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(QuantColors.Surface)
                .border(1.dp, QuantColors.Hairline, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(color = QuantColors.Cyan)
                error != null && candles.isEmpty() -> Text(error!!, color = QuantColors.Rose, modifier = Modifier.padding(16.dp))
                candles.isEmpty() -> Text("No candle data", color = QuantColors.TextMuted)
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
            DeskCard(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val sideShort = if (activePos!!.side.equals("SHORT", true) || activePos!!.side == "S") "SHORT" else "LONG"
                val name = activePos!!.pair.removePrefix("B-").replace('_', '/')
                val pnlPositive = (activePnl ?: 0.0) >= 0
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "$name · $sideShort${activePos!!.leverage?.let { " ${it.toInt()}x" } ?: ""}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = QuantColors.TextPrimary,
                        )
                        Text(
                            "Active position telemetry",
                            style = MaterialTheme.typography.bodySmall,
                            color = QuantColors.TextMuted,
                        )
                    }
                    StatusTag(
                        text = "${fmtChartInr(activePnl)}${activePos!!.roePct?.let { " (${String.format(Locale.US, "%+.2f%%", it)})" } ?: ""}",
                        accent = if (pnlPositive) QuantColors.Emerald else QuantColors.Rose,
                    )
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = QuantColors.Hairline)
                Spacer(Modifier.height(6.dp))
                StatRow("Margin", fmtChartInr(activePos!!.marginInr, signed = false))
                StatRow("Size", fmtChartInr(activePos!!.sizeInr, signed = false))
                StatRow("Avg. entry", fmtChartPrice(activePos!!.entryPrice))
                StatRow("Mark", fmtChartPrice(activePos!!.markPrice))
                StatRow("Stop loss", fmtChartPrice(activePos!!.slPrice), QuantColors.Rose)
                StatRow("Take profit", fmtChartPrice(activePos!!.targetPrice), QuantColors.Emerald)
            }
        } else {
            Text(
                "Pinch to zoom · drag to pan",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = QuantColors.TextMuted,
            )
        }
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
