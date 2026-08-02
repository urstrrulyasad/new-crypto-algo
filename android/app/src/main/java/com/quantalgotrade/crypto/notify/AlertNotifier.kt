package com.quantalgotrade.crypto.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.quantalgotrade.crypto.MainActivity
import com.quantalgotrade.crypto.R
import com.quantalgotrade.crypto.data.AlertItem

object AlertNotifier {
    const val CHANNEL_TRADES = "live_trades"
    const val CHANNEL_AI = "ai_alerts"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRADES,
                "LIVE orders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Buy and sell fills on LIVE bots"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AI,
                "AI rate limits",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "AI provider rate-limit warnings so you can rotate keys"
            },
        )
    }

    fun notify(context: Context, alert: AlertItem) {
        ensureChannels(context)
        val channel = when (alert.action) {
            "AI_RATE_LIMITED", "STRATEGY_GENERATE_SKIPPED" -> CHANNEL_AI
            else -> CHANNEL_TRADES
        }
        val open = PendingIntent.getActivity(
            context,
            alert.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(alert.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted yet
        }
    }

    fun shouldNotify(action: String): Boolean = when (action) {
        "AI_RATE_LIMITED",
        "LIVE_ORDER_PLACED",
        "LIVE_EXIT",
        "LIVE_FUTURES_EXIT",
        -> true
        else -> false
    }
}
