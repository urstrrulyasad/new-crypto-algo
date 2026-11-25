package com.quantalgotrade.crypto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quantalgotrade.crypto.data.AppContainer
import com.quantalgotrade.crypto.data.LoginRequest
import com.quantalgotrade.crypto.ui.theme.QuantColors
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
fun LoginScreen(container: AppContainer, onLoggedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
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
        focusedTextColor = QuantColors.TextPrimary,
        unfocusedTextColor = QuantColors.TextPrimary,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = QuantColors.Hairline,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = QuantColors.TextMuted,
        cursorColor = scheme.primary,
        focusedContainerColor = QuantColors.Canvas,
        unfocusedContainerColor = QuantColors.Canvas.copy(alpha = 0.7f),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(QuantColors.Canvas, Color(0xFF17130C), QuantColors.Canvas),
                ),
            ),
    ) {
        AnimatedVisibility(
            visible = show,
            enter = fadeIn(tween(420)) + slideInVertically(tween(440)) { it / 8 },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(listOf(QuantColors.GoldBright, QuantColors.Emerald)),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Q",
                        color = QuantColors.Canvas,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "QuantDCX",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = QuantColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "INR Futures · Backtesting · Live Execution",
                    style = MaterialTheme.typography.bodySmall,
                    color = QuantColors.TextMuted,
                )
                Spacer(Modifier.height(28.dp))

                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, QuantColors.Hairline),
                    colors = CardDefaults.cardColors(containerColor = QuantColors.Surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            shape = RoundedCornerShape(14.dp),
                            colors = fieldColors,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showPassword) "Hide password" else "Show password",
                                        tint = QuantColors.TextMuted,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = fieldColors,
                        )

                        AnimatedVisibility(visible = error != null) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(QuantColors.Rose.copy(alpha = 0.12f))
                                    .border(1.dp, QuantColors.Rose.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                            ) {
                                Text(error.orEmpty(), color = QuantColors.Rose, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Spacer(Modifier.height(18.dp))
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
                                        error = "Sign-in failed (${e.code()})"
                                    } catch (e: Exception) {
                                        error = e.message ?: "Sign-in failed"
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                            enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = scheme.primary,
                                contentColor = scheme.onPrimary,
                            ),
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
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
    }
}
