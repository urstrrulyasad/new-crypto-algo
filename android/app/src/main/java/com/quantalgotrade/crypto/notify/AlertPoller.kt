package com.quantalgotrade.crypto.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.quantalgotrade.crypto.QuantAlgoTradeApp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.RefreshRequest
import java.util.concurrent.TimeUnit

object AlertPollScheduler {
    private const val UNIQUE = "alert_poll_periodic"

    fun start(context: Context) {
        val req = PeriodicWorkRequestBuilder<AlertPollWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }
}

class AlertPollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? QuantAlgoTradeApp ?: return Result.success()
        return try {
            AlertPoller.pollOnce(app.container)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object AlertPoller {
    suspend fun pollOnce(container: AppContainer) {
        if (!container.sessionStore.hasSession()) return
        ensureAccess(container)
        val alerts = container.api.alerts(40)
            .filter { AlertNotifier.shouldNotify(it.action) }
            .sortedBy { it.createdAt ?: "" }
        if (alerts.isEmpty()) {
            if (!container.sessionStore.alertsBootstrapped()) {
                container.sessionStore.markAlertsBootstrapped(null)
            }
            return
        }
        val newest = alerts.last()
        if (!container.sessionStore.alertsBootstrapped()) {
            // First run: mark current head so we don't flood old history.
            container.sessionStore.markAlertsBootstrapped(newest.id)
            return
        }
        val lastSeen = container.sessionStore.lastSeenAlertId()
        val fresh = if (lastSeen.isNullOrBlank()) {
            alerts.takeLast(5)
        } else {
            val idx = alerts.indexOfLast { it.id == lastSeen }
            if (idx < 0) alerts.takeLast(5) else alerts.drop(idx + 1)
        }
        for (a in fresh) {
            AlertNotifier.notify(container.appContext, a)
        }
        if (fresh.isNotEmpty()) {
            container.sessionStore.setLastSeenAlertId(newest.id)
        }
    }

    private suspend fun ensureAccess(container: AppContainer) {
        if (!container.sessionStore.currentAccessToken().isNullOrBlank()) return
        val refresh = container.sessionStore.currentRefreshToken() ?: return
        val resp = container.api.refresh(RefreshRequest(refresh))
        container.sessionStore.saveSession(resp.accessToken, resp.refreshToken, resp.user)
    }
}
