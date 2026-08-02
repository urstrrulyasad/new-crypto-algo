package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.quantalgotrade.crypto.BuildConfig
import com.quantalgotrade.crypto.data.AiCatalogEntry
import com.quantalgotrade.crypto.data.AiHealth
import com.quantalgotrade.crypto.data.AiProvider
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.CreateKeyRequest
import com.quantalgotrade.crypto.data.ExchangeKey
import com.quantalgotrade.crypto.data.UpsertProviderRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onLoggedOut: () -> Unit) {
    val user by container.sessionStore.user.collectAsState(initial = null)
    val biometricOn by container.sessionStore.biometricEnabled.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity
    val biometricAvailable = canUseBiometric(activity)
    val isAdmin = user?.role == "SUPER_ADMIN" || user?.role == "TENANT_ADMIN"

    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        HeroHeader("Settings", "Keys · AI providers · account")
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Signed in as", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Text(user?.email ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    user?.role?.let {
                        Text("Role: $it", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }

            AiHealthBanner(container)

            CoinDcxKeysCard(container)

            if (isAdmin) {
                AiProvidersCard(container)
            } else {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("AI providers", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Ask a tenant admin to configure AI keys. You still get rate-limit push alerts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
            ) {
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
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = biometricOn && biometricAvailable,
                        enabled = biometricAvailable,
                        onCheckedChange = { enabled ->
                            scope.launch { container.sessionStore.setBiometricEnabled(enabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = scheme.onPrimary,
                            checkedTrackColor = scheme.primary,
                        ),
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Push alerts", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Local notifications for AI rate limits and LIVE buy/sell. Keep the app installed; background checks run about every 15 minutes, and every ~30s while open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        container.sessionStore.clearSession()
                        onLoggedOut()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.error.copy(alpha = 0.18f),
                    contentColor = scheme.error,
                ),
            ) {
                Text("Sign out")
            }

            Text("Quant Algo Trade - Crypto", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AiHealthBanner(container: AppContainer) {
    var health by remember { mutableStateOf<AiHealth?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            health = runCatching { container.api.aiHealth() }.getOrNull()
            delay(15_000)
        }
    }
    val h = health ?: return
    if (!h.rateLimited) return
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("AI rate limited — rotate the key", fontWeight = FontWeight.SemiBold, color = scheme.error)
            Text(h.message, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
            Text(
                "${h.recentRateLimitEvents} event(s) in the last 2h",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoinDcxKeysCard(container: AppContainer) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var keys by remember { mutableStateOf<List<ExchangeKey>?>(null) }
    var label by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            keys = runCatching { container.api.keys() }.getOrElse { emptyList() }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CoinDCX API keys", fontWeight = FontWeight.SemiBold)
            Text(
                "Encrypted at rest, never shown again. Needed for LIVE trading.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiSecret,
                onValueChange = { apiSecret = it },
                label = { Text("API secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (error.isNotBlank()) {
                Text(error, color = scheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = ""
                        try {
                            container.api.createKey(CreateKeyRequest(label, apiKey, apiSecret))
                            label = ""
                            apiKey = ""
                            apiSecret = ""
                            reload()
                        } catch (e: Exception) {
                            error = e.message ?: "Failed to save key"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && label.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (busy) "Saving…" else "Save key")
            }
            when {
                keys == null -> Text("Loading…", color = scheme.onSurfaceVariant)
                keys!!.isEmpty() -> Text("No keys yet. Paper works without keys.", color = scheme.onSurfaceVariant)
                else -> keys!!.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(k.label, fontWeight = FontWeight.Medium)
                            Text(
                                "••••${k.keyLast4} · ${k.status}",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { container.api.deleteKey(k.id) }
                                reload()
                            }
                        }) {
                            Text("Delete", color = scheme.error)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiProvidersCard(container: AppContainer) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<AiProvider>?>(null) }
    var catalog by remember { mutableStateOf<List<AiCatalogEntry>>(emptyList()) }
    var type by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            providers = runCatching { container.api.aiProviders() }.getOrElse { emptyList() }
        }
    }
    LaunchedEffect(Unit) {
        catalog = runCatching { container.api.aiCatalog() }.getOrElse { emptyList() }
        if (type.isBlank()) type = catalog.firstOrNull()?.type.orEmpty()
        reload()
    }

    val selected = catalog.find { it.type == type }

    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI providers", fontWeight = FontWeight.SemiBold)
            Text(
                "Paste a fresh key here when rate-limited. One key per provider type.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                OutlinedTextField(
                    value = selected?.displayName ?: type,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    catalog.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.displayName) },
                            onClick = {
                                type = c.type
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            if (selected != null && selected.models.isNotEmpty()) {
                Text(
                    "Models: ${selected.models.joinToString(" → ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            if (error.isNotBlank()) {
                Text(error, color = scheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = ""
                        try {
                            container.api.upsertAiProvider(UpsertProviderRequest(providerType = type, apiKey = apiKey))
                            apiKey = ""
                            reload()
                        } catch (e: Exception) {
                            error = e.message ?: "Failed to save provider"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && type.isNotBlank() && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (busy) "Saving…" else "Save provider key")
            }
            when {
                providers == null -> Text("Loading…", color = scheme.onSurfaceVariant)
                providers!!.isEmpty() -> Text("No AI providers configured.", color = scheme.onSurfaceVariant)
                else -> providers!!.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, fontWeight = FontWeight.Medium)
                            Text(
                                if (p.enabled) "ENABLED" else "DISABLED",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    container.api.updateAiProvider(
                                        p.id,
                                        UpsertProviderRequest(providerType = p.providerType, enabled = !p.enabled),
                                    )
                                }
                                reload()
                            }
                        }) {
                            Text(if (p.enabled) "Disable" else "Enable")
                        }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { container.api.deleteAiProvider(p.id) }
                                reload()
                            }
                        }) {
                            Text("Delete", color = scheme.error)
                        }
                    }
                }
            }
        }
    }
}
