package com.quantalgotrade.crypto

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.notify.AlertNotifier
import com.quantalgotrade.crypto.notify.AlertPollScheduler
import com.quantalgotrade.crypto.notify.AlertPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class QuantAlgoTradeApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var foregroundPollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AlertNotifier.ensureChannels(this)
        AlertPollScheduler.start(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foregroundPollJob?.cancel()
                foregroundPollJob = appScope.launch {
                    while (isActive) {
                        runCatching { AlertPoller.pollOnce(container) }
                        delay(30_000)
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                foregroundPollJob?.cancel()
                foregroundPollJob = null
            }
        })
    }
}
