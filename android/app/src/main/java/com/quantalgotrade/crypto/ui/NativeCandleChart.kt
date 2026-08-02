package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.Candle
import com.quantalgotrade.crypto.data.PriceLine
import kotlin.math.max
import kotlin.math.min

@Composable
fun NativeCandleChart(
    candles: List<Candle>,
    priceLines: List<PriceLine> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (candles.isEmpty()) return

    var scaleX by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val up = Color(0xFF34D399)
    val down = Color(0xFFFB7185)
    val grid = Color(0xFF1A2336)
    val label = Color(0xFF94A3B8)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(candles.size) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scaleX = (scaleX * zoom).coerceIn(0.6f, 6f)
                    offsetX = (offsetX + pan.x).coerceIn(-size.width * scaleX, size.width.toFloat())
                }
            },
    ) {
        val padL = 12.dp.toPx()
        val padR = 56.dp.toPx()
        val padT = 12.dp.toPx()
        val padB = 28.dp.toPx()
        val chartW = size.width - padL - padR
        val chartH = size.height - padT - padB
        if (chartW <= 0f || chartH <= 0f) return@Canvas

        val visibleCount = max(20, (candles.size / scaleX).toInt().coerceIn(20, candles.size))
        val end = candles.size
        val start = (end - visibleCount - (offsetX / (chartW / visibleCount)).toInt())
            .coerceIn(0, max(0, end - 10))
        val slice = candles.subList(start.coerceAtMost(end - 1), end)
        if (slice.isEmpty()) return@Canvas

        var minP = slice.minOf { it.low }
        var maxP = slice.maxOf { it.high }
        priceLines.forEach {
            minP = min(minP, it.price)
            maxP = max(maxP, it.price)
        }
        val pad = (maxP - minP).coerceAtLeast(1e-9) * 0.08
        minP -= pad
        maxP += pad
        val range = (maxP - minP).coerceAtLeast(1e-9)

        fun yOf(price: Double): Float =
            padT + ((maxP - price) / range).toFloat() * chartH

        // grid
        for (i in 0..4) {
            val y = padT + chartH * i / 4f
            drawLine(grid, Offset(padL, y), Offset(padL + chartW, y), strokeWidth = 1f)
            val price = maxP - range * i / 4.0
            drawContext.canvas.nativeCanvas.drawText(
                formatPrice(price),
                padL + chartW + 6f,
                y + 4f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#94A3B8")
                    textSize = 28f
                    isAntiAlias = true
                },
            )
        }

        val slot = chartW / slice.size
        val bodyW = (slot * 0.62f).coerceIn(2f, 18f)
        slice.forEachIndexed { i, c ->
            val x = padL + slot * i + slot / 2f
            val yHigh = yOf(c.high)
            val yLow = yOf(c.low)
            val yOpen = yOf(c.open)
            val yClose = yOf(c.close)
            val bull = c.close >= c.open
            val color = if (bull) up else down
            drawLine(color, Offset(x, yHigh), Offset(x, yLow), strokeWidth = 2f)
            val top = min(yOpen, yClose)
            val h = max(2f, kotlin.math.abs(yClose - yOpen))
            drawRect(
                color = color,
                topLeft = Offset(x - bodyW / 2f, top),
                size = Size(bodyW, h),
            )
        }

        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
        priceLines.forEach { line ->
            val y = yOf(line.price)
            val color = Color(line.colorArgb)
            drawLine(
                color = color,
                start = Offset(padL, y),
                end = Offset(padL + chartW, y),
                strokeWidth = 2.5f,
                pathEffect = dash,
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${line.label} ${formatPrice(line.price)}",
                padL + 8f,
                y - 8f,
                android.graphics.Paint().apply {
                    this.color = line.colorArgb.toInt()
                    textSize = 30f
                    isAntiAlias = true
                    isFakeBoldText = true
                },
            )
        }

        // last price badge
        val last = slice.last()
        val ly = yOf(last.close)
        drawCircle(if (last.close >= last.open) up else down, 5f, Offset(padL + chartW, ly))
    }
}

private fun formatPrice(v: Double): String = when {
    v >= 1000 -> String.format("%.2f", v)
    v >= 1 -> String.format("%.4f", v)
    else -> String.format("%.6f", v)
}
