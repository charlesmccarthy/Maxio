@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

@Composable
fun AuthQrSignInScreen(
    onBackPress: () -> Unit = {},
    onContinue: (() -> Unit)? = null,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val fullAccount = uiState.authState as? AuthState.FullAccount
    val isSignedIn = fullAccount != null
    val isOnboardingMode = onContinue != null
    val isApproved = remember(uiState.qrLoginStatus) {
        uiState.qrLoginStatus?.contains("approved", ignoreCase = true) == true
    }
    var onboardingTransitionHandled by remember(isOnboardingMode) { mutableStateOf(false) }
    var useEmailLogin by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }
    val passwordFocusRequester = remember { FocusRequester() }
    val submitFocusRequester = remember { FocusRequester() }

    BackHandler {
        viewModel.clearQrLoginSession()
        onBackPress()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearQrLoginSession()
        }
    }

    // Auto-start QR login when not using email mode
    LaunchedEffect(uiState.authState, isSignedIn, uiState.qrLoginCode, uiState.isLoading, useEmailLogin) {
        if (
            !useEmailLogin &&
            uiState.authState !is AuthState.Loading &&
            !isSignedIn &&
            uiState.qrLoginCode.isNullOrBlank() &&
            !uiState.isLoading
        ) {
            viewModel.startQrLogin()
        }
    }

    LaunchedEffect(isSignedIn) {
        if (isSignedIn && !uiState.qrLoginCode.isNullOrBlank()) {
            viewModel.clearQrLoginSession()
        }
    }

    LaunchedEffect(isApproved, uiState.isLoading) {
        if (isApproved && !uiState.isLoading) {
            viewModel.exchangeQrLogin()
        }
    }

    LaunchedEffect(isOnboardingMode, isSignedIn) {
        if (!isOnboardingMode || onboardingTransitionHandled) return@LaunchedEffect
        if (isSignedIn) {
            onboardingTransitionHandled = true
            viewModel.clearQrLoginSession()
            onContinue.invoke()
        }
    }

    val nowMillis by produceState(initialValue = System.currentTimeMillis(), key1 = uiState.qrLoginCode) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remainingMillis = uiState.qrLoginExpiresAtMillis?.let { (it - nowMillis).coerceAtLeast(0L) } ?: 0L

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo_wordmark),
                    contentDescription = "Nuvio",
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(60.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = stringResource(R.string.auth_qr_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isSignedIn) {
                        stringResource(R.string.auth_qr_connected)
                    } else if (useEmailLogin) {
                        "Enter your email and password"
                    } else {
                        stringResource(R.string.auth_qr_phone_hint)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (isSignedIn) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = fullAccount.email,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF7CFF9B),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = fullAccount.userId,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .border(1.dp, NuvioColors.Border.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .background(
                        NuvioColors.BackgroundElevated.copy(alpha = 0.35f),
                        RoundedCornerShape(18.dp)
                    )
                    .padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.auth_qr_account_login),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = if (isSignedIn) {
                        stringResource(R.string.auth_qr_synced_data)
                    } else if (useEmailLogin) {
                        "Sign in with your email"
                    } else {
                        stringResource(R.string.auth_qr_scan_instruction)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                if (isSignedIn && !isOnboardingMode) {
                    AccountConnectedStatsStrip(
                        stats = uiState.connectedStats,
                        isLoading = uiState.isStatsLoading
                    )
                } else if (isSignedIn && isOnboardingMode) {
                    StatusPill(
                        text = stringResource(R.string.auth_qr_finishing),
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary
                    )
                } else if (useEmailLogin) {
                    // Email/password login form
                    EmailLoginForm(
                        email = email,
                        onEmailChange = { email = it },
                        password = password,
                        onPasswordChange = { password = it },
                        isSignUp = isSignUp,
                        isLoading = uiState.isLoading,
                        error = uiState.error,
                        qrLoginStatus = uiState.qrLoginStatus,
                        passwordFocusRequester = passwordFocusRequester,
                        submitFocusRequester = submitFocusRequester,
                        onSubmit = {
                            if (email.isNotBlank() && password.isNotBlank()) {
                                if (isSignUp) viewModel.signUp(email.trim(), password)
                                else viewModel.signIn(email.trim(), password)
                            }
                        }
                    )
                } else {
                    // QR code display
                    QrCodeSection(
                        qrBitmap = uiState.qrLoginBitmap,
                        qrCode = uiState.qrLoginCode,
                        remainingMillis = remainingMillis,
                        isLoading = uiState.isLoading,
                        error = uiState.error,
                        qrLoginStatus = uiState.qrLoginStatus,
                        onRefresh = { viewModel.startQrLogin() }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isSignedIn) {
                        if (useEmailLogin) {
                            Button(
                                onClick = { isSignUp = !isSignUp },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioColors.BackgroundCard,
                                    focusedContainerColor = Color.White,
                                    contentColor = NuvioColors.TextPrimary,
                                    focusedContentColor = Color.Black
                                )
                            ) {
                                Text(if (isSignUp) "Already have an account?" else "Create new account")
                            }
                        }
                        Button(
                            onClick = {
                                useEmailLogin = !useEmailLogin
                                if (!useEmailLogin) viewModel.startQrLogin()
                            },
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                focusedContainerColor = Color.White,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContentColor = Color.Black
                            )
                        ) {
                            Text(if (useEmailLogin) "Use QR code" else "Use email instead")
                        }
                    }
                    if (isSignedIn) {
                        Button(
                            onClick = { viewModel.signOut() },
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                focusedContainerColor = Color.White,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContentColor = Color.Black
                            )
                        ) {
                            Text(stringResource(R.string.account_sign_out))
                        }
                    }
                    Button(
                        onClick = {
                            if (onContinue != null && !isSignedIn) {
                                viewModel.signOut()
                            }
                            viewModel.clearQrLoginSession()
                            if (onContinue != null) {
                                onContinue()
                            } else {
                                onBackPress()
                            }
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            focusedContainerColor = Color.White,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContentColor = Color.Black
                        )
                    ) {
                        Text(
                            if (onContinue != null) {
                                if (isSignedIn) stringResource(R.string.auth_qr_continue) else stringResource(R.string.auth_qr_continue_without_account)
                            } else {
                                stringResource(R.string.auth_qr_back)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCodeSection(
    qrBitmap: android.graphics.Bitmap?,
    qrCode: String?,
    remainingMillis: Long,
    isLoading: Boolean,
    error: String?,
    qrLoginStatus: String?,
    onRefresh: () -> Unit
) {
    if (qrBitmap != null) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.fillMaxSize()
            )
        }
    } else if (!isLoading) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .background(NuvioColors.BackgroundCard, RoundedCornerShape(12.dp))
                .border(1.dp, NuvioColors.Border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "QR unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.Secondary,
                        focusedContainerColor = NuvioColors.SecondaryVariant
                    )
                ) {
                    Text("Retry")
                }
            }
        }
    }

    if (!qrCode.isNullOrBlank()) {
        Text(
            text = "Code: $qrCode",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextPrimary,
            fontWeight = FontWeight.Bold
        )
        if (remainingMillis > 0) {
            Text(
                text = "Expires in ${formatDuration(remainingMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary
            )
        }
    }

    val statusText = error ?: qrLoginStatus
    if (!statusText.isNullOrBlank()) {
        StatusPill(
            text = statusText,
            containerColor = if (error != null) Color(0x33C62828) else NuvioColors.BackgroundCard,
            contentColor = if (error != null) Color(0xFFFF6E6E) else NuvioColors.TextSecondary
        )
    }
}

@Composable
private fun EmailLoginForm(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    isSignUp: Boolean,
    isLoading: Boolean,
    error: String?,
    qrLoginStatus: String?,
    passwordFocusRequester: FocusRequester,
    submitFocusRequester: FocusRequester,
    onSubmit: () -> Unit
) {
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = NuvioColors.TextPrimary,
        cursorColor = NuvioColors.Secondary,
        focusedBorderColor = NuvioColors.Secondary,
        unfocusedBorderColor = NuvioColors.Border,
        focusedLabelColor = NuvioColors.Secondary,
        unfocusedLabelColor = NuvioColors.TextSecondary
    )

    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { androidx.compose.material3.Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onNext = { passwordFocusRequester.requestFocus() }
        ),
        colors = textFieldColors,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { androidx.compose.material3.Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { submitFocusRequester.requestFocus() }
        ),
        colors = textFieldColors,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(passwordFocusRequester)
    )

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = onSubmit,
        enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
        colors = ButtonDefaults.colors(
            containerColor = NuvioColors.Secondary,
            focusedContainerColor = NuvioColors.SecondaryVariant,
            contentColor = NuvioColors.OnSecondary,
            focusedContentColor = NuvioColors.OnSecondaryVariant,
            disabledContainerColor = NuvioColors.BackgroundCard.copy(alpha = 0.55f)
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(submitFocusRequester)
    ) {
        Text(
            text = when {
                isLoading -> "Please wait..."
                isSignUp -> "Create Account"
                else -> "Sign In"
            },
            modifier = Modifier.padding(vertical = 4.dp),
            fontWeight = FontWeight.Medium
        )
    }

    val statusText = error ?: qrLoginStatus
    if (!statusText.isNullOrBlank()) {
        StatusPill(
            text = statusText,
            containerColor = if (error != null) Color(0x33C62828) else NuvioColors.BackgroundCard,
            contentColor = if (error != null) Color(0xFFFF6E6E) else NuvioColors.TextSecondary
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NuvioColors.Border.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .background(containerColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.wrapContentHeight()
        )
    }
}

@Composable
private fun AccountConnectedStatsStrip(
    stats: AccountConnectedStats?,
    isLoading: Boolean
) {
    val values = if (isLoading) {
        listOf("...", "...", "...", "...")
    } else {
        listOf(
            (stats?.addons ?: 0).toString(),
            (stats?.plugins ?: 0).toString(),
            (stats?.library ?: 0).toString(),
            (stats?.watchProgress ?: 0).toString()
        )
    }
    val labels = listOf(
        stringResource(R.string.account_stat_addons),
        stringResource(R.string.account_stat_plugins),
        stringResource(R.string.account_stat_library),
        stringResource(R.string.account_stat_progress)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NuvioColors.Border.copy(alpha = 0.8f))
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(values.size) { index ->
                AccountStatItem(
                    value = values[index],
                    label = labels[index],
                    modifier = Modifier.weight(1f)
                )
                if (index != values.lastIndex) {
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .width(1.dp)
                            .background(NuvioColors.Border.copy(alpha = 0.75f))
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NuvioColors.Border.copy(alpha = 0.8f))
        )
    }
}

@Composable
private fun AccountStatItem(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
