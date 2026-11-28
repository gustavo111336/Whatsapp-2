package com.example.ui.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.DEFAULT_AI_MODEL
import com.example.data.local.SettingsDataStore
import com.example.data.local.UserSettings
import com.example.service.AiAssistantService
import com.example.service.AiChatEntry
import com.example.service.ChatAiCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class AiChatViewModel(
    private val aiService: AiAssistantService,
    private val settingsStore: SettingsDataStore,
    private val currentUserId: String
) : ViewModel() {

    val userSettings: StateFlow<UserSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UserSettings())

    private val _messages = MutableStateFlow<List<AiChatEntry>>(emptyList())
    val messages: StateFlow<List<AiChatEntry>> = _messages.asStateFlow()

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _showConfigDialog = MutableStateFlow(false)
    val showConfigDialog: StateFlow<Boolean> = _showConfigDialog.asStateFlow()

    init {
        viewModelScope.launch {
            _messages.value = aiService.loadChatHistory(currentUserId)
        }
    }

    fun onDraftChange(newValue: String) {
        _draftText.value = newValue
    }

    fun openConfigDialog() {
        _showConfigDialog.value = true
    }

    fun dismissConfigDialog() {
        _showConfigDialog.value = false
    }

    fun dismissError() {
        _errorBanner.value = null
    }

    fun saveAiConfig(
        apiKey: String,
        model: String,
        provider: String,
        customBaseUrl: String,
        personaName: String = "",
        customInstructions: String = "",
        autoReplyPeers: Boolean = true
    ) {
        viewModelScope.launch {
            val prevModel = settingsStore.settingsFlow.first().aiModel
            settingsStore.saveAiConfig(
                apiKey = apiKey,
                model = model,
                provider = provider,
                customBaseUrl = customBaseUrl,
                personaName = personaName,
                customInstructions = customInstructions,
                autoReplyPeers = autoReplyPeers
            )
            _showConfigDialog.value = false
            _errorBanner.value = null
            val savedModel = model.trim().ifBlank { DEFAULT_AI_MODEL }
            if (!savedModel.equals(prevModel, ignoreCase = false)) {
                appendEntry(
                    AiChatEntry(
                        id = UUID.randomUUID().toString(),
                        role = "SYSTEM",
                        text = "Modelo ativo atualizado para: $savedModel",
                        modelUsed = savedModel
                    )
                )
            }
        }
    }

    fun sendQuickPrompt(prompt: String) {
        _draftText.value = prompt
        sendMessage()
    }

    fun sendMessage() {
        val rawText = _draftText.value.trim()
        if (rawText.isEmpty() || _isGenerating.value) return

        // Handle /clear command
        if (rawText.equals("/clear", ignoreCase = true) || rawText.equals("/limpar", ignoreCase = true)) {
            _draftText.value = ""
            clearConversation()
            return
        }

        // Handle /ai --configure
        if (AiAssistantService.parseChatAiCommand(rawText) is ChatAiCommand.Configure) {
            _draftText.value = ""
            _showConfigDialog.value = true
            return
        }

        // Handle /model <nome do modelo> command
        val slashModel = AiAssistantService.parseSlashModelCommand(rawText)
        if (slashModel != null) {
            _draftText.value = ""
            viewModelScope.launch {
                val activeModel = settingsStore.settingsFlow.first().aiModel.ifBlank { DEFAULT_AI_MODEL }
                if (slashModel.isBlank()) {
                    appendEntry(
                        AiChatEntry(
                            id = UUID.randomUUID().toString(),
                            role = "SYSTEM",
                            text = "Modelo atual: $activeModel\nPara trocar, digite: /model nome-do-modelo (ex: /model gemini-3.5-flash-lite ou /model gpt-4o-mini)",
                            modelUsed = activeModel
                        )
                    )
                } else {
                    settingsStore.setAiModel(slashModel)
                    appendEntry(
                        AiChatEntry(
                            id = UUID.randomUUID().toString(),
                            role = "SYSTEM",
                            text = "Modelo alterado para: $slashModel",
                            modelUsed = slashModel
                        )
                    )
                }
            }
            return
        }

        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            if (settings.aiApiKey.isBlank()) {
                _errorBanner.value = "Configure sua API Key para conversar com a IA."
                _showConfigDialog.value = true
                return@launch
            }

            _draftText.value = ""
            _errorBanner.value = null
            val activeModel = settings.aiModel.ifBlank { DEFAULT_AI_MODEL }

            val userEntry = AiChatEntry(
                id = UUID.randomUUID().toString(),
                role = "USER",
                text = rawText,
                modelUsed = activeModel
            )
            val historyBeforePrompt = _messages.value
            appendEntry(userEntry)

            _isGenerating.value = true
            try {
                aiService.generateChatReply(
                    settings = settings,
                    history = historyBeforePrompt,
                    userPrompt = rawText
                ).onSuccess { reply ->
                    appendEntry(
                        AiChatEntry(
                            id = UUID.randomUUID().toString(),
                            role = "MODEL",
                            text = reply,
                            modelUsed = activeModel
                        )
                    )
                }.onFailure { err ->
                    _errorBanner.value = err.localizedMessage ?: "Falha ao consultar o modelo $activeModel."
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun clearConversation() {
        viewModelScope.launch {
            _messages.value = emptyList()
            aiService.clearChatHistory(currentUserId)
            _errorBanner.value = null
        }
    }

    private suspend fun appendEntry(entry: AiChatEntry) {
        val updated = _messages.value + entry
        _messages.value = updated
        aiService.saveChatHistory(currentUserId, updated)
    }
}
