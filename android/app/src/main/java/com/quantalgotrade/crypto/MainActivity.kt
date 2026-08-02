package com.quantalgotrade.crypto

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.quantalgotrade.crypto.data.RefreshRequest
import com.quantalgotrade.crypto.ui.BiometricGateScreen
import com.quantalgotrade.crypto.ui.LoginScreen
import com.quantalgotrade.crypto.ui.MainScaffold
import com.quantalgotrade.crypto.ui.theme.QuantAlgoTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as QuantAlgoTradeApp).container

        setContent {
            QuantAlgoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val token by container.sessionStore.accessToken.collectAsState(initial = null)
                    val biometricOn by container.sessionStore.biometricEnabled.collectAsState(initial = false)
                    var unlocked by remember { mutableStateOf(false) }
                    var forcePassword by remember { mutableStateOf(false) }
                    var bootstrapped by remember { mutableStateOf(false) }
                    var hasRefresh by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    LaunchedEffect(Unit) {
                        hasRefresh = container.sessionStore.hasSession()
                        // Silent token refresh so session survives app restarts.
                        try {
                            val refresh = container.sessionStore.currentRefreshToken()
                            if (!refresh.isNullOrBlank()) {
                                val resp = container.api.refresh(RefreshRequest(refresh))
                                container.sessionStore.saveSession(
                                    resp.accessToken,
                                    resp.refreshToken,
                                    resp.user,
                                )
                                hasRefresh = true
                            }
                        } catch (_: Exception) {
                            // keep refresh token; user can unlock / re-login
                        }
                        bootstrapped = true
                    }

                    if (!bootstrapped) {
                        // brief splash while refreshing session
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize())
                        return@Surface
                    }

                    val loggedIn = !token.isNullOrBlank() || hasRefresh
                    when {
                        loggedIn && biometricOn && !unlocked && !forcePassword -> {
                            BiometricGateScreen(
                                onUnlocked = {
                                    unlocked = true
                                    forcePassword = false
                                    scope.launch {
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
                                        }
                                    }
                                },
                                onUsePassword = {
                                    forcePassword = true
                                    unlocked = false
                                },
                            )
                        }
                        loggedIn && (unlocked || !biometricOn) && !token.isNullOrBlank() -> {
                            MainScaffold(container) {
                                scope.launch {
                                    container.sessionStore.clearSession()
                                    unlocked = false
                                    forcePassword = false
                                    hasRefresh = false
                                }
                            }
                        }
                        else -> {
                            LoginScreen(container) {
                                unlocked = true
                                forcePassword = false
                                hasRefresh = true
                            }
                        }
                    }
                }
            }
        }
    }
}
