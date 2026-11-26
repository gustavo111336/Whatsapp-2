package com.example.ui.chatdetail

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.view.WindowManager
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.SavedStickerGifEntity
import com.example.data.local.ScheduledMessageEntity
import com.example.data.model.ChatMessage
import com.example.data.remote.FreeMediaStorageManager
import com.example.service.AudioRecorderPlayer
import com.example.service.ScheduledMessageTimeUtils
import java.io.File
import java.util.Calendar
import java.util.TimeZone

@Composable
fun GifAndStickersTrayDialog(
    savedItems: List<SavedStickerGifEntity>,
    viewOnceMode: Boolean,
    onDismiss: () -> Unit,
    onOpenStickerCreator: () -> Unit,
    onPickGifFromGallery: () -> Unit,
    onOpenGifWithAudioCreator: () -> Unit,
    onSendSavedItem: (SavedStickerGifEntity) -> Unit,
    onDeleteSavedItem: (SavedStickerGifEntity) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val stickers = remember(savedItems) { savedItems.filter { it.kind == "STICKER" } }
    val gifs = remember(savedItems) { savedItems.filter { it.kind == "GIF" } }
    val gifAudios = remember(savedItems) { savedItems.filter { it.kind == "GIF_AUDIO" } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(min = 380.dp, max = 600.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Figs, GIFs e GIFs c/ Áudio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (viewOnceMode) {
                            Text(
                                text = "Modo 1x (View única) ativo ao enviar",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fechar")
                    }
                }

                // Quick creation actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenStickerCreator()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tray_create_fig_button")
                    ) {
                        Icon(Icons.Rounded.Brush, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Criar Fig", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onPickGifFromGallery()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tray_gallery_gif_button")
                    ) {
                        Icon(Icons.Rounded.GifBox, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("GIF Galeria", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenGifWithAudioCreator()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tray_create_gif_audio_button")
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("GIF+Áudio", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Figs (${stickers.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("GIFs (${gifs.size})") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("c/ Áudio (${gifAudios.size})") }
                    )
                }

                val currentItems = when (selectedTab) {
                    0 -> stickers
                    1 -> gifs
                    else -> gifAudios
                }

                if (currentItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (selectedTab) {
                                0 -> "Nenhuma Fig salva ainda.\nToque em \"Criar Fig\" ou salve figs recebidas no chat."
                                1 -> "Nenhum GIF salvo ainda.\nToque em \"Salvar GIF\" em qualquer GIF da conversa."
                                else -> "Nenhum GIF com áudio salvo.\nToque em \"GIF+Áudio\" para gravar um agora."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(currentItems, key = { it.id }) { item ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSendSavedItem(item)
                                        onDismiss()
                                    }
                            ) {
                                Box(modifier = Modifier.height(106.dp)) {
                                    AsyncImage(
                                        model = File(item.localFilePath),
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (item.kind == "GIF_AUDIO") {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.Black.copy(alpha = 0.65f),
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.MusicNote,
                                                contentDescription = "Com áudio",
                                                tint = Color.White,
                                                modifier = Modifier
                                                    .padding(3.dp)
                                                    .size(14.dp)
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.55f),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(24.dp)
                                            .clickable { onDeleteSavedItem(item) }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.Delete,
                                                contentDescription = "Remover",
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
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
}

@Composable
fun GifWithAudioCreatorDialog(
    audioPlayer: AudioRecorderPlayer,
    savedGifs: List<SavedStickerGifEntity>,
    onDismiss: () -> Unit,
    onSendGifWithAudio: (
        gifUri: Uri?,
        savedGifFile: File?,
        audioFile: File,
        durationMs: Long,
        saveToCollection: Boolean,
        title: String
    ) -> Unit
) {
    val context = LocalContext.current
    var pickedGifUri by remember { mutableStateOf<Uri?>(null) }
    var selectedSavedGif by remember { mutableStateOf<SavedStickerGifEntity?>(null) }
    var recordedAudio by remember { mutableStateOf<Pair<File, Long>?>(null) }
    var saveToCollection by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("GIF com Áudio") }

    val isRecording by audioPlayer.isRecording.collectAsStateWithLifecycle()
    val recordingDurationMs by audioPlayer.recordingDurationMs.collectAsStateWithLifecycle()

    val gifPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedGifUri = uri
            selectedSavedGif = null
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            audioPlayer.startRecording()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (audioPlayer.isRecording.value) {
                audioPlayer.stopRecording(discard = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Criar GIF com Áudio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fechar")
                    }
                }

                val previewModel: Any? = pickedGifUri ?: selectedSavedGif?.localFilePath?.let(::File)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewModel != null) {
                        AsyncImage(
                            model = previewModel,
                            contentDescription = "GIF selecionado",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = "1. Escolha um GIF da Galeria ou dos seus salvos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        gifPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gif_audio_pick_gif_button")
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Escolher GIF / Imagem da Galeria")
                }

                val reusableGifs = savedGifs.filter { it.kind == "GIF" || it.kind == "GIF_AUDIO" }
                if (reusableGifs.isNotEmpty()) {
                    Text("Ou use um GIF salvo:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        reusableGifs.take(4).forEach { saved ->
                            Surface(
                                onClick = {
                                    selectedSavedGif = saved
                                    pickedGifUri = null
                                },
                                shape = RoundedCornerShape(10.dp),
                                tonalElevation = if (selectedSavedGif?.id == saved.id) 6.dp else 1.dp,
                                color = if (selectedSavedGif?.id == saved.id) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                modifier = Modifier.size(64.dp)
                            ) {
                                AsyncImage(
                                    model = File(saved.localFilePath),
                                    contentDescription = saved.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }

                // Step 2: Record Audio
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "2. Áudio para tocar com o GIF",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isRecording) {
                                Button(
                                    onClick = {
                                        recordedAudio = audioPlayer.stopRecording(discard = false)
                                    }
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    val secs = recordingDurationMs / 1000L
                                    Text("Parar (${secs}s)")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            audioPlayer.startRecording()
                                        } else {
                                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    modifier = Modifier.testTag("gif_audio_record_button")
                                ) {
                                    Icon(Icons.Rounded.Mic, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (recordedAudio == null) "Gravar Áudio" else "Regravar Áudio")
                                }
                            }

                            val currentAudio = recordedAudio
                            if (currentAudio != null && !isRecording) {
                                val sec = (currentAudio.second / 1000L).coerceAtLeast(1L)
                                Text(
                                    text = "Pronto (${sec}s)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { saveToCollection = !saveToCollection }
                ) {
                    Checkbox(
                        checked = saveToCollection,
                        onCheckedChange = { saveToCollection = it }
                    )
                    Text("Salvar na minha coleção de GIFs c/ Áudio", style = MaterialTheme.typography.bodySmall)
                }

                if (saveToCollection) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(36) },
                        label = { Text("Nome na coleção") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val canSend = (pickedGifUri != null || selectedSavedGif != null) && recordedAudio != null && !isRecording
                Button(
                    onClick = {
                        val audio = recordedAudio ?: return@Button
                        onSendGifWithAudio(
                            pickedGifUri,
                            selectedSavedGif?.localFilePath?.let(::File),
                            audio.first,
                            audio.second,
                            saveToCollection,
                            title
                        )
                        onDismiss()
                    },
                    enabled = canSend,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gif_audio_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enviar GIF com Áudio")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleMessageDialog(
    initialText: String,
    initialViewOnce: Boolean,
    savedItems: List<SavedStickerGifEntity>,
    onDismiss: () -> Unit,
    onConfirmSchedule: (
        scheduledAtMs: Long,
        messageType: String,
        text: String,
        savedItem: SavedStickerGifEntity?,
        viewOnce: Boolean
    ) -> Unit
) {
    val defaultFuture = remember {
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
    }
    val initialUtcMillis = remember {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(defaultFuture.get(Calendar.YEAR), defaultFuture.get(Calendar.MONTH), defaultFuture.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialUtcMillis,
        yearRange = 2024..2035
    )
    val timePickerState = rememberTimePickerState(
        initialHour = 12,
        initialMinute = 0,
        is24Hour = true
    )

    var stepTab by remember { mutableIntStateOf(0) } // 0 = Data, 1 = Hora
    var text by remember { mutableStateOf(initialText) }
    var viewOnce by remember { mutableStateOf(initialViewOnce) }
    var selectedSavedItem by remember { mutableStateOf<SavedStickerGifEntity?>(null) }

    val selectedDateMs = datePickerState.selectedDateMillis ?: initialUtcMillis
    val targetEpochMs = remember(selectedDateMs, timePickerState.hour, timePickerState.minute) {
        ScheduledMessageTimeUtils.combineDateAndTimeMillis(
            utcDatePickerMillis = selectedDateMs,
            hour = timePickerState.hour,
            minute = timePickerState.minute
        )
    }
    val formattedTarget = remember(targetEpochMs) {
        ScheduledMessageTimeUtils.formatScheduledDateTime(targetEpochMs)
    }
    val isFutureValid = ScheduledMessageTimeUtils.isValidFutureSchedule(targetEpochMs)
    val canConfirm = isFutureValid && (text.isNotBlank() || selectedSavedItem != null)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Agendar Mensagem",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fechar")
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(1000) },
                    label = { Text("Mensagem para enviar no horário") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("schedule_message_text_input")
                )

                if (savedItems.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Opcional: anexar Fig/GIF salvo",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = selectedSavedItem == null,
                                onClick = { selectedSavedItem = null },
                                label = { Text("Só texto") }
                            )
                            savedItems.take(3).forEach { saved ->
                                FilterChip(
                                    selected = selectedSavedItem?.id == saved.id,
                                    onClick = { selectedSavedItem = saved },
                                    label = { Text(saved.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = if (viewOnce) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Visualização única (1x)", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = viewOnce, onCheckedChange = { viewOnce = it })
                }

                TabRow(selectedTabIndex = stepTab) {
                    Tab(
                        selected = stepTab == 0,
                        onClick = { stepTab = 0 },
                        text = { Text("1. Data") }
                    )
                    Tab(
                        selected = stepTab == 1,
                        onClick = { stepTab = 1 },
                        text = { Text("2. Hora") }
                    )
                }

                if (stepTab == 0) {
                    DatePicker(
                        state = datePickerState,
                        showModeToggle = true
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TimePicker(state = timePickerState)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isFutureValid) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isFutureValid) {
                            "Envio programado para $formattedTarget"
                        } else {
                            "Escolha uma data e hora no futuro ($formattedTarget já passou)"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isFutureValid) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Button(
                    onClick = {
                        val attached = selectedSavedItem
                        val msgType = attached?.kind ?: "TEXT"
                        onConfirmSchedule(targetEpochMs, msgType, text, attached, viewOnce)
                        onDismiss()
                    },
                    enabled = canConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confirm_schedule_message_button")
                ) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Agendar envio")
                }
            }
        }
    }
}

@Composable
fun ScheduledMessagesListDialog(
    scheduled: List<ScheduledMessageEntity>,
    onDismiss: () -> Unit,
    onSendNow: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mensagens Agendadas (${scheduled.size})") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scheduled.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = ScheduledMessageTimeUtils.formatScheduledDateTime(item.scheduledAtMs),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = buildString {
                                    append(item.messageType)
                                    if (item.viewOnce) append(" • 1x Única")
                                    if (item.text.isNotBlank()) append(": ${item.text}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.status == "FAILED" && !item.errorMessage.isNullOrBlank()) {
                                Text(
                                    text = "Falha: ${item.errorMessage}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onCancel(item.id) }) {
                                    Text("Cancelar", color = MaterialTheme.colorScheme.error)
                                }
                                TextButton(onClick = { onSendNow(item.id) }) {
                                    Text("Enviar agora")
                                }
                            }
                        }
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
fun ViewOnceViewerDialog(
    message: ChatMessage,
    onPrepareContent: (ChatMessage, (mainFile: File?, audioFile: File?) -> Unit, (String) -> Unit) -> Unit,
    onOpenedAndConsumed: (ChatMessage) -> Unit,
    onDismiss: () -> Unit
) {
    var mainFile by remember(message.id) { mutableStateOf<File?>(null) }
    var audioFile by remember(message.id) { mutableStateOf<File?>(null) }
    var loadError by remember(message.id) { mutableStateOf<String?>(null) }
    var isLoading by remember(message.id) { mutableStateOf(message.messageType != "TEXT") }
    var isPlayingAudio by remember(message.id) { mutableStateOf(false) }
    var audioProgress by remember(message.id) { mutableStateOf(0f) }

    val mediaPlayer = remember { MediaPlayer() }

    LaunchedEffect(message.id) {
        onOpenedAndConsumed(message)
        if (message.messageType != "TEXT") {
            isLoading = true
            onPrepareContent(
                message,
                { main, audio ->
                    mainFile = main
                    audioFile = audio
                    isLoading = false
                },
                { err ->
                    loadError = err
                    isLoading = false
                }
            )
        }
    }

    // Apply FLAG_SECURE while view-once content is visible
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        val window = (dialogView.parent as? DialogWindowProvider)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
            }
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(isPlayingAudio) {
        while (isPlayingAudio) {
            val dur = runCatching { mediaPlayer.duration }.getOrDefault(1).coerceAtLeast(1)
            val pos = runCatching { mediaPlayer.currentPosition }.getOrDefault(0)
            audioProgress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            kotlinx.coroutines.delay(80L)
        }
    }

    fun startSingleAudio(file: File) {
        runCatching {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener { mp ->
                mp.start()
                isPlayingAudio = true
            }
            mediaPlayer.setOnCompletionListener {
                isPlayingAudio = false
                audioProgress = 1f
            }
            mediaPlayer.prepareAsync()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF0D1117),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(min = 320.dp, max = 640.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color(0xFF00E676))
                        Text(
                            text = "Visualização Única (1x)",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fechar", tint = Color.White)
                    }
                }

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFF00E676))
                        }
                    }
                    loadError != null -> {
                        Text(
                            text = loadError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    message.messageType == "TEXT" -> {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFF1A222D),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                    message.messageType == "AUDIO" -> {
                        val f = mainFile
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFF1A222D),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    onClick = {
                                        if (f != null && !isPlayingAudio) startSingleAudio(f)
                                    },
                                    shape = CircleShape,
                                    color = Color(0xFF00A884),
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isPlayingAudio) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = "Reproduzir áudio único",
                                            tint = Color.White,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { audioProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Toque para ouvir este áudio único",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                    message.messageType == "VIDEO" -> {
                        val vFile = mainFile
                        if (vFile != null) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoPath(vFile.absolutePath)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = false
                                            start()
                                        }
                                        setOnCompletionListener {
                                            onDismiss()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(340.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                    }
                    else -> {
                        val mFile = mainFile
                        if (mFile != null) {
                            AsyncImage(
                                model = mFile,
                                contentDescription = "Mídia de visualização única",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                        val pairedAudio = audioFile
                        if (message.messageType == "GIF_AUDIO" && pairedAudio != null) {
                            LaunchedEffect(pairedAudio.absolutePath) {
                                startSingleAudio(pairedAudio)
                            }
                            LinearProgressIndicator(
                                progress = { audioProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (message.text.isNotBlank()) {
                            Text(
                                text = message.text,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                Text(
                    text = "Ao fechar, esta mensagem desaparecerá para você.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f)
                )

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("close_view_once_button")
                ) {
                    Text("Fechar e Concluir")
                }
            }
        }
    }
}
