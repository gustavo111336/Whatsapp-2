package com.example.ui.backup

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BackupSummary
import com.example.data.model.ThemeMode
import com.example.ui.components.Zap2Avatar
import com.example.ui.components.formatRelativeDateTime
import java.util.Date

@Composable
fun BackupAndSettingsScreen(
    viewModel: BackupViewModel,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val myProfile by viewModel.myProfile.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val localBackups by viewModel.localBackups.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val pendingExportFile by viewModel.pendingExportFile.collectAsStateWithLifecycle()
    val verificationId by viewModel.verificationId.collectAsStateWithLifecycle()

    var displayName by remember(myProfile?.displayName) { mutableStateOf(myProfile?.displayName ?: "") }
    var about by remember(myProfile?.about) { mutableStateOf(myProfile?.about ?: "") }
    var phoneInput by remember(myProfile?.phoneNumber) { mutableStateOf(myProfile?.phoneNumber ?: "") }
    var backupPassword by remember { mutableStateOf("") }
    var smsCodeInput by remember { mutableStateOf("") }

    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRestoreLocal by remember { mutableStateOf<BackupSummary?>(null) }
    var restorePasswordPrompt by remember { mutableStateOf("") }

    val createZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportPendingZipToUri(uri)
        } else {
            viewModel.clearPendingExport()
        }
    }

    val openZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            restorePasswordPrompt = backupPassword
        }
    }

    LaunchedEffect(pendingExportFile) {
        val file = pendingExportFile ?: return@LaunchedEffect
        createZipLauncher.launch(file.name)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
        ) {
            item {
                Text(
                    text = "Perfil, Backup e Tema",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isBusy) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (statusMessage != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.clearStatusMessage() }
                    ) {
                        Text(
                            text = statusMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            // Profile & Phone SMS Verification Card
            item {
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Zap2Avatar(
                                name = myProfile?.displayName ?: "Zap2",
                                photoUrl = myProfile?.photoUrl,
                                online = true,
                                size = 56.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = myProfile?.displayName ?: "Carregando…",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = myProfile?.email ?: "UID: ${viewModel.currentUserId.take(12)}…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (myProfile?.phoneVerified == true) {
                                Icon(
                                    Icons.Rounded.Verified,
                                    contentDescription = "Telefone verificado",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("Nome de exibição") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = about,
                            onValueChange = { about = it },
                            label = { Text("Recado / Status") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it },
                            label = { Text("Telefone (ex: +5511999998888)") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { viewModel.updateProfile(displayName, about, phoneInput) },
                                enabled = !isBusy && displayName.isNotBlank(),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("save_profile_button")
                            ) {
                                Text("Salvar Perfil")
                            }
                            OutlinedButton(
                                onClick = {
                                    val activity = context as? Activity ?: return@OutlinedButton
                                    viewModel.startPhoneSmsVerification(activity, phoneInput)
                                },
                                enabled = !isBusy && phoneInput.isNotBlank(),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("verify_sms_button")
                            ) {
                                Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, Modifier.size(18.dp))
                                Text(" Verificar SMS", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }

            // Encrypted Local & Cloud Backup Card
            item {
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
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Backup Criptografado (.ZIP AES-256)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Compacta todo o banco SQLite (Room) e as mídias locais em um arquivo .zip protegido por senha AES-256, ou sincroniza com a nuvem.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = { backupPassword = it },
                            label = { Text("Senha de criptografia (mín. 4 caracteres)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("backup_password_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { viewModel.createLocalEncryptedBackup(backupPassword, exportAfterCreate = true) },
                                enabled = !isBusy && backupPassword.length >= 4,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("export_zip_backup_button")
                            ) {
                                Icon(Icons.Rounded.Archive, contentDescription = null, Modifier.size(18.dp))
                                Text(" Salvar ZIP", modifier = Modifier.padding(start = 4.dp))
                            }
                            OutlinedButton(
                                onClick = {
                                    openZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                                },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("import_zip_backup_button")
                            ) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, Modifier.size(18.dp))
                                Text(" Importar ZIP", modifier = Modifier.padding(start = 4.dp))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { viewModel.uploadCloudBackup(backupPassword) },
                                enabled = !isBusy && backupPassword.length >= 4,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("upload_cloud_backup_button")
                            ) {
                                Icon(Icons.Rounded.CloudUpload, contentDescription = null, Modifier.size(18.dp))
                                Text(" Enviar Nuvem", modifier = Modifier.padding(start = 4.dp))
                            }
                            OutlinedButton(
                                onClick = { viewModel.downloadAndRestoreCloudBackup(backupPassword) },
                                enabled = !isBusy && backupPassword.isNotEmpty(),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("restore_cloud_backup_button")
                            ) {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, Modifier.size(18.dp))
                                Text(" Baixar Nuvem", modifier = Modifier.padding(start = 4.dp))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Sincronização automática em nuvem",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settings.autoCloudBackup,
                                onCheckedChange = viewModel::setAutoCloudBackup
                            )
                        }
                    }
                }
            }

            if (localBackups.isNotEmpty()) {
                item {
                    Text(
                        text = "Backups Locais Salvos no App",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(localBackups, key = { it.filePath }) { item ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.fileName, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = "${(item.sizeBytes / 1024).coerceAtLeast(1)} KB • ${formatRelativeDateTime(Date(item.createdAtMs))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    pendingRestoreLocal = item
                                    restorePasswordPrompt = backupPassword
                                }
                            ) {
                                Icon(Icons.Rounded.Restore, contentDescription = null, Modifier.size(18.dp))
                                Text(" Restaurar", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }

            // Expressive Material 3 Theme Card
            item {
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
                            Icon(Icons.Rounded.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Aparência Material 3 Expressive",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Dynamic Color (Papel de parede)", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Adapta a paleta do Zap2 às cores do sistema (Android 12+).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = viewModel::setDynamicColor
                            )
                        }
                    }
                }
            }

            // Sign Out
            item {
                OutlinedButton(
                    onClick = { viewModel.signOut(context, onSignedOut) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                        .testTag("sign_out_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                    Text(" Sair da conta", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    // SMS Code Dialog
    if (verificationId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelSmsVerification,
            title = { Text("Código de Verificação SMS") },
            text = {
                OutlinedTextField(
                    value = smsCodeInput,
                    onValueChange = { smsCodeInput = it },
                    label = { Text("Código de 6 dígitos") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val code = smsCodeInput
                        smsCodeInput = ""
                        viewModel.submitSmsCode(code)
                    },
                    enabled = smsCodeInput.length >= 4
                ) {
                    Text("Verificar")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelSmsVerification) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Restore ZIP Password Prompt
    if (pendingRestoreUri != null || pendingRestoreLocal != null) {
        AlertDialog(
            onDismissRequest = {
                pendingRestoreUri = null
                pendingRestoreLocal = null
            },
            title = { Text("Restaurar Backup .ZIP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Digite a senha usada para criptografar este arquivo de backup:")
                    OutlinedTextField(
                        value = restorePasswordPrompt,
                        onValueChange = { restorePasswordPrompt = it },
                        label = { Text("Senha do backup") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingRestoreUri
                        val local = pendingRestoreLocal
                        val pwd = restorePasswordPrompt
                        pendingRestoreUri = null
                        pendingRestoreLocal = null
                        if (uri != null) {
                            viewModel.restoreBackupFromUri(uri, pwd)
                        } else if (local != null) {
                            viewModel.restoreLocalBackupFile(local, pwd)
                        }
                    },
                    enabled = restorePasswordPrompt.isNotEmpty()
                ) {
                    Text("Restaurar Agora")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRestoreUri = null
                    pendingRestoreLocal = null
                }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
