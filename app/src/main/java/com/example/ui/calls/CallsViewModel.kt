package com.example.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.CallSession
import com.example.data.model.UiState
import com.example.data.remote.CallSignalingRepository
import com.example.data.remote.WebRtcCallManager
import com.example.data.remote.unwrapFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallsViewModel(
    private val signalingRepo: CallSignalingRepository,
    val webRtcCallManager: WebRtcCallManager,
    val currentUserId: String
) : ViewModel() {

    val callHistoryState: StateFlow<UiState<List<CallSession>>> =
        signalingRepo.observeCallHistory(currentUserId)
            .map<List<CallSession>, UiState<List<CallSession>>> { UiState.Success(it) }
            .catch { error ->
                val root = error.unwrapFirestoreException()
                if (root == error && error is CancellationException) throw error
                emit(UiState.Error(root.localizedMessage ?: "Falha ao carregar chamadas."))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val incomingCalls: StateFlow<List<CallSession>> =
        signalingRepo.observeIncomingCalls(currentUserId)
            .catch { error ->
                val root = error.unwrapFirestoreException()
                if (root == error && error is CancellationException) throw error
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun declineIncomingCall(callId: String) {
        viewModelScope.launch {
            signalingRepo.updateCallStatus(callId, "DECLINED")
        }
    }

    fun startRedialCall(
        peerUid: String,
        peerName: String,
        chatId: String,
        myName: String,
        isVideo: Boolean,
        onCreated: (String, Boolean) -> Unit
    ) {
        viewModelScope.launch {
            signalingRepo.createOutgoingCall(
                callerName = myName,
                calleeId = peerUid,
                calleeName = peerName,
                chatId = chatId,
                callType = if (isVideo) "VIDEO" else "AUDIO"
            ).onSuccess { callId ->
                onCreated(callId, isVideo)
            }
        }
    }
}
