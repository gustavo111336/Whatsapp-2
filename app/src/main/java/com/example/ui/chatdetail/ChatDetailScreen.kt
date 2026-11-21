package com.example.ui.chatdetail

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.UiState
import com.example.data.model.ZipExtractionResult
import com.example.data.remote.FreeMediaStorageManager
import com.example.service.ZipArchiveHelper
import com.example.ui.components.TypingDotsAnimated
import com.example.ui.components.Zap2Avatar
import com.example.ui.components.formatClockTime
import com.example.ui.components.formatFileSize
import com.example.ui.theme.Zap2ExpressiveShapes
import com.example.ui.theme.ZapOnlineGreen
import com.example.ui.theme.ZapReadBlue
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    viewModel: ChatDetailViewModel,
    onBack: () -> Unit,
    onOpenGroupInfo: () -> Unit,
    onStartCall: (callId: String, isVideo: Boolean) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val chatRoom by viewModel.chatRoom.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val typingUsers by viewModel.typingUsers.collectAsStateWithLifecycle()
    val peerProfile by viewModel.peerProfile.collectAsStateWithLifecycle()
    val participantProfiles by viewModel.participantProfiles.collectAsStateWithLifecycle()
    val draftText by viewModel.draftText.collectAsStateWithLifecycle()
    val isSendingMedia by viewModel.isSendingMedia.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val fileDownloadProgress by viewModel.fileDownloadProgress.collectAsStateWithLifecycle()
    val defaultZipExtractUri by viewModel.defaultZipExtractUri.collectAsStateWithLifecycle()
    val errorBanner by viewModel.errorBanner.collectAsStateWithLifecycle()
    val editingMessage by viewModel.editingMessage.collectAsStateWithLifecycle()

    val isRecording by viewModel.audioPlayer.isRecording.collectAsStateWithLifecycle()
    val recordingDurationMs by viewModel.audioPlayer.recordingDurationMs.collectAsStateWithLifecycle()
    val recordingAmplitude by viewModel.audioPlayer.amplitude.collectAsStateWithLifecycle()
    val playingMessageId by viewModel.audioPlayer.playingMessageId.collectAsStateWithLifecycle()
    val playbackProgress by viewModel.audioPlayer.playbackProgress.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var pendingCallIsVideo by remember { mutableStateOf<Boolean?>(null) }
    var selectedMessageForActions by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteConfirmMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var activeVideoFile by remember { mutableStateOf<File?>(null) }
    var activePdfPreview by remember { mutableStateOf<Pair<File, String>?>(null) }
    var pendingApkInstallFile by remember { mutableStateOf<File?>(null) }
    var showApkPermissionDialog by remember { mutableStateOf(false) }
    var activeZipPrompt by remember { mutableStateOf<Pair<File, String>?>(null) }
    var zipPromptStep by remember { mutableStateOf(ZipPromptStep.INITIAL) }
    var pendingZipForCustomTree by remember { mutableStateOf<Pair<File, String>?>(null) }
    var zipExtractionDoneResult by remember { mutableStateOf<ZipExtractionResult?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.sendImage(uri)
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.sendVideo(uri)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.sendDocument(uri)
        }
    }

    val zipTreePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val pending = pendingZipForCustomTree
        pendingZipForCustomTree = null
        if (treeUri != null && pending != null) {
            viewModel.extractZipToCustomUri(
                context = context,
                zipFile = pending.first,
                archiveDisplayName = pending.second,
                treeUri = treeUri
            ) { result ->
                zipExtractionDoneResult = result
            }
        } else if (pending != null) {
            Toast.makeText(context, "Seleção de pasta cancelada.", Toast.LENGTH_SHORT).show()
        }
    }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val pendingApk = pendingApkInstallFile
        if (pendingApk != null) {
            pendingApkInstallFile = null
            launchApkInstall(
                context = context,
                apkFile = pendingApk,
                onNeedPermission = {
                    viewModel.reportError("Permissão de fontes desconhecidas ainda não concedida.")
                },
                onError = { err -> viewModel.reportError(err) }
            )
        }
    }

    val audioRecordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.audioPlayer.startRecording()
        }
    }

    val callPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val isVideo = pendingCallIsVideo ?: false
        pendingCallIsVideo = null
        val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
        val videoOk = !isVideo || grants[Manifest.permission.CAMERA] == true
        if (audioOk && videoOk) {
            viewModel.startCall(isVideo, onStartCall)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.onDraftChanged("")
            viewModel.audioPlayer.stopPlayback()
        }
    }

    val title = chatRoom?.resolveDisplayTitle(viewModel.currentUserId) ?: "Conversa"
    val isGroup = chatRoom?.chatType == "GROUP"

    val statusSubtitle = when {
        typingUsers.isNotEmpty() -> {
            if (isGroup) {
                "${typingUsers.joinToString(", ") { it.displayName }} digitando..."
            } else {
                "Digitando..."
            }
        }
        !isGroup && peerProfile?.online == true -> "Online"
        !isGroup && peerProfile?.lastSeen != null -> {
            "Visto por último às ${formatClockTime(peerProfile?.lastSeen?.toDate())}"
        }
        isGroup -> "${chatRoom?.participantUids?.size ?: 1} participantes"
        else -> "Offline"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isGroup, onClick = onOpenGroupInfo)
                    ) {
                        Zap2Avatar(
                            name = title,
                            photoUrl = if (isGroup) chatRoom?.groupPhotoUrl else peerProfile?.photoUrl,
                            isGroup = isGroup,
                            online = !isGroup && peerProfile?.online == true,
                            size = 40.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (typingUsers.isNotEmpty()) {
                                    TypingDotsAnimated(color = MaterialTheme.colorScheme.primary)
                                } else if (!isGroup && peerProfile?.online == true) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(ZapOnlineGreen)
                                    )
                                }
                                Text(
                                    text = statusSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (typingUsers.isNotEmpty() || peerProfile?.online == true) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("chat_presence_subtitle")
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val hasAudio = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio) {
                                viewModel.startCall(isVideo = false, onStartCall)
                            } else {
                                pendingCallIsVideo = false
                                callPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            }
                        },
                        modifier = Modifier.testTag("start_audio_call_button")
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = "Chamada de voz")
                    }
                    IconButton(
                        onClick = {
                            val hasAudio = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            val hasCam = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio && hasCam) {
                                viewModel.startCall(isVideo = true, onStartCall)
                            } else {
                                pendingCallIsVideo = true
                                callPermissionsLauncher.launch(
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                                )
                            }
                        },
                        modifier = Modifier.testTag("start_video_call_button")
                    ) {
                        Icon(Icons.Rounded.Videocam, contentDescription = "Chamada de vídeo")
                    }
                    if (isGroup) {
                        IconButton(
                            onClick = onOpenGroupInfo,
                            modifier = Modifier.testTag("group_info_button")
                        ) {
                            Icon(Icons.Rounded.Info, contentDescription = "Dados do grupo")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                val activeUpload = uploadProgress
                if (activeUpload != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("upload_progress_banner")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Enviando ${activeUpload.fileName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${(activeUpload.progress * 100).toInt()}% • ${formatFileSize(activeUpload.bytesTransferred)} / ${formatFileSize(activeUpload.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { activeUpload.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else if (isSendingMedia) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (errorBanner != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.dismissError() }
                    ) {
                        Text(
                            text = errorBanner.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                val activeEdit = editingMessage
                if (activeEdit != null && !isRecording) {
                    EditingMessageBanner(
                        originalText = activeEdit.text,
                        onCancel = viewModel::cancelEditing
                    )
                }

                if (isRecording) {
                    VoiceRecordingBar(
                        durationMs = recordingDurationMs,
                        amplitude = recordingAmplitude,
                        onDiscard = { viewModel.audioPlayer.stopRecording(discard = true) },
                        onSend = {
                            val result = viewModel.audioPlayer.stopRecording(discard = false)
                            if (result != null) {
                                viewModel.sendVoiceNote(result.first, result.second)
                            }
                        }
                    )
                } else {
                    MessageComposerBar(
                        text = draftText,
                        isEditing = activeEdit != null,
                        onTextChange = viewModel::onDraftChanged,
                        onPickImage = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickVideo = {
                            videoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onPickPdf = {
                            documentPickerLauncher.launch(arrayOf("application/pdf"))
                        },
                        onPickApk = {
                            documentPickerLauncher.launch(
                                arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")
                            )
                        },
                        onPickZip = {
                            documentPickerLauncher.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                            )
                        },
                        onPickGenericFile = {
                            documentPickerLauncher.launch(arrayOf("*/*"))
                        },
                        onStartVoiceRecord = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.audioPlayer.startRecording()
                            } else {
                                audioRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onSendText = viewModel::sendTextMessage
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter
        ) {
            when (val state = messagesState) {
                UiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                is UiState.Success -> {
                    val messages = state.data
                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.lastIndex)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                    ) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { index, msg ->
                            val prevSender = messages.getOrNull(index - 1)?.senderId
                            val isFirstInGroup = prevSender != msg.senderId
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + slideInVertically(
                                    initialOffsetY = { 24 },
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            ) {
                                val senderPhoto = participantProfiles[msg.senderId]?.photoUrl
                                    ?: if (!isGroup && msg.senderId != viewModel.currentUserId) peerProfile?.photoUrl else null

                                MessageBubbleRow(
                                    message = msg,
                                    isMine = msg.senderId == viewModel.currentUserId,
                                    isGroup = isGroup,
                                    isFirstInGroup = isFirstInGroup,
                                    senderPhotoUrl = senderPhoto,
                                    isPlayingAudio = playingMessageId == msg.id,
                                    audioProgress = if (playingMessageId == msg.id) playbackProgress else 0f,
                                    downloadProgress = fileDownloadProgress[msg.id],
                                    onToggleAudio = {
                                        val localPath = msg.localMediaPath?.takeIf { File(it).exists() }
                                        val remoteUrl = msg.mediaUrl
                                        if (localPath != null) {
                                            viewModel.audioPlayer.togglePlayback(msg.id, localPath)
                                        } else if (!remoteUrl.isNullOrBlank()) {
                                            if (FreeMediaStorageManager.isChunkUri(remoteUrl) || remoteUrl.contains("github")) {
                                                viewModel.downloadAndPrepareFile(msg) { downloaded ->
                                                    viewModel.audioPlayer.togglePlayback(msg.id, downloaded.absolutePath)
                                                }
                                            } else {
                                                viewModel.audioPlayer.togglePlayback(msg.id, remoteUrl)
                                            }
                                        }
                                    },
                                    onPlayVideo = { file -> activeVideoFile = file },
                                    onOpenFilePrimary = { clickedMsg ->
                                        viewModel.downloadAndPrepareFile(clickedMsg) { localFile ->
                                            val displayName = clickedMsg.fileName?.ifBlank { localFile.name } ?: localFile.name
                                            when (clickedMsg.messageType) {
                                                "PDF" -> activePdfPreview = localFile to displayName
                                                "APK" -> {
                                                    launchApkInstall(
                                                        context = context,
                                                        apkFile = localFile,
                                                        onNeedPermission = {
                                                            pendingApkInstallFile = localFile
                                                            showApkPermissionDialog = true
                                                        },
                                                        onError = { err -> viewModel.reportError(err) }
                                                    )
                                                }
                                                "ZIP" -> {
                                                    activeZipPrompt = localFile to displayName
                                                    zipPromptStep = ZipPromptStep.INITIAL
                                                }
                                                else -> {
                                                    openLocalFileWithFileProvider(
                                                        context = context,
                                                        file = localFile,
                                                        mimeType = clickedMsg.mimeType ?: "*/*",
                                                        onError = { err -> viewModel.reportError(err) }
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onOpenFileExternal = { clickedMsg ->
                                        viewModel.downloadAndPrepareFile(clickedMsg) { localFile ->
                                            openLocalFileWithFileProvider(
                                                context = context,
                                                file = localFile,
                                                mimeType = clickedMsg.mimeType ?: "application/pdf",
                                                onError = { err -> viewModel.reportError(err) }
                                            )
                                        }
                                    },
                                    onOpenUrl = { url ->
                                        runCatching { uriHandler.openUri(url) }
                                    },
                                    onLongClick = {
                                        if (msg.messageType != "SYSTEM") {
                                            selectedMessageForActions = msg
                                        }
                                    }
                                )
                            }
                        }

                        if (typingUsers.isNotEmpty()) {
                            item(key = "typing_bubble") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Surface(
                                        shape = Zap2ExpressiveShapes.IncomingBubbleFirst,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            TypingDotsAnimated(color = MaterialTheme.colorScheme.primary)
                                            Text(
                                                text = if (isGroup) {
                                                    "${typingUsers.first().displayName} digitando…"
                                                } else {
                                                    stringResource(R.string.typing_indicator)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    }

    val actionMsg = selectedMessageForActions
    if (actionMsg != null) {
        val isMine = actionMsg.senderId == viewModel.currentUserId
        MessageActionsDialog(
            message = actionMsg,
            isMine = isMine,
            onDismiss = { selectedMessageForActions = null },
            onCopy = {
                clipboardManager.setText(AnnotatedString(actionMsg.text))
                selectedMessageForActions = null
                Toast.makeText(context, "Mensagem copiada", Toast.LENGTH_SHORT).show()
            },
            onEdit = {
                selectedMessageForActions = null
                viewModel.startEditingMessage(actionMsg)
            },
            onRequestDelete = {
                selectedMessageForActions = null
                if (isMine) {
                    deleteConfirmMessage = actionMsg
                } else {
                    viewModel.deleteMessageForMe(actionMsg)
                }
            }
        )
    }

    val delMsg = deleteConfirmMessage
    if (delMsg != null) {
        DeleteMessageConfirmDialog(
            isMine = delMsg.senderId == viewModel.currentUserId,
            onDismiss = { deleteConfirmMessage = null },
            onDeleteForMe = {
                deleteConfirmMessage = null
                viewModel.deleteMessageForMe(delMsg)
            },
            onDeleteForEveryone = {
                deleteConfirmMessage = null
                viewModel.deleteMessageForEveryone(delMsg)
            }
        )
    }

    val playingVideo = activeVideoFile
    if (playingVideo != null) {
        VideoPlayerDialog(
            videoFile = playingVideo,
            onDismiss = { activeVideoFile = null }
        )
    }

    val pdfPair = activePdfPreview
    if (pdfPair != null) {
        InAppPdfViewerDialog(
            pdfFile = pdfPair.first,
            displayName = pdfPair.second,
            onOpenExternal = { file ->
                openLocalFileWithFileProvider(
                    context = context,
                    file = file,
                    mimeType = "application/pdf",
                    onError = { err -> viewModel.reportError(err) }
                )
            },
            onDismiss = { activePdfPreview = null }
        )
    }

    if (showApkPermissionDialog) {
        ApkInstallPermissionDialog(
            onOpenSettings = {
                showApkPermissionDialog = false
                unknownSourcesLauncher.launch(buildManageUnknownAppSourcesIntent(context))
            },
            onDismiss = {
                showApkPermissionDialog = false
                pendingApkInstallFile = null
            }
        )
    }

    val zipPair = activeZipPrompt
    if (zipPair != null) {
        ZipExtractionDialogs(
            step = zipPromptStep,
            archiveName = zipPair.second,
            defaultPathSummary = remember(defaultZipExtractUri) {
                ZipArchiveHelper.formatDefaultExtractSummary(context, defaultZipExtractUri)
            },
            onYesDefault = {
                activeZipPrompt = null
                viewModel.extractZipToDefault(
                    context = context,
                    zipFile = zipPair.first,
                    archiveDisplayName = zipPair.second
                ) { result ->
                    zipExtractionDoneResult = result
                }
            },
            onChooseCustomPath = {
                activeZipPrompt = null
                pendingZipForCustomTree = zipPair
                zipTreePickerLauncher.launch(null)
            },
            onNoFirstPrompt = {
                zipPromptStep = ZipPromptStep.CONFIRM_CANCEL
            },
            onConfirmYesAfterNo = {
                activeZipPrompt = null
                viewModel.extractZipToDefault(
                    context = context,
                    zipFile = zipPair.first,
                    archiveDisplayName = zipPair.second
                ) { result ->
                    zipExtractionDoneResult = result
                }
            },
            onConfirmNoCancel = {
                activeZipPrompt = null
                Toast.makeText(context, "Extração do ZIP cancelada.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    val doneZip = zipExtractionDoneResult
    if (doneZip != null) {
        AlertDialog(
            onDismissRequest = { zipExtractionDoneResult = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.FolderZip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("ZIP extraído com sucesso") },
            text = {
                Text(
                    text = "${doneZip.extractedFilesCount} arquivo(s) extraído(s) para:\n${doneZip.destinationPath ?: doneZip.destinationLabel}",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { zipExtractionDoneResult = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun EditingMessageBanner(
    originalText: String,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("editing_message_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Editando mensagem",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = originalText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.testTag("cancel_edit_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancelar edição",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun MessageActionsDialog(
    message: ChatMessage,
    isMine: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opções da mensagem") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Surface(
                        onClick = onCopy,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("action_copy_message")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                            Text("Copiar", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                if (isMine && message.messageType == "TEXT") {
                    Surface(
                        onClick = onEdit,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("action_edit_message")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                            Text("Editar", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                Surface(
                    onClick = onRequestDelete,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("action_delete_message")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = if (isMine) "Apagar" else "Apagar para mim",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )
}

@Composable
private fun DeleteMessageConfirmDialog(
    isMine: Boolean,
    onDismiss: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apagar mensagem?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "A mensagem será removida sem deixar rastros na conversa.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    onClick = onDeleteForMe,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("delete_for_me_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Text("Apagar para mim", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (isMine) {
                    Surface(
                        onClick = onDeleteForEveryone,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_for_everyone_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Rounded.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Apagar para todos",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleRow(
    message: ChatMessage,
    isMine: Boolean,
    isGroup: Boolean,
    isFirstInGroup: Boolean,
    senderPhotoUrl: String?,
    isPlayingAudio: Boolean,
    audioProgress: Float,
    downloadProgress: Float?,
    onToggleAudio: () -> Unit,
    onPlayVideo: (File) -> Unit,
    onOpenFilePrimary: (ChatMessage) -> Unit,
    onOpenFileExternal: (ChatMessage) -> Unit,
    onOpenUrl: (String) -> Unit,
    onLongClick: () -> Unit
) {
    if (message.messageType == "SYSTEM") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
        return
    }

    val bubbleShape = when {
        isMine && isFirstInGroup -> Zap2ExpressiveShapes.OutgoingBubbleFirst
        isMine -> Zap2ExpressiveShapes.OutgoingBubbleGrouped
        isFirstInGroup -> Zap2ExpressiveShapes.IncomingBubbleFirst
        else -> Zap2ExpressiveShapes.IncomingBubbleGrouped
    }

    val bubbleColor = if (isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isMine) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val linkBlue = if (isDark) Color(0xFF64B5F6) else Color(0xFF1565C0)
    val ytInfo = remember(message.id, message.text) {
        if (message.text.isNotBlank()) extractFirstYouTubeInfo(message.text) else null
    }
    val annotatedBody = remember(message.id, message.text, linkBlue) {
        if (message.text.isNotBlank()) buildMessageAnnotatedText(message.text, linkBlue) else null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirstInGroup) 6.dp else 1.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isMine) {
            if (isFirstInGroup) {
                Zap2Avatar(
                    name = message.senderName.ifBlank { "Zap2" },
                    photoUrl = senderPhotoUrl,
                    size = 30.dp,
                    modifier = Modifier.padding(end = 6.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(36.dp))
            }
        }
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            tonalElevation = if (isMine) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(min = 84.dp, max = 300.dp)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isGroup && !isMine && isFirstInGroup) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                when (message.messageType) {
                    "IMAGE" -> {
                        val context = LocalContext.current
                        val model by produceState<Any?>(
                            initialValue = null,
                            key1 = message.id,
                            key2 = message.localMediaPath,
                            key3 = message.mediaUrl
                        ) {
                            val localFile = message.localMediaPath?.let(::File)?.takeIf { it.exists() }
                            val remoteUrl = message.mediaUrl
                            value = when {
                                localFile != null -> localFile
                                remoteUrl?.startsWith("data:image/") == true -> {
                                    val b64 = remoteUrl.substringAfter(",", "")
                                    runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                                }
                                FreeMediaStorageManager.requiresManagedResolution(remoteUrl) -> {
                                    FreeMediaStorageManager.resolveMediaModel(context, remoteUrl)
                                }
                                else -> remoteUrl
                            }
                        }
                        if (model != null) {
                            AsyncImage(
                                model = model,
                                contentDescription = "Imagem enviada",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                        if (annotatedBody != null) {
                            Text(
                                text = annotatedBody,
                                style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
                            )
                        }
                    }
                    "VIDEO" -> {
                        VideoMessageBubbleContent(
                            message = message,
                            onPlayVideo = onPlayVideo
                        )
                        if (annotatedBody != null) {
                            Text(
                                text = annotatedBody,
                                style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
                            )
                        }
                    }
                    "AUDIO" -> {
                        val seconds = ((message.mediaDurationMs ?: 0L) / 1000L).coerceAtLeast(1L)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable(onClick = onToggleAudio)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlayingAudio) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlayingAudio) "Pausar áudio" else "Reproduzir áudio",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                LinearProgressIndicator(
                                    progress = { if (isPlayingAudio) audioProgress else 0f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textColor.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                    "PDF", "APK", "ZIP", "FILE" -> {
                        FileAttachmentMessageCard(
                            message = message,
                            downloadProgress = downloadProgress,
                            onPrimaryAction = { onOpenFilePrimary(message) },
                            onSecondaryAction = if (message.messageType == "PDF") {
                                { onOpenFileExternal(message) }
                            } else {
                                null
                            }
                        )
                        if (annotatedBody != null) {
                            Text(
                                text = annotatedBody,
                                style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
                            )
                        }
                    }
                    else -> {
                        if (annotatedBody != null) {
                            Text(
                                text = annotatedBody,
                                style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
                            )
                        }
                    }
                }

                if (ytInfo != null) {
                    YouTubePreviewCard(
                        info = ytInfo,
                        onOpenUrl = onOpenUrl,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (message.edited) {
                        Text(
                            text = "Editada",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.65f)
                        )
                    }
                    Text(
                        text = formatClockTime(message.createdAt?.toDate()),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.65f)
                    )
                    if (isMine) {
                        val isReadByOther = message.readBy.any { it != message.senderId }
                        Icon(
                            imageVector = Icons.Rounded.DoneAll,
                            contentDescription = if (isReadByOther) "Lida" else "Entregue",
                            tint = if (isReadByOther) ZapReadBlue else textColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageComposerBar(
    text: String,
    isEditing: Boolean,
    onTextChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickPdf: () -> Unit,
    onPickApk: () -> Unit,
    onPickZip: () -> Unit,
    onPickGenericFile: () -> Unit,
    onStartVoiceRecord: () -> Unit,
    onSendText: () -> Unit
) {
    var showAttachMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp)
            ) {
                Box {
                    IconButton(
                        onClick = { showAttachMenu = true },
                        modifier = Modifier.testTag("pick_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AttachFile,
                            contentDescription = "Anexar foto, vídeo ou arquivo",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Foto") },
                            leadingIcon = {
                                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                            },
                            onClick = {
                                showAttachMenu = false
                                onPickImage()
                            },
                            modifier = Modifier.testTag("pick_photo_menu_item")
                        )
                        DropdownMenuItem(
                            text = { Text("Vídeo") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Videocam, contentDescription = null)
                            },
                            onClick = {
                                showAttachMenu = false
                                onPickVideo()
                            },
                            modifier = Modifier.testTag("pick_video_menu_item")
                        )
                        DropdownMenuItem(
                            text = { Text("Documento PDF") },
                            leadingIcon = {
                                Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                            },
                            onClick = {
                                showAttachMenu = false
                                onPickPdf()
                            },
                            modifier = Modifier.testTag("pick_pdf_menu_item")
                        )
                        DropdownMenuItem(
                            text = { Text("Aplicativo APK") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Android, contentDescription = null)
                            },
                            onClick = {
                                showAttachMenu = false
                                onPickApk()
                            },
                            modifier = Modifier.testTag("pick_apk_menu_item")
                        )
                        DropdownMenuItem(
                            text = { Text("Arquivo ZIP") },
                            leadingIcon = {
                                Icon(Icons.Rounded.FolderZip, contentDescription = null)
                            },
                            onClick = {
                                showAttachMenu = false
                                onPickZip()
                            },
                            modifier = Modifier.testTag("pick_zip_menu_item")
                        )
                        DropdownMenuItem(
                            text = { Text("Outro arquivo") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Description, contentDescription = null)
                            },
                            onClick = {
                                showAttachMenu = false
                                onPickGenericFile()
                            },
                            modifier = Modifier.testTag("pick_generic_file_menu_item")
                        )
                    }
                }
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text(stringResource(R.string.type_message_hint)) },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("message_text_input")
                )
            }
        }

        val hasText = text.isNotBlank()
        Surface(
            onClick = { if (hasText) onSendText() else onStartVoiceRecord() },
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(52.dp)
                .testTag(if (hasText) "send_message_button" else "record_voice_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(targetState = Pair(hasText, isEditing), label = "send_mic_morph") { (showSend, editing) ->
                    Icon(
                        imageVector = when {
                            showSend && editing -> Icons.Rounded.Check
                            showSend -> Icons.AutoMirrored.Rounded.Send
                            else -> Icons.Rounded.Mic
                        },
                        contentDescription = when {
                            showSend && editing -> "Salvar edição"
                            showSend -> "Enviar mensagem"
                            else -> "Gravar áudio"
                        },
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceRecordingBar(
    durationMs: Long,
    amplitude: Float,
    onDiscard: () -> Unit,
    onSend: () -> Unit
) {
    val secs = durationMs / 1000L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(onClick = onDiscard) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "Descartar áudio",
                tint = MaterialTheme.colorScheme.error
            )
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Text(
                    text = "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.width(6.dp))
                LinearProgressIndicator(
                    progress = { amplitude },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(CircleShape)
                )
            }
        }
        Surface(
            onClick = onSend,
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(52.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Enviar áudio",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
