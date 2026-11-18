package com.example.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatRoom
import com.example.data.model.ContactEntry
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.remote.ChatRepository
import com.example.data.remote.UserAndContactsRepository
import com.example.data.remote.unwrapFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatsViewModel(
    private val chatRepository: ChatRepository,
    private val userRepository: UserAndContactsRepository,
    val currentUserId: String
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

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

    val chatsState: StateFlow<UiState<List<ChatRoom>>> = combine(
        chatRepository.observeChats(currentUserId),
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
