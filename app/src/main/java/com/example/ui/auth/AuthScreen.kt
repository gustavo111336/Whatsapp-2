package com.example.ui.auth

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.VideoCall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.remote.AuthRepository
import com.example.data.remote.GoogleSignInOutcome
import com.example.service.EmailVerificationHelper
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    authRepository: AuthRepository,
    onAuthenticated: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var recoveryEmailInput by remember { mutableStateOf("") }
    var recoveryFeedback by remember { mutableStateOf<String?>(null) }
    var recoveryBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        val autoSuccess = authRepository.attemptAutoSignIn(context)
        if (autoSuccess) {
            onAuthenticated()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInVertically { -24 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(32.dp))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_welcome_hero),
                            contentDescription = "Arte de boas-vindas do WhatwillIdo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.55f)
                                        )
                                    )
                                )
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Bolt,
                                        contentDescription = "Logo WhatwillIdo",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.tagline),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Converse em tempo real, sem limites.",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.sign_in_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FeaturePillCard(
                            icon = Icons.Rounded.VideoCall,
                            title = "Chamadas WebRTC",
                            subtitle = "Áudio e vídeo HD",
                            modifier = Modifier.weight(1f)
                        )
                        FeaturePillCard(
                            icon = Icons.Rounded.Lock,
                            title = "Backup AES-256",
                            subtitle = "ZIP local & nuvem",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        val activity = context as? Activity
                        if (activity == null) {
                            errorMessage = "Contexto de Activity indisponível."
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            when (val outcome = authRepository.signInWithGoogleInteractive(activity)) {
                                is GoogleSignInOutcome.Success -> {
                                    isLoading = false
                                    onAuthenticated()
                                }
                                GoogleSignInOutcome.Cancelled -> {
                                    isLoading = false
                                }
                                is GoogleSignInOutcome.Error -> {
                                    isLoading = false
                                    errorMessage = outcome.message
                                }
                            }
                        }
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = ButtonDefaults.ContentPadding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .testTag("google_sign_in_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.sign_in_google),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        recoveryFeedback = null
                        showRecoveryDialog = true
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("recover_account_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MarkEmailRead,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = " Recuperar e-mail ou conta",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }

    if (showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = { if (!recoveryBusy) showRecoveryDialog = false },
            icon = { Icon(Icons.Rounded.MarkEmailRead, contentDescription = null) },
            title = { Text("Recuperação de E-mail e Conta") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Informe seu e-mail para solicitar a recuperação pelo Firebase ou escolha outra Conta Google vinculada ao WhatwillIdo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = recoveryEmailInput,
                        onValueChange = { recoveryEmailInput = it },
                        label = { Text("E-mail principal ou de recuperação") },
                        placeholder = { Text("seuemail@exemplo.com") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_recovery_email_input")
                    )
                    if (!recoveryFeedback.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = recoveryFeedback.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            showRecoveryDialog = false
                            val activity = context as? Activity
                            if (activity == null) {
                                errorMessage = "Contexto de Activity indisponível."
                            } else {
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    when (val outcome = authRepository.signInWithGoogleInteractive(activity)) {
                                        is GoogleSignInOutcome.Success -> {
                                            isLoading = false
                                            onAuthenticated()
                                        }
                                        GoogleSignInOutcome.Cancelled -> isLoading = false
                                        is GoogleSignInOutcome.Error -> {
                                            isLoading = false
                                            errorMessage = outcome.message
                                        }
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Escolher outra Conta Google")
                    }
                    TextButton(
                        onClick = {
                            runCatching {
                                uriHandler.openUri("https://accounts.google.com/signin/recovery")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Abrir recuperação oficial da Conta Google")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val candidate = recoveryEmailInput.trim()
                        if (!EmailVerificationHelper.isValidEmail(candidate)) {
                            recoveryFeedback = "Digite um endereço de e-mail válido."
                            return@Button
                        }
                        recoveryBusy = true
                        recoveryFeedback = null
                        scope.launch {
                            authRepository.sendRecoveryEmail(candidate)
                                .onSuccess {
                                    recoveryFeedback = "E-mail de recuperação enviado para ${EmailVerificationHelper.maskEmail(candidate)} (verifique a caixa de entrada e spam). Se sua conta usar login exclusivo Google, use \"Escolher outra Conta Google\"."
                                }
                                .onFailure { err ->
                                    recoveryFeedback = err.localizedMessage
                                        ?: "Se sua conta usa login Google, selecione sua Conta Google acima ou use a recuperação oficial do Google."
                                }
                            recoveryBusy = false
                        }
                    },
                    enabled = !recoveryBusy && recoveryEmailInput.isNotBlank(),
                    modifier = Modifier.testTag("auth_send_recovery_button")
                ) {
                    Text(if (recoveryBusy) "Enviando…" else "Enviar recuperação")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRecoveryDialog = false },
                    enabled = !recoveryBusy
                ) {
                    Text("Fechar")
                }
            }
        )
    }
}

@Composable
private fun FeaturePillCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}
