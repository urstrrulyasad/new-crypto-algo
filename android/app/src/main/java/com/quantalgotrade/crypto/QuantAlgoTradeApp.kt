package com.quantalgotrade.crypto

import android.app.Application
import com.quantalgotrade.crypto.data.AppContainer

class QuantAlgoTradeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
