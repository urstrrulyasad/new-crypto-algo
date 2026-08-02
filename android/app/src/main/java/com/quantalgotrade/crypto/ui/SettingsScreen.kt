package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.BuildConfig
import com.quantalgotrade.crypto.data.AppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onLoggedOut: () -> Unit) {
    val apiBase by container.sessionStore.apiBase.collectAsState(initial = BuildConfig.DEFAULT_API_BASE)
    val user by container.sessionStore.user.collectAsState(initial = null)
    var apiInput by remember(apiBase) { mutableStateOf(apiBase) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(Modifier.padding(16.dp)) {
            Text("Signed in as", style = MaterialTheme.typography.labelMedium)
            Text(user?.email ?: "—", style = MaterialTheme.typography.titleMedium)
            user?.role?.let {
                Text("Role: $it", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Text("API base URL", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiInput,
                onValueChange = { apiInput = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://host:8080") },
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        container.updateApiBase(apiInput)
                        message = "API URL saved"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save API URL")
            }
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message!!, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    scope.launch {
                        container.sessionStore.clearSession()
                        onLoggedOut()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign out")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Quant Algo Trade - Crypto",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}
