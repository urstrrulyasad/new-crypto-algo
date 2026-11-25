package com.quantalgotrade.crypto.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.quantalgotrade.crypto.ui.theme.QuantColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    onLoggedOut: () -> Unit,
    onOpenLive: () -> Unit = {},
) {
    val user by container.sessionStore.user.collectAsState(initial = null)
    val biometricOn by container.sessionStore.biometricEnabled.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity
    val biometricAvailable = canUseBiometric(activity)
    val isAdmin = user?.role == "SUPER_ADMIN" || user?.role == "TENANT_ADMIN"

    Column(Modifier.fillMaxSize()) {
        HeroHeader(
            title = "Settings",
            subtitle = "Exchange keys · AI chain · device security",
            kicker = "Desk configuration",
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DeskCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "SIGNED IN AS",
                            style = MaterialTheme.typography.labelMedium,
                            color = QuantColors.TextMuted,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            user?.email ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = QuantColors.TextPrimary,
                        )
                    }
                    user?.role?.let {
                        StatusTag(
                            text = it,
                            accent = if (isAdmin) QuantColors.Cyan else QuantColors.TextMuted,
                        )
                    }
                }
            }

            AiHealthBanner(container)

            CoinDcxKeysCard(container, onOpenLive)

            if (isAdmin) {
                AiProvidersCard(container)
            } else {
                DeskCard {
                    Text("AI providers", fontWeight = FontWeight.SemiBold, color = QuantColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Ask a tenant admin to configure AI keys. You still receive rate-limit push alerts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = QuantColors.TextMuted,
                    )
                }
            }

            DeskCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fingerprint unlock", fontWeight = FontWeight.SemiBold, color = QuantColors.TextPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (biometricAvailable) "Unlock app without typing password"
                            else "Biometrics unavailable on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = QuantColors.TextMuted,
                        )
                    }
                    Switch(
                        checked = biometricOn && biometricAvailable,
                        enabled = biometricAvailable,
                        onCheckedChange = { enabled ->
                            scope.launch { container.sessionStore.setBiometricEnabled(enabled) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = QuantColors.Canvas,
                            checkedTrackColor = QuantColors.Cyan,
                            uncheckedTrackColor = QuantColors.Canvas,
                            uncheckedBorderColor = QuantColors.Hairline,
                        ),
                    )
                }
            }

            DeskCard {
                Text("Push alerts", fontWeight = FontWeight.SemiBold, color = QuantColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Local notifications for AI rate limits and LIVE executions. Background checks run roughly every 15 minutes, and every ~30s while open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = QuantColors.TextMuted,
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        container.sessionStore.clearSession()
                        onLoggedOut()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, QuantColors.Rose.copy(alpha = 0.35f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = QuantColors.Rose.copy(alpha = 0.14f),
                    contentColor = QuantColors.Rose,
                ),
            ) {
                Text("Sign out", fontWeight = FontWeight.SemiBold)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("QuantDCX Terminal", style = MaterialTheme.typography.bodySmall, color = QuantColors.TextMuted)
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = QuantColors.TextMuted,
                )
            }
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(QuantColors.Rose.copy(alpha = 0.12f))
            .border(1.dp, QuantColors.Rose.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "AI rate limited — rotate key",
                fontWeight = FontWeight.SemiBold,
                color = QuantColors.Rose,
            )
            Text(h.message, style = MaterialTheme.typography.bodySmall, color = QuantColors.TextPrimary)
            Text(
                "${h.recentRateLimitEvents} event(s) in the last 2h",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = QuantColors.TextMuted,
            )
        }
    }
}

@Composable
private fun CoinDcxKeysCard(container: AppContainer, onOpenLive: () -> Unit) {
    val scope = rememberCoroutineScope()
    var keys by remember { mutableStateOf<List<ExchangeKey>?>(null) }
    var label by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            keys = runCatching { container.api.keys() }.getOrElse { emptyList() }
        }
    }
    LaunchedEffect(Unit) { reload() }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = QuantColors.Cyan,
        unfocusedBorderColor = QuantColors.Hairline,
        focusedContainerColor = QuantColors.Canvas,
        unfocusedContainerColor = QuantColors.Canvas,
    )

    DeskCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("CoinDCX API keys", fontWeight = FontWeight.SemiBold, color = QuantColors.TextPrimary)
                if (keys?.any { it.status.equals("ACTIVE", true) } == true) {
                    TextButton(onClick = onOpenLive) {
                        Text("View Real Trades →", color = QuantColors.Emerald)
                    }
                }
            }
            Text(
                "Encrypted at rest, never shown again. Required for Real Trades (LIVE) execution.",
                style = MaterialTheme.typography.bodySmall,
                color = QuantColors.TextMuted,
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
            )
            OutlinedTextField(
                value = apiSecret,
                onValueChange = { apiSecret = it },
                label = { Text("API secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showSecret) "Hide secret" else "Show secret",
                            tint = QuantColors.TextMuted,
                        )
                    }
                },
            )
            if (error.isNotBlank()) {
                Text(error, color = QuantColors.Rose, style = MaterialTheme.typography.bodySmall)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = QuantColors.Cyan,
                    contentColor = QuantColors.Canvas,
                ),
            ) {
                Text(if (busy) "Saving…" else "Save key", fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(thickness = 1.dp, color = QuantColors.Hairline)
            when {
                keys == null -> Text("Loading…", color = QuantColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                keys!!.isEmpty() -> Text("No keys yet. Paper mode works without keys.", color = QuantColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                else -> keys!!.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(k.label, fontWeight = FontWeight.Medium, color = QuantColors.TextPrimary)
                            Text(
                                "••••${k.keyLast4}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = QuantColors.TextMuted,
                            )
                        }
                        StatusTag(
                            text = k.status,
                            accent = if (k.status.equals("ACTIVE", true)) QuantColors.Emerald else QuantColors.TextMuted,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { container.api.deleteKey(k.id) }
                                reload()
                            }
                        }) {
                            Text("Delete", color = QuantColors.Rose)
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
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<AiProvider>?>(null) }
    var catalog by remember { mutableStateOf<List<AiCatalogEntry>>(emptyList()) }
    var type by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
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
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = QuantColors.Cyan,
        unfocusedBorderColor = QuantColors.Hairline,
        focusedContainerColor = QuantColors.Canvas,
        unfocusedContainerColor = QuantColors.Canvas,
    )

    DeskCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI providers", fontWeight = FontWeight.SemiBold, color = QuantColors.TextPrimary)
            Text(
                "Configure fallback providers for strategy synthesis.",
                style = MaterialTheme.typography.bodySmall,
                color = QuantColors.TextMuted,
            )
            ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                OutlinedTextField(
                    value = selected?.displayName ?: type,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
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
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = QuantColors.TextMuted,
                )
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key",
                            tint = QuantColors.TextMuted,
                        )
                    }
                },
            )
            if (error.isNotBlank()) {
                Text(error, color = QuantColors.Rose, style = MaterialTheme.typography.bodySmall)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = QuantColors.Cyan,
                    contentColor = QuantColors.Canvas,
                ),
            ) {
                Text(if (busy) "Saving…" else "Save provider key", fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(thickness = 1.dp, color = QuantColors.Hairline)
            when {
                providers == null -> Text("Loading…", color = QuantColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                providers!!.isEmpty() -> Text("No AI providers configured.", color = QuantColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                else -> providers!!.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, fontWeight = FontWeight.Medium, color = QuantColors.TextPrimary)
                            Spacer(Modifier.height(3.dp))
                            StatusTag(
                                text = if (p.enabled) "ENABLED" else "DISABLED",
                                accent = if (p.enabled) QuantColors.Emerald else QuantColors.TextMuted,
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
                            Text(if (p.enabled) "Disable" else "Enable", color = QuantColors.Cyan)
                        }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { container.api.deleteAiProvider(p.id) }
                                reload()
                            }
                        }) {
                            Text("Delete", color = QuantColors.Rose)
                        }
                    }
                }
            }
        }
    }
}
