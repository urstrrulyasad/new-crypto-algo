package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.quantalgotrade.crypto.BuildConfig
import com.quantalgotrade.crypto.data.AppContainer
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onLoggedOut: () -> Unit) {
    val user by container.sessionStore.user.collectAsState(initial = null)
    val biometricOn by container.sessionStore.biometricEnabled.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity
    val biometricAvailable = canUseBiometric(activity)

    Column(Modifier.fillMaxSize()) {
        HeroHeader("Settings", "Account · security · about")
        Column(Modifier.padding(16.dp)) {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Signed in as", style = MaterialTheme.typography.labelMedium)
                    Text(user?.email ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    user?.role?.let {
                        Text("Role: $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fingerprint unlock", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (biometricAvailable) "Unlock app without typing password"
                            else "Biometrics unavailable on this device",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = biometricOn && biometricAvailable,
                        enabled = biometricAvailable,
                        onCheckedChange = { enabled ->
                            scope.launch { container.sessionStore.setBiometricEnabled(enabled) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    scope.launch {
                        container.sessionStore.clearSession()
                        onLoggedOut()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Sign out")
            }
            Spacer(Modifier.height(20.dp))
            Text("Quant Algo Trade - Crypto", style = MaterialTheme.typography.bodySmall)
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
