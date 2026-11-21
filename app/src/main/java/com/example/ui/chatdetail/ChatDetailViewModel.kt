package com.example.ui.chatdetail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.SettingsDataStore
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
import com.example.service.AudioRecorderPlayer
import com.example.service.ZipArchiveHelper
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDetailViewModel(
    private val chatId: String,
    private val chatRepository: ChatRepository,
    private val userRepository: UserAndContactsRepository,
    private val callSignalingRepository: CallSignalingRepository,
    val audioPlayer: AudioRecorderPlayer,
    private val settingsStore: SettingsDataStore,
    val currentUserId: String
) : ViewModel() {

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _isSendingMedia = MutableStateFlow(false)
    val isSendingMedia: StateFlow<Boolean> = _isSendingMedia.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _editingMessage = MutableStateFlow<ChatMessage?>(null)
    val editingMessage: StateFlow<ChatMessage?> = _editingMessage.asStateFlow()

    val uploadProgress: StateFlow<UploadProgressState?> = chatRepository.uploadProgress

    private val _fileDownloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val fileDownloadProgress: StateFlow<Map<String, Float>> = _fileDownloadProgress.asStateFlow()

    val defaultZipExtractUri: StateFlow<String> = settingsStore.settingsFlow
        .map { it.defaultZipExtractUri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    private var myDisplayName: String = "Você"
    private var typingTimeoutJob: Job? = null
    private var isCurrentlyMarkedTyping = false
    private var lastTypingSentMs = 0L

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
        if (message.senderId != currentUserId || message.messageType != "TEXT") return
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
        _draftText.value = ""
        _editingMessage.value = null
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
                chatRepository.sendTextMessage(room, myDisplayName, text)
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
        viewModelScope.launch {
            _isSendingMedia.value = true
            chatRepository.sendImageMessage(room, myDisplayName, uri)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao enviar imagem."
                }
            _isSendingMedia.value = false
        }
    }

    fun sendVideo(uri: Uri) {
        val room = chatRoom.value ?: return
        viewModelScope.launch {
            _isSendingMedia.value = true
            chatRepository.sendVideoMessage(room, myDisplayName, uri)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao enviar vídeo."
                }
            _isSendingMedia.value = false
        }
    }

    fun sendVoiceNote(file: File, durationMs: Long) {
        val room = chatRoom.value ?: return
        viewModelScope.launch {
            _isSendingMedia.value = true
            chatRepository.sendAudioMessage(room, myDisplayName, file, durationMs)
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

    fun dismissError() {
        _errorBanner.value = null
    }

    override fun onCleared() {
        super.onCleared()
        typingTimeoutJob?.cancel()
        audioPlayer.stopPlayback()
        audioPlayer.stopRecording(discard = true)
        val room = chatRoom.value
        if (isCurrentlyMarkedTyping && room != null) {
            isCurrentlyMarkedTyping = false
            // Best-effort fire-and-forget cleanup via GlobalScope is avoided;
            // typing status expires automatically after 12s if view closes abruptly,
            // and is cleared cleanly onDisposableEffect / send.
        }
    }
}
