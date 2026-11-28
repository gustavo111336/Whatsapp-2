package com.example.ui.chatdetail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.DEFAULT_AI_MODEL
import com.example.data.local.SavedStickerGifEntity
import com.example.data.local.ScheduledMessageEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.UserSettings
import com.example.data.model.ChatMessage
import com.example.data.model.ChatRoom
import com.example.data.model.ContactEntry
import com.example.data.model.PublicProfile
import com.example.data.model.TypingStatus
import com.example.data.model.UiState
import com.example.data.model.UploadProgressState
import com.example.data.model.ZipExtractionResult
import com.example.data.remote.CallSignalingRepository
import com.example.data.remote.ChatRepository
import com.example.data.remote.UserAndContactsRepository
import com.example.data.remote.unwrapFirestoreException
import com.example.service.AiAssistantService
import com.example.service.AudioRecorderPlayer
import com.example.service.ChatAiCommand
import com.example.service.ScheduledMessageScheduler
import com.example.service.ScheduledMessageTimeUtils
import com.example.service.ZipArchiveHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDetailViewModel(
    private val chatId: String,
    private val chatRepository: ChatRepository,
    private val userRepository: UserAndContactsRepository,
    private val callSignalingRepository: CallSignalingRepository,
    val audioPlayer: AudioRecorderPlayer,
    private val settingsStore: SettingsDataStore,
    private val aiAssistantService: AiAssistantService? = null,
    val currentUserId: String
) : ViewModel() {

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _viewOnceMode = MutableStateFlow(false)
    val viewOnceMode: StateFlow<Boolean> = _viewOnceMode.asStateFlow()

    private val _isSendingMedia = MutableStateFlow(false)
    val isSendingMedia: StateFlow<Boolean> = _isSendingMedia.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _editingMessage = MutableStateFlow<ChatMessage?>(null)
    val editingMessage: StateFlow<ChatMessage?> = _editingMessage.asStateFlow()

    private val _aiSuggestions = MutableStateFlow<List<String>>(emptyList())
    val aiSuggestions: StateFlow<List<String>> = _aiSuggestions.asStateFlow()

    private val _isLoadingSuggestions = MutableStateFlow(false)
    val isLoadingSuggestions: StateFlow<Boolean> = _isLoadingSuggestions.asStateFlow()

    private val _showAiConfigPrompt = MutableStateFlow(false)
    val showAiConfigPrompt: StateFlow<Boolean> = _showAiConfigPrompt.asStateFlow()

    private val _isAiThinkingInChat = MutableStateFlow(false)
    val isAiThinkingInChat: StateFlow<Boolean> = _isAiThinkingInChat.asStateFlow()

    val userSettings: StateFlow<UserSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UserSettings())

    val isAiActiveInChat: StateFlow<Boolean> = settingsStore.settingsFlow
        .map { chatId in it.aiJoinedChatIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)

    val uploadProgress: StateFlow<UploadProgressState?> = chatRepository.uploadProgress

    private val _fileDownloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val fileDownloadProgress: StateFlow<Map<String, Float>> = _fileDownloadProgress.asStateFlow()

    val defaultZipExtractUri: StateFlow<String> = settingsStore.settingsFlow
        .map { it.defaultZipExtractUri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val savedStickersAndGifs: StateFlow<List<SavedStickerGifEntity>> =
        chatRepository.observeSavedStickersAndGifs()
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val scheduledMessages: StateFlow<List<ScheduledMessageEntity>> =
        chatRepository.observeScheduledMessages(chatId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private var myDisplayName: String = "Você"
    private var typingTimeoutJob: Job? = null
    private var isCurrentlyMarkedTyping = false
    private var lastTypingSentMs = 0L
    private var baselineMessagesInitialized = false
    private var lastObservedAutoAiMessageId: String? = null
    private var aiAutoReplyJob: Job? = null
    private var pendingActivateAiAfterConfig = false
    private var pendingAiFocusPromptAfterConfig: String? = null
    private var pendingSuggestAfterConfig = false

    val chatRoom: StateFlow<ChatRoom?> = chatRepository.observeChat(chatId, currentUserId)
        .catch { error ->
            val root = error.unwrapFirestoreException()
            if (root == error && error is CancellationException) throw error
            _errorBanner.value = root.localizedMessage ?: "Erro ao observar conversa."
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val messagesState: StateFlow<UiState<List<ChatMessage>>> =
        chatRepository.observeMessages(chatId, currentUserId)
            .map<List<ChatMessage>, UiState<List<ChatMessage>>> { list ->
                viewModelScope.launch {
                    chatRepository.markMessagesRead(chatId, currentUserId, list)
                }
                UiState.Success(list)
            }
            .catch { error ->
                val root = error.unwrapFirestoreException()
                if (root == error && error is CancellationException) throw error
                emit(UiState.Error(root.localizedMessage ?: "Falha ao carregar mensagens."))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val typingUsers: StateFlow<List<TypingStatus>> =
        chatRepository.observeTyping(chatId, currentUserId)
            .catch { error ->
                val root = error.unwrapFirestoreException()
                if (root == error && error is CancellationException) throw error
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val peerProfile: StateFlow<PublicProfile?> = chatRoom
        .flatMapLatest { room ->
            val peerUid = room?.peerUidForDirect(currentUserId)
            if (peerUid != null) {
                chatRepository.observePeerProfile(peerUid, currentUserId)
            } else {
                flowOf(null)
            }
        }
        .catch { error ->
            val root = error.unwrapFirestoreException()
            if (root == error && error is CancellationException) throw error
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val participantProfiles: StateFlow<Map<String, PublicProfile>> = chatRoom
        .map { room -> room?.participantUids.orEmpty().distinct().sorted() }
        .distinctUntilChanged()
        .flatMapLatest { uids ->
            if (uids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    uids.map { uid ->
                        userRepository.observePublicProfile(uid).map { profile -> uid to profile }
                    }
                ) { pairs ->
                    pairs.mapNotNull { (uid, profile) -> profile?.let { uid to it } }.toMap()
                }
            }
        }
        .catch { emit(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyMap())

    val savedContacts: StateFlow<List<ContactEntry>> =
        userRepository.observeSavedContacts(currentUserId)
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        viewModelScope.launch {
            userRepository.observeMyProfile(currentUserId)
                .catch { }
                .collect { profile ->
                    if (profile != null && profile.displayName.isNotBlank()) {
                        myDisplayName = profile.displayName
                    }
                }
        }
        viewModelScope.launch {
            messagesState.collect { state ->
                val list = (state as? UiState.Success)?.data ?: return@collect
                val latest = list.lastOrNull()
                if (!baselineMessagesInitialized) {
                    baselineMessagesInitialized = true
                    lastObservedAutoAiMessageId = latest?.id
                    return@collect
                }
                if (latest != null && latest.id != lastObservedAutoAiMessageId) {
                    lastObservedAutoAiMessageId = latest.id
                    val settings = userSettings.value
                    val isFresh = latest.createdAt == null ||
                        (System.currentTimeMillis() - latest.createdAt.toDate().time) < 120_000L
                    if (chatId in settings.aiJoinedChatIds &&
                        settings.aiAutoReplyPeers &&
                        settings.aiApiKey.isNotBlank() &&
                        isFresh &&
                        !latest.aiGenerated &&
                        latest.messageType != "SYSTEM" &&
                        !latest.viewOnce &&
                        latest.senderId != currentUserId
                    ) {
                        aiAutoReplyJob?.cancel()
                        aiAutoReplyJob = viewModelScope.launch {
                            delay(900L)
                            triggerAiRoomReply(focusPrompt = null)
                        }
                    }
                }
            }
        }
    }

    fun toggleViewOnceMode() {
        _viewOnceMode.value = !_viewOnceMode.value
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun onDraftChanged(newText: String) {
        _draftText.value = newText
        val room = chatRoom.value ?: return
        if (newText.isBlank()) {
            clearTypingStatusNow(room)
            return
        }

        val now = System.currentTimeMillis()
        if (!isCurrentlyMarkedTyping || (now - lastTypingSentMs) > 2500L) {
            isCurrentlyMarkedTyping = true
            lastTypingSentMs = now
            viewModelScope.launch {
                chatRepository.setTypingStatus(chatId, myDisplayName, room.participantUids, true)
            }
        }

        typingTimeoutJob?.cancel()
        typingTimeoutJob = viewModelScope.launch {
            delay(3500L)
            clearTypingStatusNow(room)
        }
    }

    private fun clearTypingStatusNow(room: ChatRoom? = chatRoom.value) {
        typingTimeoutJob?.cancel()
        typingTimeoutJob = null
        if (isCurrentlyMarkedTyping && room != null) {
            isCurrentlyMarkedTyping = false
            viewModelScope.launch {
                chatRepository.setTypingStatus(chatId, myDisplayName, room.participantUids, false)
            }
        }
    }

    fun startEditingMessage(message: ChatMessage) {
        if (message.senderId != currentUserId || message.messageType != "TEXT" || message.viewOnce) return
        _editingMessage.value = message
        _draftText.value = message.text
    }

    fun cancelEditing() {
        _editingMessage.value = null
        _draftText.value = ""
    }

    fun sendTextMessage() {
        val room = chatRoom.value ?: return
        val text = _draftText.value.trim()
        if (text.isEmpty()) return
        val targetEdit = _editingMessage.value

        if (targetEdit == null) {
            val aiCommand = AiAssistantService.parseChatAiCommand(text)
            if (aiCommand != null) {
                _draftText.value = ""
                _viewOnceMode.value = false
                clearTypingStatusNow(room)
                handleAiSlashCommand(aiCommand)
                return
            }

            val slashModel = AiAssistantService.parseSlashModelCommand(text)
            if (slashModel != null) {
                _draftText.value = ""
                _viewOnceMode.value = false
                clearTypingStatusNow(room)
                viewModelScope.launch {
                    if (slashModel.isBlank()) {
                        val active = settingsStore.settingsFlow.first().aiModel.ifBlank { DEFAULT_AI_MODEL }
                        _toastMessage.value = "Modelo atual: $active (use /model nome ou /ai --configure)"
                    } else {
                        settingsStore.setAiModel(slashModel)
                        _toastMessage.value = "Modelo atualizado para $slashModel!"
                    }
                }
                return
            }
        }

        val useViewOnce = _viewOnceMode.value && targetEdit == null
        _draftText.value = ""
        _editingMessage.value = null
        _aiSuggestions.value = emptyList()
        if (useViewOnce) _viewOnceMode.value = false
        clearTypingStatusNow(room)

        viewModelScope.launch {
            if (targetEdit != null) {
                val currentList = (messagesState.value as? UiState.Success)?.data.orEmpty()
                val isLatest = currentList.lastOrNull()?.id == targetEdit.id
                chatRepository.editMessage(room, targetEdit, text, isLatest)
                    .onFailure { err ->
                        _errorBanner.value = err.localizedMessage ?: "Falha ao editar mensagem."
                    }
            } else {
                chatRepository.sendTextMessage(room, myDisplayName, text, viewOnce = useViewOnce)
                    .onSuccess {
                        if (!useViewOnce && isAiActiveInChat.value) {
                            aiAutoReplyJob?.cancel()
                            aiAutoReplyJob = viewModelScope.launch {
                                delay(450L)
                                triggerAiRoomReply(focusPrompt = null)
                            }
                        }
                    }
                    .onFailure { err ->
                        _errorBanner.value = err.localizedMessage ?: "Falha ao enviar mensagem."
                    }
            }
        }
    }

    fun deleteMessageForMe(message: ChatMessage) {
        viewModelScope.launch {
            if (_editingMessage.value?.id == message.id) {
                cancelEditing()
            }
            chatRepository.deleteMessageForMe(chatId, message.id)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao apagar mensagem."
                }
        }
    }

    fun deleteMessageForEveryone(message: ChatMessage) {
        val room = chatRoom.value ?: return
        viewModelScope.launch {
            if (_editingMessage.value?.id == message.id) {
                cancelEditing()
            }
            val currentList = (messagesState.value as? UiState.Success)?.data.orEmpty()
            val remaining = currentList.filter { it.id != message.id }
            chatRepository.deleteMessageForEveryone(room, message, remaining)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao apagar mensagem para todos."
                }
        }
    }

    fun sendImage(uri: Uri) {
        val room = chatRoom.value ?: return
        val useViewOnce = _viewOnceMode.value
        _viewOnceMode.value = false
        viewModelScope.launch {
            _isSendingMedia.value = true
            chatRepository.sendImageMessage(room, myDisplayName, uri, viewOnce = useViewOnce)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao enviar imagem/GIF."
                }
            _isSendingMedia.value = false
        }
    }

    fun sendVideo(uri: Uri) {
        val room = chatRoom.value ?: return
        val useViewOnce = _viewOnceMode.value
        _viewOnceMode.value = false
        viewModelScope.launch {
            _isSendingMedia.value = true
            chatRepository.sendVideoMessage(room, myDisplayName, uri, viewOnce = useViewOnce)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao enviar vídeo."
                }
            _isSendingMedia.value = false
        }
    }

    fun sendVoiceNote(file: File, durationMs: Long) {
        val room = chatRoom.value ?: return
        val useViewOnce = _viewOnceMode.value
        _viewOnceMode.value = false
        viewModelScope.launch {
            _isSendingMedia.value = true
            chatRepository.sendAudioMessage(room, myDisplayName, file, durationMs, viewOnce = useViewOnce)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao enviar áudio."
                }
            _isSendingMedia.value = false
        }
    }

    fun sendDocument(uri: Uri) {
        val room = chatRoom.value ?: return
        viewModelScope.launch {
            _isSendingMedia.value = true
            chatRepository.sendDocumentMessage(room, myDisplayName, uri)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao enviar arquivo."
                }
            _isSendingMedia.value = false
        }
    }

    fun sendCustomSticker(stickerFile: File, saveToCollection: Boolean, title: String) {
        val room = chatRoom.value ?: return
        val useViewOnce = _viewOnceMode.value
        _viewOnceMode.value = false
        viewModelScope.launch {
            _isSendingMedia.value = true
            if (saveToCollection) {
                chatRepository.saveCustomStickerToCollection(stickerFile, title = title)
            }
            chatRepository.sendStickerMessage(room, myDisplayName, stickerFile, viewOnce = useViewOnce)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao enviar fig."
                }
            _isSendingMedia.value = false
        }
    }

    fun saveCustomStickerOnly(stickerFile: File, title: String) {
        viewModelScope.launch {
            chatRepository.saveCustomStickerToCollection(stickerFile, title = title)
                .onSuccess {
                    _toastMessage.value = "Fig salva em Minhas Figs!"
                }
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao salvar fig."
                }
        }
    }

    fun sendSavedStickerOrGif(item: SavedStickerGifEntity) {
        val room = chatRoom.value ?: return
        val useViewOnce = _viewOnceMode.value
        _viewOnceMode.value = false
        val mainFile = File(item.localFilePath)
        if (!mainFile.exists()) {
            _errorBanner.value = "Arquivo salvo não encontrado no dispositivo."
            return
        }
        viewModelScope.launch {
            _isSendingMedia.value = true
            val result = when (item.kind) {
                "STICKER" -> chatRepository.sendStickerMessage(room, myDisplayName, mainFile, viewOnce = useViewOnce)
                "GIF_AUDIO" -> {
                    val aud = item.localAudioPath?.let(::File)?.takeIf { it.exists() }
                    if (aud != null) {
                        chatRepository.sendGifWithAudioMessage(
                            chat = room,
                            senderName = myDisplayName,
                            gifFile = mainFile,
                            audioFile = aud,
                            audioDurationMs = item.audioDurationMs,
                            viewOnce = useViewOnce
                        )
                    } else {
                        chatRepository.sendGifFileMessage(room, myDisplayName, mainFile, viewOnce = useViewOnce)
                    }
                }
                else -> chatRepository.sendGifFileMessage(room, myDisplayName, mainFile, viewOnce = useViewOnce)
            }
            result.onFailure { err ->
                _errorBanner.value = err.localizedMessage ?: "Falha ao enviar mídia salva."
            }
            _isSendingMedia.value = false
        }
    }

    fun sendGifWithAudio(
        gifUri: Uri?,
        savedGifFile: File?,
        audioFile: File,
        durationMs: Long,
        saveToCollection: Boolean,
        title: String
    ) {
        val room = chatRoom.value ?: return
        val useViewOnce = _viewOnceMode.value
        _viewOnceMode.value = false
        viewModelScope.launch {
            _isSendingMedia.value = true
            try {
                val resolvedGif = withContext(Dispatchers.IO) {
                    when {
                        savedGifFile != null && savedGifFile.exists() -> savedGifFile
                        gifUri != null -> chatRepository.copyUriToScheduledMedia(gifUri, "gif")
                        else -> throw IllegalStateException("Selecione um GIF primeiro.")
                    }
                }
                if (saveToCollection) {
                    chatRepository.saveCustomGifWithAudioToCollection(
                        gifFile = resolvedGif,
                        audioFile = audioFile,
                        audioDurationMs = durationMs,
                        title = title
                    )
                }
                chatRepository.sendGifWithAudioMessage(
                    chat = room,
                    senderName = myDisplayName,
                    gifFile = resolvedGif,
                    audioFile = audioFile,
                    audioDurationMs = durationMs,
                    viewOnce = useViewOnce
                ).onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao enviar GIF com áudio."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorBanner.value = e.localizedMessage ?: "Erro ao preparar GIF com áudio."
            } finally {
                _isSendingMedia.value = false
            }
        }
    }

    fun saveMessageMedia(message: ChatMessage) {
        viewModelScope.launch {
            chatRepository.saveMessageMediaToCollectionAndGallery(message, exportToGallery = true)
                .onSuccess { entity ->
                    val label = when (entity.kind) {
                        "STICKER" -> "Fig salva em Minhas Figs e na Galeria!"
                        "GIF_AUDIO" -> "GIF com áudio salvo na coleção e Galeria!"
                        else -> "GIF salvo na coleção e na Galeria (Pictures/WhatwillIdo)!"
                    }
                    _toastMessage.value = label
                }
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Não foi possível salvar esta mídia."
                }
        }
    }

    fun deleteSavedItem(item: SavedStickerGifEntity) {
        viewModelScope.launch {
            chatRepository.deleteSavedStickerOrGif(item)
            _toastMessage.value = "Item removido da coleção."
        }
    }

    fun markViewOnceOpened(message: ChatMessage) {
        viewModelScope.launch {
            chatRepository.markMessageViewOnceOpened(chatId, message)
        }
    }

    fun prepareAudioForMessage(message: ChatMessage, onReady: (File) -> Unit) {
        viewModelScope.launch {
            val res = if (message.messageType == "GIF_AUDIO") {
                chatRepository.downloadMessageAudioFile(message)
            } else {
                chatRepository.downloadMessageFile(message)
            }
            res.onSuccess(onReady)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao baixar áudio."
                }
        }
    }

    fun prepareViewOnceContent(
        message: ChatMessage,
        onReady: (mainFile: File?, audioFile: File?) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (message.messageType == "TEXT") {
                onReady(null, null)
                return@launch
            }
            val mainRes = chatRepository.downloadMessageFile(message)
            if (mainRes.isFailure) {
                onError(mainRes.exceptionOrNull()?.localizedMessage ?: "Falha ao carregar mídia única.")
                return@launch
            }
            val mainFile = mainRes.getOrNull()
            val audioFile = if (message.messageType == "GIF_AUDIO" && !message.audioUrl.isNullOrBlank()) {
                chatRepository.downloadMessageAudioFile(message).getOrNull()
            } else {
                null
            }
            onReady(mainFile, audioFile)
        }
    }

    fun scheduleMessage(
        context: Context,
        scheduledAtMs: Long,
        messageType: String,
        text: String,
        savedItem: SavedStickerGifEntity? = null,
        viewOnce: Boolean = false
    ) {
        val room = chatRoom.value ?: return
        viewModelScope.launch {
            val mediaFile = savedItem?.localFilePath?.let(::File)?.takeIf { it.exists() }
            val audioFile = savedItem?.localAudioPath?.let(::File)?.takeIf { it.exists() }
            ScheduledMessageScheduler.scheduleMessage(
                context = context,
                chat = room,
                senderUid = currentUserId,
                senderName = myDisplayName,
                messageType = messageType,
                text = text,
                localMediaFile = mediaFile,
                localAudioFile = audioFile,
                mediaDurationMs = savedItem?.audioDurationMs ?: 0L,
                mimeType = when (messageType) {
                    "STICKER" -> "image/png"
                    "GIF", "GIF_AUDIO" -> "image/gif"
                    else -> null
                },
                viewOnce = viewOnce,
                scheduledAtMs = scheduledAtMs
            ).onSuccess {
                if (messageType == "TEXT" && _draftText.value.trim() == text.trim()) {
                    _draftText.value = ""
                }
                _viewOnceMode.value = false
                _toastMessage.value = "Mensagem agendada para ${ScheduledMessageTimeUtils.formatScheduledDateTime(scheduledAtMs)}!"
            }.onFailure { err ->
                _errorBanner.value = err.localizedMessage ?: "Não foi possível agendar a mensagem."
            }
        }
    }

    fun cancelScheduledMessage(context: Context, scheduleId: String) {
        viewModelScope.launch {
            ScheduledMessageScheduler.cancelScheduledMessage(context, scheduleId)
            _toastMessage.value = "Agendamento cancelado."
        }
    }

    fun sendScheduledMessageNow(context: Context, scheduleId: String) {
        viewModelScope.launch {
            ScheduledMessageScheduler.sendScheduledNow(context, scheduleId)
                .onSuccess { _toastMessage.value = "Enviando mensagem agendada..." }
                .onFailure { err -> _errorBanner.value = err.localizedMessage }
        }
    }

    fun downloadAndPrepareFile(message: ChatMessage, onReady: (File) -> Unit) {
        viewModelScope.launch {
            _fileDownloadProgress.update { it + (message.id to 0f) }
            chatRepository.downloadMessageFile(message) { progress ->
                _fileDownloadProgress.update { it + (message.id to progress) }
            }.onSuccess { file ->
                _fileDownloadProgress.update { it - message.id }
                onReady(file)
            }.onFailure { err ->
                _fileDownloadProgress.update { it - message.id }
                _errorBanner.value = err.localizedMessage ?: "Falha ao baixar arquivo."
            }
        }
    }

    fun extractZipToDefault(
        context: Context,
        zipFile: File,
        archiveDisplayName: String,
        onComplete: (ZipExtractionResult) -> Unit
    ) {
        viewModelScope.launch {
            _isSendingMedia.value = true
            ZipArchiveHelper.extractZipToDefault(
                context = context,
                zipFile = zipFile,
                archiveDisplayName = archiveDisplayName,
                configuredTreeUri = defaultZipExtractUri.value
            ).onSuccess { result ->
                onComplete(result)
            }.onFailure { err ->
                _errorBanner.value = err.localizedMessage ?: "Erro ao extrair arquivo ZIP."
            }
            _isSendingMedia.value = false
        }
    }

    fun extractZipToCustomUri(
        context: Context,
        zipFile: File,
        archiveDisplayName: String,
        treeUri: Uri,
        onComplete: (ZipExtractionResult) -> Unit
    ) {
        viewModelScope.launch {
            _isSendingMedia.value = true
            ZipArchiveHelper.extractZipToTreeUri(
                context = context,
                zipFile = zipFile,
                archiveDisplayName = archiveDisplayName,
                treeUri = treeUri
            ).onSuccess { result ->
                onComplete(result)
            }.onFailure { err ->
                _errorBanner.value = err.localizedMessage ?: "Erro ao extrair arquivo ZIP na pasta escolhida."
            }
            _isSendingMedia.value = false
        }
    }

    fun reportError(message: String) {
        _errorBanner.value = message
    }

    fun startCall(isVideo: Boolean, onCallCreated: (callId: String, isVideo: Boolean) -> Unit) {
        val room = chatRoom.value ?: return
        val peerUid = room.participantUids.firstOrNull { it != currentUserId }
        if (peerUid == null) {
            _errorBanner.value = "Adicione outro participante para iniciar uma chamada."
            return
        }
        val peerName = room.participantNames[peerUid] ?: room.resolveDisplayTitle(currentUserId)
        val callType = if (isVideo) "VIDEO" else "AUDIO"

        viewModelScope.launch {
            callSignalingRepository.createOutgoingCall(
                callerName = myDisplayName,
                calleeId = peerUid,
                calleeName = peerName,
                chatId = room.id,
                callType = callType
            ).onSuccess { callId ->
                onCallCreated(callId, isVideo)
            }.onFailure { err ->
                _errorBanner.value = err.localizedMessage ?: "Falha ao iniciar chamada."
            }
        }
    }

    // Group Management Actions
    fun updateGroupInfo(title: String, description: String) {
        viewModelScope.launch {
            chatRepository.updateGroupInfo(chatId, title, description)
                .onFailure { _errorBanner.value = it.localizedMessage }
        }
    }

    fun addGroupParticipant(contact: ContactEntry) {
        val room = chatRoom.value ?: return
        viewModelScope.launch {
            chatRepository.addGroupParticipant(room, contact.peerUid, contact.displayName, myDisplayName)
                .onFailure { _errorBanner.value = it.localizedMessage }
        }
    }

    fun removeGroupParticipant(targetUid: String) {
        val room = chatRoom.value ?: return
        viewModelScope.launch {
            chatRepository.removeGroupParticipant(room, targetUid, myDisplayName)
                .onFailure { _errorBanner.value = it.localizedMessage }
        }
    }

    fun toggleGroupAdmin(targetUid: String) {
        val room = chatRoom.value ?: return
        viewModelScope.launch {
            chatRepository.toggleGroupAdmin(room, targetUid)
                .onFailure { _errorBanner.value = it.localizedMessage }
        }
    }

    fun leaveGroup(onLeft: () -> Unit) {
        val room = chatRoom.value ?: return
        viewModelScope.launch {
            chatRepository.leaveGroup(room)
                .onSuccess { onLeft() }
                .onFailure { _errorBanner.value = it.localizedMessage }
        }
    }

    private fun handleAiSlashCommand(command: ChatAiCommand) {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            when (command) {
                is ChatAiCommand.Configure -> {
                    _showAiConfigPrompt.value = true
                }
                is ChatAiCommand.Disable -> {
                    aiAutoReplyJob?.cancel()
                    settingsStore.setChatAiJoined(chatId, false)
                    _toastMessage.value = "IA saiu deste chat."
                }
                is ChatAiCommand.EnableAndSpeak -> {
                    if (settings.aiApiKey.isBlank()) {
                        pendingActivateAiAfterConfig = true
                        pendingAiFocusPromptAfterConfig = command.focusPrompt
                        _showAiConfigPrompt.value = true
                        _toastMessage.value = "Configure a API Key, provedor e modelo para ativar /ai!"
                    } else {
                        settingsStore.setChatAiJoined(chatId, true)
                        val persona = AiAssistantService.resolvePersonaName(settings)
                        _toastMessage.value = "$persona entrou no chat!"
                        triggerAiRoomReply(focusPrompt = command.focusPrompt)
                    }
                }
            }
        }
    }

    fun toggleAiInChat() {
        viewModelScope.launch {
            val currentlyActive = isAiActiveInChat.value
            if (currentlyActive) {
                aiAutoReplyJob?.cancel()
                settingsStore.setChatAiJoined(chatId, false)
                _toastMessage.value = "IA saiu deste chat."
            } else {
                handleAiSlashCommand(ChatAiCommand.EnableAndSpeak(focusPrompt = null))
            }
        }
    }

    fun openAiConfigDialog() {
        _showAiConfigPrompt.value = true
    }

    fun nudgeAiSpeakNow() {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            if (settings.aiApiKey.isBlank()) {
                pendingActivateAiAfterConfig = true
                _showAiConfigPrompt.value = true
                return@launch
            }
            if (!isAiActiveInChat.value) {
                settingsStore.setChatAiJoined(chatId, true)
            }
            triggerAiRoomReply(focusPrompt = null)
        }
    }

    private fun triggerAiRoomReply(focusPrompt: String? = null) {
        val service = aiAssistantService ?: return
        val room = chatRoom.value ?: return
        if (_isAiThinkingInChat.value) return
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            if (settings.aiApiKey.isBlank()) return@launch
            _isAiThinkingInChat.value = true
            _errorBanner.value = null
            try {
                val persona = AiAssistantService.resolvePersonaName(settings)
                val activeModel = settings.aiModel.ifBlank { DEFAULT_AI_MODEL }
                val messages = (messagesState.value as? UiState.Success)?.data.orEmpty()
                val title = room.resolveDisplayTitle(currentUserId)
                val isGroup = room.chatType == "GROUP"
                val names = room.participantUids.map { uid ->
                    participantProfiles.value[uid]?.displayName
                        ?: room.participantNames[uid]
                        ?: if (uid == currentUserId) myDisplayName else "Participante"
                }

                service.generateRoomReply(
                    settings = settings,
                    chatTitle = title,
                    isGroup = isGroup,
                    participantNames = names,
                    recentMessages = messages,
                    currentUserId = currentUserId,
                    currentUserName = myDisplayName,
                    focusPrompt = focusPrompt
                ).onSuccess { reply ->
                    chatRepository.sendTextMessage(
                        chat = room,
                        senderName = myDisplayName,
                        text = reply,
                        viewOnce = false,
                        aiGenerated = true,
                        aiPersona = persona,
                        aiModel = activeModel
                    ).onFailure { err ->
                        _errorBanner.value = err.localizedMessage ?: "Falha ao enviar fala da IA."
                    }
                }.onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao gerar resposta da IA no chat."
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isAiThinkingInChat.value = false
            }
        }
    }

    fun requestAiReplySuggestions() {
        val service = aiAssistantService ?: return
        if (_isLoadingSuggestions.value) return
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            if (settings.aiApiKey.isBlank()) {
                pendingSuggestAfterConfig = true
                _showAiConfigPrompt.value = true
                return@launch
            }
            _isLoadingSuggestions.value = true
            _errorBanner.value = null
            val title = chatRoom.value?.resolveDisplayTitle(currentUserId) ?: "Conversa"
            val messages = (messagesState.value as? UiState.Success)?.data.orEmpty()
            try {
                service.suggestReplies(
                    settings = settings,
                    chatTitle = title,
                    recentMessages = messages,
                    currentUserId = currentUserId
                ).onSuccess { list ->
                    _aiSuggestions.value = list
                }.onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao sugerir respostas com IA."
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isLoadingSuggestions.value = false
            }
        }
    }

    fun applyAiSuggestion(suggestion: String) {
        onDraftChanged(suggestion)
    }

    fun dismissAiSuggestions() {
        _aiSuggestions.value = emptyList()
    }

    fun dismissAiConfigPrompt() {
        _showAiConfigPrompt.value = false
        pendingActivateAiAfterConfig = false
        pendingAiFocusPromptAfterConfig = null
        pendingSuggestAfterConfig = false
    }

    fun saveQuickAiConfig(
        apiKey: String,
        model: String,
        provider: String,
        customBaseUrl: String,
        personaName: String = "",
        customInstructions: String = "",
        autoReplyPeers: Boolean = true
    ) {
        viewModelScope.launch {
            settingsStore.saveAiConfig(
                apiKey = apiKey,
                model = model,
                provider = provider,
                customBaseUrl = customBaseUrl,
                personaName = personaName,
                customInstructions = customInstructions,
                autoReplyPeers = autoReplyPeers
            )
            _showAiConfigPrompt.value = false
            val shouldActivateAi = pendingActivateAiAfterConfig
            val focusPrompt = pendingAiFocusPromptAfterConfig
            val shouldSuggest = pendingSuggestAfterConfig
            pendingActivateAiAfterConfig = false
            pendingAiFocusPromptAfterConfig = null
            pendingSuggestAfterConfig = false

            if (apiKey.isNotBlank()) {
                val label = personaName.ifBlank { AiAssistantService.presetFor(provider).defaultPersona }
                _toastMessage.value = "IA configurada: $label ($model)"
                if (shouldActivateAi) {
                    settingsStore.setChatAiJoined(chatId, true)
                    triggerAiRoomReply(focusPrompt = focusPrompt)
                } else if (shouldSuggest) {
                    requestAiReplySuggestions()
                }
            }
        }
    }

    fun dismissError() {
        _errorBanner.value = null
    }

    override fun onCleared() {
        super.onCleared()
        typingTimeoutJob?.cancel()
        aiAutoReplyJob?.cancel()
        audioPlayer.stopPlayback()
        audioPlayer.stopRecording(discard = true)
        val room = chatRoom.value
        if (isCurrentlyMarkedTyping && room != null) {
            isCurrentlyMarkedTyping = false
        }
    }
}
