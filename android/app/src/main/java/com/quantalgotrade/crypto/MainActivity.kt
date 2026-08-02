package com.quantalgotrade.crypto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.quantalgotrade.crypto.data.RefreshRequest
import com.quantalgotrade.crypto.ui.LoginScreen
import com.quantalgotrade.crypto.ui.MainScaffold
import com.quantalgotrade.crypto.ui.theme.QuantAlgoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as QuantAlgoTradeApp
        val container = app.container
        // Best-effort silent refresh on launch
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val refresh = container.sessionStore.currentRefreshToken()
                if (!refresh.isNullOrBlank()) {
                    val resp = container.api.refresh(RefreshRequest(refresh))
                    container.sessionStore.saveSession(
                        resp.accessToken,
                        resp.refreshToken,
                        resp.user,
                    )
                }
            } catch (_: Exception) {
                // stay on login if refresh fails
            }
        }
        setContent {
            QuantAlgoTheme {
                Surface(Modifier.fillMaxSize()) {
                    val token by container.sessionStore.accessToken.collectAsState(initial = null)
                    var forceLogin by remember { mutableStateOf(false) }
                    val loggedIn = !token.isNullOrBlank() && !forceLogin
                    if (loggedIn) {
                        MainScaffold(container) {
                            forceLogin = true
                        }
                    } else {
                        LoginScreen(container) {
                            forceLogin = false
                        }
                    }
                }
            }
        }
    }
}
