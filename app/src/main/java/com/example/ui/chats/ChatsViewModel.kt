package com.example.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.SettingsDataStore
import com.example.data.local.UserSettings
import com.example.data.model.ChatRoom
import com.example.data.model.ContactEntry
import com.example.data.model.PublicProfile
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.remote.ChatRepository
import com.example.data.remote.UserAndContactsRepository
import com.example.data.remote.unwrapFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModel(
    private val chatRepository: ChatRepository,
    private val userRepository: UserAndContactsRepository,
    settingsStore: SettingsDataStore,
    val currentUserId: String
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    val userSettings: StateFlow<UserSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UserSettings())

    val myProfile: StateFlow<UserProfile?> = userRepository.observeMyProfile(currentUserId)
        .catch { error ->
            val root = error.unwrapFirestoreException()
            if (root == error && error is CancellationException) throw error
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val savedContacts: StateFlow<List<ContactEntry>> = userRepository.observeSavedContacts(currentUserId)
        .catch { error ->
            val root = error.unwrapFirestoreException()
            if (root == error && error is CancellationException) throw error
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val allChatsFlow = chatRepository.observeChats(currentUserId)

    val peerProfiles: StateFlow<Map<String, PublicProfile>> = allChatsFlow
        .map { chats ->
            chats.mapNotNull { it.peerUidForDirect(currentUserId) }.distinct().sorted()
        }
        .distinctUntilChanged()
        .flatMapLatest { peerUids ->
            if (peerUids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    peerUids.map { peerUid ->
                        userRepository.observePublicProfile(peerUid).map { profile -> peerUid to profile }
                    }
                ) { pairs ->
                    pairs.mapNotNull { (uid, prof) -> prof?.let { uid to it } }.toMap()
                }
            }
        }
        .catch { error ->
            val root = error.unwrapFirestoreException()
            if (root == error && error is CancellationException) throw error
            emit(emptyMap())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyMap())

    val chatsState: StateFlow<UiState<List<ChatRoom>>> = combine(
        allChatsFlow,
        _searchQuery
    ) { chats, query ->
        val filtered = if (query.isBlank()) {
            chats
        } else {
            val q = query.trim().lowercase()
            chats.filter { chat ->
                chat.resolveDisplayTitle(currentUserId).lowercase().contains(q) ||
                    chat.lastMessageText.lowercase().contains(q)
            }
        }
        UiState.Success(filtered) as UiState<List<ChatRoom>>
    }.catch { error ->
        val root = error.unwrapFirestoreException()
        if (root == error && error is CancellationException) throw error
        emit(UiState.Error(root.localizedMessage ?: "Falha ao carregar conversas."))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UiState.Loading
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun clearMessage() {
        _actionMessage.value = null
    }

    fun startDirectChat(
        peerUid: String,
        peerName: String,
        onChatReady: (String) -> Unit
    ) {
        val myName = myProfile.value?.displayName ?: "Você"
        viewModelScope.launch {
            chatRepository.getOrCreateDirectChat(myName, peerUid, peerName)
                .onSuccess { chatId -> onChatReady(chatId) }
                .onFailure { err ->
                    _actionMessage.value = err.localizedMessage ?: "Erro ao abrir conversa."
                }
        }
    }

    fun createGroup(
        title: String,
        description: String,
        selectedContacts: List<ContactEntry>,
        onGroupCreated: (String) -> Unit
    ) {
        val myName = myProfile.value?.displayName ?: "Você"
        viewModelScope.launch {
            val members = selectedContacts.map { it.peerUid to it.displayName }
            chatRepository.createGroupChat(
                creatorName = myName,
                title = title,
                description = description,
                memberProfiles = members
            ).onSuccess { chatId ->
                onGroupCreated(chatId)
            }.onFailure { err ->
                _actionMessage.value = err.localizedMessage ?: "Erro ao criar grupo."
            }
        }
    }
}
