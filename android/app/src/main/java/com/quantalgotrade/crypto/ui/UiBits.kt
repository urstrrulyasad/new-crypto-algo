package com.quantalgotrade.crypto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.ui.theme.QuantColors

private val Ease = FastOutSlowInEasing

@Composable
fun HeroHeader(title: String, subtitle: String, kicker: String? = null) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(title) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(320, easing = Ease)) +
            slideInVertically(tween(340, easing = Ease)) { -it / 4 },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF191610),
                            QuantColors.Surface,
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                if (!kicker.isNullOrBlank()) {
                    Text(
                        kicker.uppercase(),
                        color = QuantColors.GoldBright,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    title,
                    color = QuantColors.TextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    color = QuantColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = QuantColors.Hairline)
        }
    }
}

@Composable
fun EquitySparkline(
    points: List<Double>,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) return
    val minVal = kotlin.math.min(0.0, points.min())
    val maxVal = kotlin.math.max(0.0001, points.max())
    val span = (maxVal - minVal).coerceAtLeast(1e-6)
    val positive = points.last() >= points.first()
    val strokeColor = if (positive) QuantColors.Emerald else QuantColors.Rose

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        val padY = 6.dp.toPx()
        val innerH = (size.height - padY * 2).coerceAtLeast(4f)
        fun yAt(v: Double): Float = padY + innerH - (((v - minVal) / span).toFloat() * innerH)

        val zeroY = yAt(0.0)
        drawLine(
            color = QuantColors.Hairline,
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
        )

        val linePath = Path()
        val fillPath = Path()
        points.forEachIndexed { i, v ->
            val x = (i.toFloat() / (points.size - 1)) * size.width
            val y = yAt(v)
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(strokeColor.copy(alpha = 0.28f), Color.Transparent),
            ),
        )
        drawPath(
            path = linePath,
            color = strokeColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun DeskCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickableMod = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Card(
        modifier = clickableMod.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, QuantColors.Hairline),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

@Composable
fun StatusTag(
    text: String,
    accent: Color = QuantColors.Cyan,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AnimatedProgressBar(
    progress: Float,
    color: Color = QuantColors.Cyan,
    trackColor: Color = QuantColors.Canvas,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 550, easing = Ease),
        label = "progress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

@Composable
fun SegmentedTabBar(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(QuantColors.Canvas)
            .border(1.dp, QuantColors.Hairline, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) QuantColors.Cyan.copy(alpha = 0.16f) else Color.Transparent,
                animationSpec = tween(180),
                label = "seg-bg",
            )
            val txt by animateColorAsState(
                targetValue = if (selected) QuantColors.Cyan else QuantColors.TextMuted,
                animationSpec = tween(180),
                label = "seg-txt",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .then(
                        if (selected) Modifier.border(1.dp, QuantColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        else Modifier,
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = txt,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshColumn(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    initialLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {
            Indicator(
                modifier = Modifier.align(Alignment.TopCenter),
                isRefreshing = refreshing,
                state = state,
                color = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.surface,
            )
        },
    ) {
        when {
            initialLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            else -> {
                Column(Modifier.fillMaxSize()) {
                    if (error != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(QuantColors.Rose.copy(alpha = 0.1f))
                                .border(1.dp, QuantColors.Rose.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(error, color = QuantColors.Rose, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    content()
                }
            }
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    accent: Color = QuantColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(QuantColors.Surface)
            .border(1.dp, QuantColors.Hairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = QuantColors.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
