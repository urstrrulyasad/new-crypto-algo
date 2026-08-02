package com.quantalgotrade.crypto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.LoginRequest
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
fun LoginScreen(container: AppContainer, onLoggedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        email = container.sessionStore.savedEmail().orEmpty()
        show = true
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = Color.White.copy(alpha = 0.65f),
        cursorColor = scheme.primary,
        focusedContainerColor = Color.Black.copy(alpha = 0.18f),
        unfocusedContainerColor = Color.Black.copy(alpha = 0.12f),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF05070D), Color(0xFF0A1628), Color(0xFF0E3A3A)),
                ),
            ),
    ) {
        AnimatedVisibility(
            visible = show,
            enter = fadeIn(tween(500)) + slideInVertically(tween(520)) { it / 5 },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Quant Algo Trade",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    "Crypto · INR Futures",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "AI strategies · Paper · LIVE",
                    color = Color.White.copy(alpha = 0.65f),
                )
                Spacer(Modifier.height(32.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                )
                AnimatedVisibility(visible = error != null) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Text(error.orEmpty(), color = scheme.error)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                val resp = container.api.login(LoginRequest(email.trim(), password))
                                container.sessionStore.saveSession(
                                    resp.accessToken,
                                    resp.refreshToken,
                                    resp.user,
                                )
                                container.sessionStore.setBiometricEnabled(true)
                                onLoggedIn()
                            } catch (e: HttpException) {
                                error = "Login failed (${e.code()})"
                            } catch (e: Exception) {
                                error = e.message ?: "Login failed"
                            } finally {
                                loading = false
                            }
                        }
                    },
                    enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(22.dp),
                            strokeWidth = 2.dp,
                            color = scheme.onPrimary,
                        )
                    } else {
                        Text("Sign in", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
