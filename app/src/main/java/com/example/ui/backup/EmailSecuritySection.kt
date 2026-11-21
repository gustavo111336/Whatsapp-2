package com.example.ui.backup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    isBusy: Boolean,
    onToggleVerification: (Boolean) -> Unit,
    onGeneratePrimaryCode: () -> Unit,
    onGenerateRecoveryCode: (String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onSaveRecoveryEmail: (String) -> Unit,
    onSendFirebaseVerification: () -> Unit,
    onRefreshFirebaseStatus: () -> Unit,
    onSendRecoveryLink: (String) -> Unit
) {
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }
    var recoveryInput by remember(recoveryEmail) { mutableStateOf(recoveryEmail) }

    LaunchedEffect(activeChallenge?.code) {
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

            // Status & Enable/Disable Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Verificação de e-mail por código",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (verificationEnabled) {
                            "Ativada • exige código de 6 dígitos (letras e números)."
                        } else {
                            "Desativada • você pode ativar novamente quando quiser."
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

            // Primary Email Status Pill
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
                primaryCodeVerified -> "E-mail verificado com código de 6 caracteres"
                else -> "Aguardando confirmação com código de 6 dígitos (letras e números)"
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
                            text = "E-mail da conta: $primaryEmail" +
                                if (firebaseEmailVerified) " • Verificado no Google/Firebase" else "",
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
                        onClick = onGeneratePrimaryCode,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("generate_primary_code_button")
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" Código 6 dígitos", modifier = Modifier.padding(start = 6.dp))
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
                    Text(" Atualizar status no Firebase", modifier = Modifier.padding(start = 4.dp))
                }
            }

            // Displayed 6-character alphanumeric code banner + entry box (for Primary or Recovery)
            if (activeChallenge != null) {
                AlphanumericCodeChallengeBox(
                    context = context,
                    challenge = activeChallenge,
                    codeInput = codeInput,
                    onCodeInputChange = { raw ->
                        codeInput = EmailVerificationHelper.normalizeCodeInput(raw)
                    },
                    isBusy = isBusy,
                    onSubmitCode = { onSubmitCode(codeInput) },
                    onRegenerate = {
                        if (activeChallenge.target == EmailVerificationTarget.PRIMARY) {
                            onGeneratePrimaryCode()
                        } else {
                            onGenerateRecoveryCode(activeChallenge.targetEmail)
                        }
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
                text = "Cadastre um e-mail secundário de recuperação para confirmar sua identidade com código de 6 dígitos ou enviar recuperação caso perca o acesso.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = recoveryInput,
                onValueChange = { recoveryInput = it },
                label = { Text("E-mail de recuperação") },
                placeholder = { Text("recuperacao@exemplo.com") },
                leadingIcon = {
                    Icon(Icons.Rounded.Email, contentDescription = null)
                },
                supportingText = {
                    val label = when {
                        recoveryEmail.isBlank() -> "Nenhum e-mail de recuperação salvo."
                        recoveryEmailVerified -> "E-mail de recuperação verificado: $recoveryEmail"
                        else -> "Salvo ($recoveryEmail) • falta confirmar com código de 6 dígitos."
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
                    Text(if (recoveryInput.isBlank() && recoveryEmail.isNotBlank()) "Remover" else "Salvar")
                }
                OutlinedButton(
                    onClick = { onGenerateRecoveryCode(recoveryInput) },
                    enabled = !isBusy && EmailVerificationHelper.isValidEmail(recoveryInput),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("generate_recovery_code_button")
                ) {
                    Text("Código 6 dig.")
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
private fun AlphanumericCodeChallengeBox(
    context: Context,
    challenge: EmailVerificationChallenge,
    codeInput: String,
    onCodeInputChange: (String) -> Unit,
    isBusy: Boolean,
    onSubmitCode: () -> Unit,
    onRegenerate: () -> Unit
) {
    val targetTitle = when (challenge.target) {
        EmailVerificationTarget.PRIMARY -> "Código de verificação (E-mail principal)"
        EmailVerificationTarget.RECOVERY -> "Código de verificação (E-mail de recuperação)"
    }

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
                text = targetTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "Para: ${challenge.targetEmail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )

            // Prominent 6-character alphanumeric code display
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("displayed_email_verification_code")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        challenge.code.forEach { ch ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = ch.toString(),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(ClipData.newPlainText("Código WhatwillIdo", challenge.code))
                            Toast.makeText(context, "Código ${challenge.code} copiado!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copiar código de 6 caracteres"
                        )
                    }
                }
            }

            OutlinedTextField(
                value = codeInput,
                onValueChange = onCodeInputChange,
                label = { Text("Digite o código de 6 dígitos (letras e números)") },
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
                    onClick = onRegenerate,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Novo código")
                }
            }
        }
    }
}

@Composable
fun EmailVerificationGateDialog(
    primaryEmail: String?,
    activeChallenge: EmailVerificationChallenge?,
    isBusy: Boolean,
    statusMessage: String?,
    onEnsureCodeGenerated: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onDisableVerification: () -> Unit
) {
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (activeChallenge == null) {
            onEnsureCodeGenerated()
        }
    }

    AlertDialog(
        onDismissRequest = {},
        icon = {
            Icon(
                imageVector = Icons.Rounded.MarkEmailRead,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Verificar E-mail (Código de 6 dígitos)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "A verificação de e-mail está ativada para ${primaryEmail ?: "sua conta"}. Confirme com o código de 6 caracteres (letras e números) abaixo ou desative a verificação se preferir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (activeChallenge != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gate_displayed_code")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = activeChallenge.code.toCharArray().joinToString(" "),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            IconButton(
                                onClick = {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cb?.setPrimaryClip(ClipData.newPlainText("Código WhatwillIdo", activeChallenge.code))
                                    Toast.makeText(context, "Código ${activeChallenge.code} copiado!", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copiar código",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = EmailVerificationHelper.normalizeCodeInput(it) },
                    label = { Text("Código de 6 dígitos (letras e números)") },
                    placeholder = { Text("A8K4M2") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gate_code_input")
                )

                if (!statusMessage.isNullOrBlank()) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmitCode(codeInput) },
                enabled = !isBusy && codeInput.length == 6,
                modifier = Modifier.testTag("gate_confirm_code_button")
            ) {
                Text("Confirmar")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onEnsureCodeGenerated,
                    enabled = !isBusy
                ) {
                    Text("Novo código")
                }
                TextButton(
                    onClick = onDisableVerification,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("gate_disable_verification_button")
                ) {
                    Text("Desativar verificação")
                }
            }
        }
    )
}
