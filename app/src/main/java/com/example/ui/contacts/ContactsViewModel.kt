package com.example.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ContactEntry
import com.example.data.model.PublicProfile
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContactsViewModel(
    private val userRepository: UserAndContactsRepository,
    private val chatRepository: ChatRepository,
    val currentUserId: String
) : ViewModel() {

    val myProfile: StateFlow<UserProfile?> = userRepository.observeMyProfile(currentUserId)
        .catch { error ->
            val root = error.unwrapFirestoreException()
            if (root == error && error is CancellationException) throw error
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val savedContactsState: StateFlow<UiState<List<ContactEntry>>> =
        userRepository.observeSavedContacts(currentUserId)
            .map<List<ContactEntry>, UiState<List<ContactEntry>>> { UiState.Success(it) }
            .catch { error ->
                val root = error.unwrapFirestoreException()
                if (root == error && error is CancellationException) throw error
                emit(UiState.Error(root.localizedMessage ?: "Falha ao carregar contatos."))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _searchResults = MutableStateFlow<List<PublicProfile>>(emptyList())
    val searchResults: StateFlow<List<PublicProfile>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    fun searchUsers(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _searchResults.value = emptyList()
            _hasSearched.value = false
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            _hasSearched.value = true
            userRepository.searchUsers(trimmed)
                .onSuccess { list ->
                    _searchResults.value = list
                }
                .onFailure { err ->
                    _searchResults.value = emptyList()
                    _feedback.value = err.localizedMessage ?: "Erro ao buscar usuários."
                }
            _isSearching.value = false
        }
    }

    fun saveAndOpenChat(
        profile: PublicProfile,
        customAlias: String = "",
        onChatReady: (String) -> Unit
    ) {
        val myName = myProfile.value?.displayName?.takeIf { it.isNotBlank() } ?: "Você"
        val finalName = customAlias.trim().ifBlank { profile.displayName }
        viewModelScope.launch {
            userRepository.addContact(
                peerUid = profile.uid,
                displayName = finalName,
                username = profile.username.takeIf { it.isNotBlank() },
                photoUrl = profile.photoUrl
            ).onFailure { err ->
                _feedback.value = err.localizedMessage ?: "Erro ao salvar contato."
                return@launch
            }
            chatRepository.getOrCreateDirectChat(myName, profile.uid, finalName)
                .onSuccess { chatId ->
                    clearSearchResults()
                    onChatReady(chatId)
                }
                .onFailure { err ->
                    _feedback.value = err.localizedMessage ?: "Erro ao iniciar conversa."
                }
        }
    }

    fun openChatWithContact(contact: ContactEntry, onChatReady: (String) -> Unit) {
        val myName = myProfile.value?.displayName?.takeIf { it.isNotBlank() } ?: "Você"
        viewModelScope.launch {
            chatRepository.getOrCreateDirectChat(
                currentUserName = myName,
                peerUid = contact.peerUid,
                peerName = contact.displayName
            ).onSuccess(onChatReady)
                .onFailure { err ->
                    _feedback.value = err.localizedMessage ?: "Erro ao abrir conversa."
                }
        }
    }

    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            userRepository.deleteContact(contactId)
                .onFailure { err ->
                    _feedback.value = err.localizedMessage ?: "Erro ao remover contato."
                }
        }
    }

    fun showFeedback(message: String) {
        _feedback.value = message
    }

    fun clearFeedback() {
        _feedback.value = null
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
        _hasSearched.value = false
    }
}
