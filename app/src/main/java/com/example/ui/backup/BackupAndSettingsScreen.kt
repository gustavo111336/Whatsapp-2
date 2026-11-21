package com.example.ui.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.STORAGE_MODE_AUTO
import com.example.data.local.STORAGE_MODE_FIRESTORE
import com.example.data.local.STORAGE_MODE_GITHUB
import com.example.data.model.BackupSummary
import com.example.data.model.ThemeMode
import com.example.data.remote.FreeMediaStorageManager
import com.example.service.ZipArchiveHelper
import com.example.ui.components.Zap2Avatar
import com.example.ui.components.Zap2ProfileBanner
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
    val mediaUploadProgress by viewModel.mediaUploadProgress.collectAsStateWithLifecycle()

    val effectiveBannerUrl = myProfile?.bannerUrl?.takeIf { it.isNotBlank() }
        ?: settings.cachedBannerUrl.takeIf { it.isNotBlank() }
    val effectivePhotoUrl = myProfile?.photoUrl?.takeIf { it.isNotBlank() }
        ?: settings.cachedPhotoUrl.takeIf { it.isNotBlank() }
    val effectiveName = myProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: settings.cachedDisplayName.ifBlank { "Zap2" }

    var displayName by remember(myProfile?.displayName, settings.cachedDisplayName) {
        mutableStateOf(myProfile?.displayName ?: settings.cachedDisplayName)
    }
    var about by remember(myProfile?.about, settings.cachedAbout) {
        mutableStateOf(myProfile?.about ?: settings.cachedAbout)
    }
    var phoneInput by remember(myProfile?.phoneNumber) { mutableStateOf(myProfile?.phoneNumber ?: "") }
    var backupPassword by remember { mutableStateOf("") }
    var smsCodeInput by remember { mutableStateOf("") }

    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRestoreLocal by remember { mutableStateOf<BackupSummary?>(null) }
    var restorePasswordPrompt by remember { mutableStateOf("") }

    var showBannerOptionsDialog by remember { mutableStateOf(false) }
    var bannerUrlInput by remember(effectiveBannerUrl) { mutableStateOf(effectiveBannerUrl.orEmpty()) }

    var showAvatarOptionsDialog by remember { mutableStateOf(false) }
    var avatarUrlInput by remember(effectivePhotoUrl) { mutableStateOf(effectivePhotoUrl.orEmpty()) }

    var storageModeInput by remember(settings.storageBackendMode) { mutableStateOf(settings.storageBackendMode) }
    var githubRepoInput by remember(settings.githubRepo) { mutableStateOf(settings.githubRepo) }
    var githubBranchInput by remember(settings.githubBranch) { mutableStateOf(settings.githubBranch) }
    var githubTokenInput by remember(settings.githubToken) { mutableStateOf(settings.githubToken) }

    val bannerGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadBannerFromGallery(uri)
        }
    }

    val bannerVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadBannerVideo(uri)
        }
    }

    val avatarImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadAvatarImageOrGif(uri)
        }
    }

    val avatarVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadAvatarVideo(uri)
        }
    }

    val defaultZipFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setDefaultZipExtractUri(treeUri.toString())
        }
    }

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

            val uploadPct = mediaUploadProgress
            if (uploadPct != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { uploadPct },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Enviando mídia do perfil ${(uploadPct * 100).toInt()}%…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else if (isBusy) {
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

            // Profile Banner + Animated Avatar + Phone SMS Verification Card
            item {
                Card(
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column {
                        // Top Profile Banner Header
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        ) {
                            Zap2ProfileBanner(
                                bannerUrl = effectiveBannerUrl,
                                onClick = { showBannerOptionsDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(132.dp)
                                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                                    .testTag("profile_banner_header")
                            )

                            Surface(
                                onClick = { showBannerOptionsDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .heightIn(min = 40.dp)
                                    .testTag("edit_banner_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "Alterar banner",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Banner",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 18.dp)
                                    .offset(y = 14.dp)
                                    .clickable { showAvatarOptionsDialog = true }
                                    .testTag("edit_avatar_button")
                            ) {
                                Zap2Avatar(
                                    name = effectiveName,
                                    photoUrl = effectivePhotoUrl,
                                    online = true,
                                    size = 76.dp,
                                    modifier = Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                        com.example.ui.theme.Zap2ExpressiveShapes.SquircleAvatar
                                    )
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(26.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.CameraAlt,
                                            contentDescription = "Alterar foto ou MP4",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = effectiveName,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showAvatarOptionsDialog = true },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(" Foto / MP4", modifier = Modifier.padding(start = 4.dp))
                                }
                                OutlinedButton(
                                    onClick = { showBannerOptionsDialog = true },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(" Banner", modifier = Modifier.padding(start = 4.dp))
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
                                        .heightIn(min = 48.dp)
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
                                        .heightIn(min = 48.dp)
                                        .testTag("verify_sms_button")
                                ) {
                                    Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, Modifier.size(18.dp))
                                    Text(" Verificar SMS", modifier = Modifier.padding(start = 4.dp))
                                }
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

            // Default ZIP Extraction Folder Card
            item {
                Card(
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.FolderZip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Extração de Arquivos ZIP",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Caminho padrão atual: ${ZipArchiveHelper.formatDefaultExtractSummary(context, settings.defaultZipExtractUri)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { defaultZipFolderLauncher.launch(null) },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("pick_default_zip_folder_button")
                            ) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, Modifier.size(18.dp))
                                Text(" Alterar pasta padrão", modifier = Modifier.padding(start = 6.dp))
                            }
                            if (settings.defaultZipExtractUri.isNotBlank()) {
                                TextButton(
                                    onClick = { viewModel.setDefaultZipExtractUri("") },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Restaurar padrão")
                                }
                            }
                        }
                    }
                }
            }

            // 100% Free Cloud & GitHub Repository Storage Card
            item {
                Card(
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Armazenamento 100% Gratuito",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        val hasGh = settings.githubRepo.contains("/") && settings.githubToken.isNotBlank()
                        val activeSummary = when {
                            settings.storageBackendMode != STORAGE_MODE_FIRESTORE && hasGh ->
                                "Ativo: Repositório GitHub (${settings.githubRepo} • branch ${settings.githubBranch})"
                            else ->
                                "Ativo: Firestore Gratuito (Zero custo, funciona sem Firebase Storage pago)"
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.CloudDone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = activeSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Text(
                            text = "No Firestore Grátis (padrão), cada arquivo de chat fica visível só para quem está naquela conversa e backups só para você (até 35 MB). No GitHub Repo, você pode usar repositório privado para seus backups e uso pessoal (até 200 MB); quem for abrir arquivos de um repo privado precisará ter acesso/token.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                STORAGE_MODE_AUTO to "Automático",
                                STORAGE_MODE_FIRESTORE to "Firestore Grátis",
                                STORAGE_MODE_GITHUB to "GitHub Repo"
                            ).forEach { (modeKey, label) ->
                                FilterChip(
                                    selected = storageModeInput == modeKey,
                                    onClick = { storageModeInput = modeKey },
                                    label = { Text(label) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = githubRepoInput,
                            onValueChange = { githubRepoInput = it },
                            label = { Text("Repositório GitHub (ex: usuario/meu-repo)") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("github_repo_input")
                        )
                        OutlinedTextField(
                            value = githubBranchInput,
                            onValueChange = { githubBranchInput = it },
                            label = { Text("Branch (padrão: main)") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("github_branch_input")
                        )
                        OutlinedTextField(
                            value = githubTokenInput,
                            onValueChange = { githubTokenInput = it },
                            label = { Text("Token GitHub (permissão Contents: Read & Write)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("github_token_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.testGitHubConnection(
                                        githubRepoInput,
                                        githubBranchInput,
                                        githubTokenInput
                                    )
                                },
                                enabled = !isBusy && githubRepoInput.isNotBlank() && githubTokenInput.isNotBlank(),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("test_github_storage_button")
                            ) {
                                Text("Testar Repo")
                            }
                            Button(
                                onClick = {
                                    viewModel.saveFreeStorageConfig(
                                        storageModeInput,
                                        githubRepoInput,
                                        githubBranchInput,
                                        githubTokenInput
                                    )
                                },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("save_free_storage_button")
                            ) {
                                Text("Salvar Modo")
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

    // Banner Options Dialog
    if (showBannerOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showBannerOptionsDialog = false },
            title = { Text("Banner do Perfil") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Defina uma imagem, GIF ou vídeo MP4 em loop automático sem som (também aparece no topo da aba Conversas).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            showBannerOptionsDialog = false
                            bannerGalleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("banner_pick_gallery_button")
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Foto ou GIF da galeria")
                    }

                    Button(
                        onClick = {
                            showBannerOptionsDialog = false
                            bannerVideoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("banner_pick_video_button")
                    ) {
                        Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Vídeo MP4 da galeria (em loop)")
                    }

                    OutlinedTextField(
                        value = bannerUrlInput,
                        onValueChange = { bannerUrlInput = it },
                        label = { Text("URL direta (imagem, .gif ou .mp4)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("banner_url_input")
                    )
                    Button(
                        onClick = {
                            showBannerOptionsDialog = false
                            viewModel.saveBannerUrl(bannerUrlInput)
                        },
                        enabled = bannerUrlInput.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("banner_save_url_button")
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Salvar URL do banner")
                    }

                    if (!effectiveBannerUrl.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                showBannerOptionsDialog = false
                                bannerUrlInput = ""
                                viewModel.saveBannerUrl("")
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Remover banner")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBannerOptionsDialog = false }) {
                    Text("Fechar")
                }
            }
        )
    }

    // Animated / Photo Avatar Options Dialog
    if (showAvatarOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showAvatarOptionsDialog = false },
            title = { Text("Foto / Avatar Animado") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Defina uma foto, GIF ou vídeo MP4 curto em loop automático sem som (ou cole uma URL direta).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            showAvatarOptionsDialog = false
                            avatarImageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("avatar_pick_image_button")
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Foto ou GIF da galeria")
                    }

                    Button(
                        onClick = {
                            showAvatarOptionsDialog = false
                            avatarVideoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("avatar_pick_mp4_button")
                    ) {
                        Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("MP4 curto da galeria (estilo GIF)")
                    }

                    OutlinedTextField(
                        value = avatarUrlInput,
                        onValueChange = { avatarUrlInput = it },
                        label = { Text("URL direta (imagem, .gif ou .mp4)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("avatar_url_input")
                    )
                    Button(
                        onClick = {
                            showAvatarOptionsDialog = false
                            viewModel.saveAvatarUrl(avatarUrlInput)
                        },
                        enabled = avatarUrlInput.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("avatar_save_url_button")
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Salvar URL do avatar")
                    }

                    if (!effectivePhotoUrl.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                showAvatarOptionsDialog = false
                                avatarUrlInput = ""
                                viewModel.saveAvatarUrl("")
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Remover foto de perfil")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAvatarOptionsDialog = false }) {
                    Text("Fechar")
                }
            }
        )
    }
}
