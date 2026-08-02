package com.quantalgotrade.crypto.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.quantalgotrade.crypto.BuildConfig
import com.quantalgotrade.crypto.data.AppContainer
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartPortalScreen(
    container: AppContainer,
    pair: String,
    mode: String,
    strategyId: String?,
    positionId: String?,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var bootstrapHtml by remember { mutableStateOf<String?>(null) }
    val portal = BuildConfig.WEB_PORTAL_BASE.trimEnd('/')

    LaunchedEffect(pair, mode, strategyId, positionId) {
        val access = container.sessionStore.currentAccessToken().orEmpty()
        val refresh = container.sessionStore.currentRefreshToken().orEmpty()
        val qs = buildString {
            append("mode=").append(mode)
            append("&timeframe=5m")
            if (!strategyId.isNullOrBlank()) append("&strategyId=").append(strategyId)
            if (!positionId.isNullOrBlank()) append("&positionId=").append(positionId)
        }
        val target = "$portal/futures/chart/${URLEncoder.encode(pair, "UTF-8")}?$qs"
        bootstrapHtml = """
            <!doctype html><html><body style="background:#05070d;color:#e2e8f0;font-family:sans-serif;padding:24px">
            Opening chart…
            <script>
              localStorage.setItem('accessToken', ${jsStr(access)});
              localStorage.setItem('refreshToken', ${jsStr(refresh)});
              location.replace(${jsStr(target)});
            </script>
            </body></html>
        """.trimIndent()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(pair, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = scheme.surface,
                titleContentColor = scheme.onSurface,
                navigationIconContentColor = scheme.onSurface,
            ),
        )
        val html = bootstrapHtml
        if (html == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = scheme.primary)
            }
        } else {
            key(html) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL(portal, html, "text/html", "UTF-8", null)
                        }
                    },
                )
            }
        }
    }
}

private fun jsStr(value: String): String =
    buildString {
        append('\'')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
        append('\'')
    }
