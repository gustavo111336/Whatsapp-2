package com.example.ui.backup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.EMAIL_DELIVERY_APPS_SCRIPT
import com.example.data.local.EMAIL_DELIVERY_AUTO
import com.example.data.local.EMAIL_DELIVERY_GMAIL_SMTP
import com.example.data.local.EMAIL_DELIVERY_RESEND
import com.example.data.local.UserSettings
import com.example.service.EmailDeliveryService
import com.example.service.EmailVerificationChallenge
import com.example.service.EmailVerificationHelper
import com.example.service.EmailVerificationTarget

@Composable
fun EmailSecurityCard(
    primaryEmail: String?,
    verificationEnabled: Boolean,
    primaryCodeVerified: Boolean,
    firebaseEmailVerified: Boolean,
    recoveryEmail: String,
    recoveryEmailVerified: Boolean,
    activeChallenge: EmailVerificationChallenge?,
    settings: UserSettings,
    needsMailerSetupHint: Boolean,
    lastSentMaskedEmail: String?,
    isBusy: Boolean,
    onToggleVerification: (Boolean) -> Unit,
    onSendPrimaryCodeToGmail: () -> Unit,
    onSendRecoveryCodeToGmail: (String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onSaveRecoveryEmail: (String) -> Unit,
    onSendFirebaseVerification: () -> Unit,
    onRefreshFirebaseStatus: () -> Unit,
    onSendRecoveryLink: (String) -> Unit,
    onSaveEmailDeliveryConfig: (
        mode: String,
        senderAddress: String,
        smtpAppPassword: String,
        webhookUrl: String,
        resendApiKey: String,
        resendFrom: String
    ) -> Unit
) {
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }
    var recoveryInput by remember(recoveryEmail) { mutableStateOf(recoveryEmail) }
    var showDeliveryConfig by remember(needsMailerSetupHint) { mutableStateOf(needsMailerSetupHint) }

    LaunchedEffect(activeChallenge?.createdAtMs) {
        if (activeChallenge == null) {
            codeInput = ""
        }
    }

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MarkEmailRead,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Verificação e Recuperação de E-mail",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Enable / Disable switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Verificação de e-mail ao entrar",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (verificationEnabled) {
                            "Ativada • envia código de 6 dígitos (letras e números) para o seu Gmail."
                        } else {
                            "Desativada • você pode reativar quando quiser."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = verificationEnabled,
                    onCheckedChange = onToggleVerification,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("toggle_email_verification")
                )
            }

            val statusColor = when {
                !verificationEnabled -> MaterialTheme.colorScheme.surfaceVariant
                primaryCodeVerified -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.tertiaryContainer
            }
            val statusOnColor = when {
                !verificationEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                primaryCodeVerified -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onTertiaryContainer
            }
            val statusText = when {
                !verificationEnabled -> "Verificação de e-mail desativada"
                primaryCodeVerified -> "E-mail verificado nesta sessão"
                else -> "Aguardando código de 6 dígitos enviado para o Gmail"
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = statusColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (primaryCodeVerified && verificationEnabled) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.Security
                            },
                            contentDescription = null,
                            tint = statusOnColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusOnColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (!primaryEmail.isNullOrBlank()) {
                        Text(
                            text = "Conta: $primaryEmail" +
                                if (firebaseEmailVerified) " • Confirmada no Firebase" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusOnColor.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            if (verificationEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSendPrimaryCodeToGmail,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("generate_primary_code_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" Enviar código ao Gmail", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        onClick = onSendFirebaseVerification,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("send_firebase_email_verification_button")
                    ) {
                        Icon(Icons.Rounded.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" Link Firebase", modifier = Modifier.padding(start = 6.dp))
                    }
                }

                TextButton(
                    onClick = onRefreshFirebaseStatus,
                    enabled = !isBusy,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Já confirmei no link do Gmail", modifier = Modifier.padding(start = 4.dp))
                }
            }

            // Input ONLY — code is NEVER revealed on screen!
            if (activeChallenge != null) {
                InboxCodeEntryBox(
                    challenge = activeChallenge,
                    lastSentMaskedEmail = lastSentMaskedEmail,
                    hasMailer = EmailDeliveryService.hasConfiguredCustomMailer(settings),
                    codeInput = codeInput,
                    onCodeInputChange = { raw ->
                        codeInput = EmailVerificationHelper.normalizeCodeInput(raw)
                    },
                    isBusy = isBusy,
                    onSubmitCode = { onSubmitCode(codeInput) },
                    onResend = {
                        if (activeChallenge.target == EmailVerificationTarget.PRIMARY) {
                            onSendPrimaryCodeToGmail()
                        } else {
                            onSendRecoveryCodeToGmail(activeChallenge.targetEmail)
                        }
                    }
                )
            }

            // Expandable Gmail Delivery Setup (Gmail SMTP App Password / Google Apps Script / Resend)
            OutlinedButton(
                onClick = { showDeliveryConfig = !showDeliveryConfig },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("toggle_email_delivery_config")
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = if (EmailDeliveryService.hasConfiguredCustomMailer(settings)) {
                        " Config. envio para Gmail (Ativa)"
                    } else {
                        " Configurar envio do código para Gmail"
                    },
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                Icon(
                    imageVector = if (showDeliveryConfig) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = showDeliveryConfig) {
                EmailDeliveryConfigPanel(
                    context = context,
                    settings = settings,
                    isBusy = isBusy,
                    onSave = { m, s, p, w, rk, rf ->
                        onSaveEmailDeliveryConfig(m, s, p, w, rk, rf)
                        showDeliveryConfig = false
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Secondary Recovery Email section
            Text(
                text = "Recuperação de E-mail e Conta",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Cadastre um e-mail secundário de recuperação, receba o código de 6 dígitos nele ou envie um link de recuperação.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = recoveryInput,
                onValueChange = { recoveryInput = it },
                label = { Text("E-mail de recuperação") },
                placeholder = { Text("recuperacao@gmail.com") },
                leadingIcon = {
                    Icon(Icons.Rounded.Email, contentDescription = null)
                },
                supportingText = {
                    val label = when {
                        recoveryEmail.isBlank() -> "Nenhum e-mail de recuperação salvo."
                        recoveryEmailVerified -> "E-mail de recuperação verificado: $recoveryEmail"
                        else -> "Salvo ($recoveryEmail) • confirme pelo código enviado ao e-mail."
                    }
                    Text(
                        text = label,
                        color = if (recoveryEmailVerified) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recovery_email_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSaveRecoveryEmail(recoveryInput) },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("save_recovery_email_button")
                ) {
                    Text(if (recoveryInput.isBlank() && recoveryEmail.isNotBlank()) "Remover" else "Salvar e enviar")
                }
                OutlinedButton(
                    onClick = { onSendRecoveryCodeToGmail(recoveryInput) },
                    enabled = !isBusy && EmailVerificationHelper.isValidEmail(recoveryInput),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("generate_recovery_code_button")
                ) {
                    Text("Enviar código")
                }
            }

            if (EmailVerificationHelper.isValidEmail(recoveryInput)) {
                TextButton(
                    onClick = { onSendRecoveryLink(recoveryInput) },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("send_recovery_link_button")
                ) {
                    Text("Enviar link de recuperação para ${EmailVerificationHelper.maskEmail(recoveryInput)}")
                }
            }
        }
    }
}

@Composable
private fun InboxCodeEntryBox(
    challenge: EmailVerificationChallenge,
    lastSentMaskedEmail: String?,
    hasMailer: Boolean,
    codeInput: String,
    onCodeInputChange: (String) -> Unit,
    isBusy: Boolean,
    onSubmitCode: () -> Unit,
    onResend: () -> Unit
) {
    val targetLabel = when (challenge.target) {
        EmailVerificationTarget.PRIMARY -> "Confirmar código enviado ao seu Gmail"
        EmailVerificationTarget.RECOVERY -> "Confirmar código do e-mail de recuperação"
    }
    val maskedDest = lastSentMaskedEmail ?: EmailVerificationHelper.maskEmail(challenge.targetEmail)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = targetLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = if (hasMailer) {
                    "Enviamos o código de 6 dígitos (letras e números) para $maskedDest. Abra seu Gmail (ou Spam) e digite o código abaixo. O código nunca é exibido na tela."
                } else {
                    "Nenhum servidor de e-mail configurado para entregar o código de 6 dígitos em $maskedDest. Configure o Gmail SMTP/Apps Script ou confirme usando o link enviado pelo Firebase no Gmail."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f)
            )

            OutlinedTextField(
                value = codeInput,
                onValueChange = onCodeInputChange,
                label = { Text("Código recebido no Gmail (6 letras/números)") },
                placeholder = { Text("Ex: K7M9P2") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("email_verification_code_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSubmitCode,
                    enabled = !isBusy && codeInput.length == 6,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("confirm_email_code_button")
                ) {
                    Text("Confirmar código")
                }
                OutlinedButton(
                    onClick = onResend,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Reenviar")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailDeliveryConfigPanel(
    context: Context,
    settings: UserSettings,
    isBusy: Boolean,
    onSave: (
        mode: String,
        senderAddress: String,
        smtpAppPassword: String,
        webhookUrl: String,
        resendApiKey: String,
        resendFrom: String
    ) -> Unit
) {
    var modeInput by remember(settings.emailDeliveryMode) { mutableStateOf(settings.emailDeliveryMode) }
    var smtpUserInput by remember(settings.emailSenderAddress) { mutableStateOf(settings.emailSenderAddress) }
    var smtpPassInput by remember(settings.emailSmtpAppPassword) { mutableStateOf(settings.emailSmtpAppPassword) }
    var webhookInput by remember(settings.emailWebhookUrl) { mutableStateOf(settings.emailWebhookUrl) }
    var resendKeyInput by remember(settings.emailResendApiKey) { mutableStateOf(settings.emailResendApiKey) }
    var resendFromInput by remember(settings.emailResendFrom) { mutableStateOf(settings.emailResendFrom) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Como entregar o código de 6 dígitos no Gmail",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Escolha Gmail SMTP (com Senha de App do Google), Google Apps Script Webhook gratuito ou Resend API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    EMAIL_DELIVERY_AUTO to "Auto",
                    EMAIL_DELIVERY_GMAIL_SMTP to "Gmail SMTP",
                    EMAIL_DELIVERY_APPS_SCRIPT to "Apps Script",
                    EMAIL_DELIVERY_RESEND to "Resend API"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = modeInput == key,
                        onClick = { modeInput = key },
                        label = { Text(label) }
                    )
                }
            }

            if (modeInput == EMAIL_DELIVERY_AUTO || modeInput == EMAIL_DELIVERY_GMAIL_SMTP) {
                OutlinedTextField(
                    value = smtpUserInput,
                    onValueChange = { smtpUserInput = it },
                    label = { Text("Gmail remetente (ex: remetente@gmail.com)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("email_smtp_user_input")
                )
                OutlinedTextField(
                    value = smtpPassInput,
                    onValueChange = { smtpPassInput = it },
                    label = { Text("Senha de App do Google (16 letras)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("email_smtp_pass_input")
                )
            }

            if (modeInput == EMAIL_DELIVERY_AUTO || modeInput == EMAIL_DELIVERY_APPS_SCRIPT) {
                OutlinedTextField(
                    value = webhookInput,
                    onValueChange = { webhookInput = it },
                    label = { Text("URL HTTPS Webhook / Google Apps Script") },
                    placeholder = { Text("https://script.google.com/macros/s/.../exec") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("email_webhook_url_input")
                )
                TextButton(
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cb?.setPrimaryClip(
                            ClipData.newPlainText(
                                "Apps Script WhatwillIdo",
                                EmailDeliveryService.GOOGLE_APPS_SCRIPT_SNIPPET
                            )
                        )
                        Toast.makeText(
                            context,
                            "Código do Google Apps Script copiado! Cole em script.google.com.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Copiar script gratuito do Google Apps Script", modifier = Modifier.padding(start = 6.dp))
                }
            }

            if (modeInput == EMAIL_DELIVERY_RESEND) {
                OutlinedTextField(
                    value = resendKeyInput,
                    onValueChange = { resendKeyInput = it },
                    label = { Text("Resend API Key (re_...)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = resendFromInput,
                    onValueChange = { resendFromInput = it },
                    label = { Text("Remetente Resend (opcional)") },
                    placeholder = { Text("WhatwillIdo <onboarding@resend.dev>") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    onSave(
                        modeInput,
                        smtpUserInput,
                        smtpPassInput,
                        webhookInput,
                        resendKeyInput,
                        resendFromInput
                    )
                },
                enabled = !isBusy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("save_email_delivery_config_button")
            ) {
                Text("Salvar configuração de envio Gmail")
            }
        }
    }
}

/**
 * Full-screen Material 3 verification gate displayed immediately upon entering the app
 * whenever email verification is enabled and not yet confirmed for the active session.
 * Never reveals the 6-character code on screen.
 */
@Composable
fun EmailVerificationGateScreen(
    primaryEmail: String?,
    activeChallenge: EmailVerificationChallenge?,
    settings: UserSettings,
    needsMailerSetupHint: Boolean,
    lastSentMaskedEmail: String?,
    isBusy: Boolean,
    statusMessage: String?,
    onSendCodeToGmail: () -> Unit,
    onSendFirebaseLink: () -> Unit,
    onConfirmViaFirebaseLink: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onDisableVerification: () -> Unit,
    onSaveEmailDeliveryConfig: (
        mode: String,
        senderAddress: String,
        smtpAppPassword: String,
        webhookUrl: String,
        resendApiKey: String,
        resendFrom: String
    ) -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }
    var showDeliveryConfig by remember { mutableStateOf(false) }

    // Automatically trigger sending the verification code/email on entering the gate
    LaunchedEffect(primaryEmail) {
        if (activeChallenge == null && !primaryEmail.isNullOrBlank()) {
            onSendCodeToGmail()
        }
    }

    val maskedEmail = lastSentMaskedEmail
        ?: primaryEmail?.takeIf { it.isNotBlank() }?.let(EmailVerificationHelper::maskEmail)
        ?: "seu Gmail"
    val hasMailer = EmailDeliveryService.hasConfiguredCustomMailer(settings)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        MaterialTheme.colorScheme.background
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MarkEmailRead,
                        contentDescription = "Verificar e-mail",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Text(
                text = "Verifique seu e-mail para entrar",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("email_verification_gate_title")
            )

            Text(
                text = if (hasMailer) {
                    "Enviamos um código de 6 dígitos (letras e números) para $maskedEmail. Confira seu Gmail (Caixa de Entrada ou Spam) e digite o código abaixo."
                } else {
                    "A verificação de e-mail ao entrar está ativa para $maskedEmail. Para entregar o código de 6 dígitos (letras e números) direto no seu Gmail, configure o remetente abaixo — ou confirme pelo link oficial enviado ao seu Gmail."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!statusMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = EmailVerificationHelper.normalizeCodeInput(it) },
                        label = { Text("Código recebido no Gmail (6 caracteres)") },
                        placeholder = { Text("Ex: K7M9P2") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gate_code_input")
                    )

                    Button(
                        onClick = { onSubmitCode(codeInput) },
                        enabled = !isBusy && codeInput.length == 6,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("gate_confirm_code_button")
                    ) {
                        Text("Confirmar código e entrar")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onSendCodeToGmail,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("gate_resend_code_button")
                        ) {
                            Text("Reenviar código")
                        }
                        OutlinedButton(
                            onClick = onSendFirebaseLink,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("gate_send_firebase_link_button")
                        ) {
                            Text("Link no Gmail")
                        }
                    }

                    Button(
                        onClick = onConfirmViaFirebaseLink,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("gate_check_firebase_link_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Já cliquei no link no Gmail", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            if (needsMailerSetupHint || !hasMailer) {
                OutlinedButton(
                    onClick = { showDeliveryConfig = !showDeliveryConfig },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("gate_configure_gmail_mailer_button")
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = " Configurar envio Gmail SMTP / Apps Script",
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    Icon(
                        imageVector = if (showDeliveryConfig) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = showDeliveryConfig) {
                    EmailDeliveryConfigPanel(
                        context = context,
                        settings = settings,
                        isBusy = isBusy,
                        onSave = { m, s, p, w, rk, rf ->
                            onSaveEmailDeliveryConfig(m, s, p, w, rk, rf)
                            showDeliveryConfig = false
                            onSendCodeToGmail()
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDisableVerification,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("gate_disable_verification_button")
                ) {
                    Text("Desativar verificação")
                }

                TextButton(
                    onClick = onSignOut,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("gate_sign_out_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(" Trocar conta", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}
