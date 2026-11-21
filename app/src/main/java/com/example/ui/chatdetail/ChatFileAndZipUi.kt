package com.example.ui.chatdetail

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.data.model.ChatMessage
import com.example.ui.components.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ZipPromptStep {
    INITIAL,
    CONFIRM_CANCEL
}

@Composable
fun FileAttachmentMessageCard(
    message: ChatMessage,
    downloadProgress: Float?,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isLocalAvailable = remember(message.id, message.localMediaPath) {
        message.localMediaPath?.let(::File)?.exists() == true
    }

    val badgeIcon: ImageVector
    val badgeColor: Color
    val badgeTint: Color
    val typeLabel: String
    val primaryButtonText: String
    val primaryButtonIcon: ImageVector

    when (message.messageType) {
        "PDF" -> {
            badgeIcon = Icons.Rounded.PictureAsPdf
            badgeColor = MaterialTheme.colorScheme.errorContainer
            badgeTint = MaterialTheme.colorScheme.onErrorContainer
            typeLabel = "Documento PDF"
            primaryButtonText = if (isLocalAvailable) "Visualizar PDF" else "Baixar e Abrir PDF"
            primaryButtonIcon = Icons.Rounded.Visibility
        }
        "APK" -> {
            badgeIcon = Icons.Rounded.Android
            badgeColor = MaterialTheme.colorScheme.tertiaryContainer
            badgeTint = MaterialTheme.colorScheme.onTertiaryContainer
            typeLabel = "Instalador Android (APK)"
            primaryButtonText = if (isLocalAvailable) "Instalar APK" else "Baixar e Instalar"
            primaryButtonIcon = Icons.Rounded.InstallMobile
        }
        "ZIP" -> {
            badgeIcon = Icons.Rounded.FolderZip
            badgeColor = MaterialTheme.colorScheme.secondaryContainer
            badgeTint = MaterialTheme.colorScheme.onSecondaryContainer
            typeLabel = "Arquivo Compactado (ZIP)"
            primaryButtonText = if (isLocalAvailable) "Extrair ZIP" else "Baixar e Extrair ZIP"
            primaryButtonIcon = Icons.Rounded.FolderZip
        }
        else -> {
            badgeIcon = Icons.Rounded.Description
            badgeColor = MaterialTheme.colorScheme.surfaceVariant
            badgeTint = MaterialTheme.colorScheme.onSurfaceVariant
            typeLabel = "Arquivo"
            primaryButtonText = if (isLocalAvailable) "Abrir arquivo" else "Baixar arquivo"
            primaryButtonIcon = if (isLocalAvailable) Icons.AutoMirrored.Rounded.OpenInNew else Icons.Rounded.Download
        }
    }

    val displayName = message.fileName?.takeIf { it.isNotBlank() } ?: when (message.messageType) {
        "PDF" -> "documento.pdf"
        "APK" -> "aplicativo.apk"
        "ZIP" -> "arquivo.zip"
        else -> "anexo"
    }
    val sizeText = if ((message.fileSizeBytes ?: 0L) > 0L) {
        "${formatFileSize(message.fileSizeBytes)} • $typeLabel"
    } else {
        typeLabel
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("file_card_${message.messageType.lowercase()}_${message.id}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = badgeColor,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = badgeIcon,
                            contentDescription = typeLabel,
                            tint = badgeTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (downloadProgress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Text(
                        text = "Baixando ${(downloadProgress * 100).toInt()}%…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onPrimaryAction,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("file_primary_action_${message.id}")
                    ) {
                        Icon(
                            imageVector = primaryButtonIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = primaryButtonText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (onSecondaryAction != null && message.messageType == "PDF") {
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("pdf_open_external_${message.id}")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Abrir PDF externamente",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InAppPdfViewerDialog(
    pdfFile: File,
    displayName: String,
    onOpenExternal: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val pageBitmaps by produceState<List<Bitmap>?>(initialValue = null, key1 = pdfFile.absolutePath) {
        value = withContext(Dispatchers.IO) {
            renderPdfPages(pdfFile)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Bitmaps are GC'd safely after composition ends
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val count = pageBitmaps?.size
                        Text(
                            text = if (count != null) "$count página(s) • ${formatFileSize(pdfFile.length())}"
                            else formatFileSize(pdfFile.length()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { onOpenExternal(pdfFile) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("Abrir")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fechar PDF")
                    }
                }

                when (val pages = pageBitmaps) {
                    null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    emptyList<Bitmap>() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Não foi possível renderizar este PDF internamente.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { onOpenExternal(pdfFile) }) {
                                Text("Abrir em outro aplicativo")
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        ) {
                            items(pages.size) { idx ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    shadowElevation = 2.dp,
                                    color = Color.White,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Image(
                                        bitmap = pages[idx].asImageBitmap(),
                                        contentDescription = "Página ${idx + 1}",
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ZipExtractionDialogs(
    step: ZipPromptStep,
    archiveName: String,
    defaultPathSummary: String,
    onYesDefault: () -> Unit,
    onChooseCustomPath: () -> Unit,
    onNoFirstPrompt: () -> Unit,
    onConfirmYesAfterNo: () -> Unit,
    onConfirmNoCancel: () -> Unit
) {
    when (step) {
        ZipPromptStep.INITIAL -> {
            AlertDialog(
                onDismissRequest = onConfirmNoCancel,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = "Deseja extrair o zip",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Arquivo: $archiveName",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Caminho padrão do app:\n$defaultPathSummary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onYesDefault,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("zip_extract_yes")
                        ) {
                            Text("Sim")
                        }
                        OutlinedButton(
                            onClick = onChooseCustomPath,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("zip_extract_custom_path")
                        ) {
                            Text("Sim, mas no caminho que quero")
                        }
                        OutlinedButton(
                            onClick = onNoFirstPrompt,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("zip_extract_no")
                        ) {
                            Text("Não")
                        }
                    }
                },
                confirmButton = {}
            )
        }
        ZipPromptStep.CONFIRM_CANCEL -> {
            AlertDialog(
                onDismissRequest = onConfirmNoCancel,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                },
                title = {
                    Text(
                        text = "Mas você quer extrair o zip?",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Escolha \"Sim\" para extrair \"$archiveName\" no caminho padrão ou \"Não\" para cancelar.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onConfirmYesAfterNo,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("zip_confirm_yes")
                    ) {
                        Text("Sim")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onConfirmNoCancel,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("zip_confirm_no")
                    ) {
                        Text("Não")
                    }
                }
            )
        }
    }
}

@Composable
fun ApkInstallPermissionDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    description: String = "Para instalar aplicativos enviados no chat, autorize \"Instalar apps desconhecidos\" para o WhatwillIdo nas configurações do Android."
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Permissão para instalar APK") },
        text = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Permitir nas Configurações")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancelar")
            }
        }
    )
}

fun launchApkInstall(
    context: Context,
    apkFile: File,
    onNeedPermission: () -> Unit,
    onError: (String) -> Unit
) {
    if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
        onError("Arquivo APK inválido, vazio ou não encontrado.")
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        onNeedPermission()
        return
    }
    runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { err ->
        onError(err.localizedMessage ?: "Nenhum instalador de pacotes encontrado para abrir o APK.")
    }
}

fun buildManageUnknownAppSourcesIntent(context: Context): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

fun openLocalFileWithFileProvider(
    context: Context,
    file: File,
    mimeType: String,
    onError: (String) -> Unit
) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Abrir com"))
    }.onFailure { err ->
        onError(err.localizedMessage ?: "Nenhum aplicativo compatível encontrado para abrir este arquivo.")
    }
}

private fun renderPdfPages(file: File): List<Bitmap> {
    return runCatching {
        val list = mutableListOf<Bitmap>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val maxPages = renderer.pageCount.coerceAtMost(35)
                for (i in 0 until maxPages) {
                    renderer.openPage(i).use { page ->
                        val targetWidth = 1080
                        val targetHeight = ((targetWidth.toFloat() / page.width.coerceAtLeast(1)) * page.height)
                            .toInt()
                            .coerceIn(200, 2400)
                        val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        list.add(bmp)
                    }
                }
            }
        }
        list
    }.getOrElse { emptyList() }
}
