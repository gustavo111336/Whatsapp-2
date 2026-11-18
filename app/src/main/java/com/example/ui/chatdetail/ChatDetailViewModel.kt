package com.example.ui.chatdetail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.ChatRoom
import com.example.data.model.ContactEntry
import com.example.data.model.PublicProfile
import com.example.data.model.TypingStatus
import com.example.data.model.UiState
import com.example.data.remote.CallSignalingRepository
import com.example.data.remote.ChatRepository
import com.example.data.remote.UserAndContactsRepository
import com.example.data.remote.unwrapFirestoreException
import com.example.service.AudioRecorderPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDetailViewModel(
    private val chatId: String,
    private val chatRepository: ChatRepository,
    private val userRepository: UserAndContactsRepository,
    private val callSignalingRepository: CallSignalingRepository,
    val audioPlayer: AudioRecorderPlayer,
    val currentUserId: String
) : ViewModel() {

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _isSendingMedia = MutableStateFlow(false)
    val isSendingMedia: StateFlow<Boolean> = _isSendingMedia.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

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

    fun sendTextMessage() {
        val room = chatRoom.value ?: return
        val text = _draftText.value.trim()
        if (text.isEmpty()) return
        _draftText.value = ""
        clearTypingStatusNow(room)

        viewModelScope.launch {
            chatRepository.sendTextMessage(room, myDisplayName, text)
                .onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao enviar mensagem."
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
