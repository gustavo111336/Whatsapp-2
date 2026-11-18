package com.example.ui.contacts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ContactEntry
import com.example.data.model.DeviceContact
import com.example.data.model.PublicProfile
import com.example.data.model.UiState
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

    val savedContactsState: StateFlow<UiState<List<ContactEntry>>> =
        userRepository.observeSavedContacts(currentUserId)
            .map<List<ContactEntry>, UiState<List<ContactEntry>>> { UiState.Success(it) }
            .catch { error ->
                val root = error.unwrapFirestoreException()
                if (root == error && error is CancellationException) throw error
                emit(UiState.Error(root.localizedMessage ?: "Falha ao carregar contatos."))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _deviceContacts = MutableStateFlow<List<DeviceContact>>(emptyList())
    val deviceContacts: StateFlow<List<DeviceContact>> = _deviceContacts.asStateFlow()

    private val _isSyncingDevice = MutableStateFlow(false)
    val isSyncingDevice: StateFlow<Boolean> = _isSyncingDevice.asStateFlow()

    private val _searchResult = MutableStateFlow<PublicProfile?>(null)
    val searchResult: StateFlow<PublicProfile?> = _searchResult.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    private val myName = MutableStateFlow("Você")

    init {
        viewModelScope.launch {
            userRepository.observeMyProfile(currentUserId)
                .catch { }
                .collect { profile ->
                    if (profile != null && profile.displayName.isNotBlank()) {
                        myName.value = profile.displayName
                    }
                }
        }
    }

    fun syncDevicePhonebook(context: Context) {
        viewModelScope.launch {
            _isSyncingDevice.value = true
            userRepository.readAndMatchDeviceContacts(context)
                .onSuccess { list ->
                    _deviceContacts.value = list
                    val registeredCount = list.count { it.registeredUid != null }
                    _feedback.value = "${list.size} contatos lidos da agenda ($registeredCount no Zap2)."
                }
                .onFailure { err ->
                    _feedback.value = err.localizedMessage ?: "Falha ao ler agenda do dispositivo."
                }
            _isSyncingDevice.value = false
        }
    }

    fun searchUserByPhoneOrId(query: String) {
        viewModelScope.launch {
            _searchResult.value = null
            userRepository.findUserByPhoneOrId(query)
                .onSuccess { profile ->
                    _searchResult.value = profile
                }
                .onFailure { err ->
                    _feedback.value = err.localizedMessage ?: "Usuário não encontrado."
                }
        }
    }

    fun saveAndOpenChat(
        peerUid: String,
        displayName: String,
        phoneNumber: String?,
        photoUrl: String?,
        onChatReady: (String) -> Unit
    ) {
        viewModelScope.launch {
            userRepository.addContact(peerUid, displayName, phoneNumber, photoUrl)
                .onFailure { err ->
                    _feedback.value = err.localizedMessage ?: "Erro ao salvar contato."
                    return@launch
                }
            chatRepository.getOrCreateDirectChat(myName.value, peerUid, displayName)
                .onSuccess { chatId ->
                    _searchResult.value = null
                    onChatReady(chatId)
                }
                .onFailure { err ->
                    _feedback.value = err.localizedMessage ?: "Erro ao iniciar conversa."
                }
        }
    }

    fun openChatWithContact(contact: ContactEntry, onChatReady: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateDirectChat(
                currentUserName = myName.value,
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

    fun clearFeedback() {
        _feedback.value = null
    }

    fun clearSearchResult() {
        _searchResult.value = null
    }
}
