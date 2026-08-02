package com.quantalgotrade.crypto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.quantalgotrade.crypto.data.RefreshRequest
import com.quantalgotrade.crypto.ui.BiometricGateScreen
import com.quantalgotrade.crypto.ui.LoginScreen
import com.quantalgotrade.crypto.ui.MainScaffold
import com.quantalgotrade.crypto.ui.theme.QuantAlgoTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — polling still runs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotifyPermissionIfNeeded()
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
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
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

    private fun requestNotifyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
