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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

    /** After this long in background, require biometric again (if enabled). */
    private val reLockAfterMs = 5 * 60 * 1000L

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
                    var backgroundedAt by remember { mutableLongStateOf(0L) }
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

                    DisposableEffect(Unit) {
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_STOP -> {
                                    backgroundedAt = System.currentTimeMillis()
                                }
                                Lifecycle.Event.ON_START -> {
                                    if (!bootstrapped) return@LifecycleEventObserver
                                    val awayMs = if (backgroundedAt > 0L) {
                                        System.currentTimeMillis() - backgroundedAt
                                    } else {
                                        0L
                                    }
                                    // Ignore quick app switches; only re-validate after ~5 minutes.
                                    if (awayMs < reLockAfterMs) {
                                        backgroundedAt = 0L
                                        return@LifecycleEventObserver
                                    }
                                    scope.launch {
                                        try {
                                            val refresh = container.sessionStore.currentRefreshToken()
                                            if (refresh.isNullOrBlank()) {
                                                hasRefresh = false
                                                unlocked = false
                                                forcePassword = true
                                                return@launch
                                            }
                                            val refreshed = try {
                                                val resp = container.api.refresh(RefreshRequest(refresh))
                                                container.sessionStore.saveSession(
                                                    resp.accessToken,
                                                    resp.refreshToken,
                                                    resp.user,
                                                )
                                                hasRefresh = true
                                                true
                                            } catch (_: Exception) {
                                                false
                                            }
                                            if (refreshed) {
                                                // Still inside active refresh window — stay in the app.
                                                return@launch
                                            }
                                            // Session expired: clear access and ask fingerprint (or password).
                                            container.sessionStore.clearAccessOnly()
                                            hasRefresh = container.sessionStore.hasSession()
                                            unlocked = false
                                            forcePassword = !biometricOn
                                        } finally {
                                            backgroundedAt = 0L
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
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
                                                hasRefresh = true
                                            }
                                        } catch (_: Exception) {
                                            // stay unlocked only if we already have access token
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
