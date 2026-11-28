package com.example.service

import android.content.Context
import android.net.Uri
import com.example.data.local.AI_PROVIDER_AUTO
import com.example.data.local.AI_PROVIDER_CHATGPT
import com.example.data.local.AI_PROVIDER_CLAUDE
import com.example.data.local.AI_PROVIDER_CUSTOM
import com.example.data.local.AI_PROVIDER_DEEPSEEK
import com.example.data.local.AI_PROVIDER_GEMINI
import com.example.data.local.AI_PROVIDER_GROK
import com.example.data.local.AI_PROVIDER_GROQ
import com.example.data.local.AI_PROVIDER_OPENAI
import com.example.data.local.AI_PROVIDER_OPENROUTER
import com.example.data.local.DEFAULT_AI_MODEL
import com.example.data.local.UserSettings
import com.example.data.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

@Serializable
data class AiChatEntry(
    val id: String,
    val role: String, // "USER", "MODEL", "SYSTEM"
    val text: String,
    val modelUsed: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

data class AiProviderPreset(
    val id: String,
    val displayName: String,
    val defaultPersona: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val defaultBaseUrl: String,
    val keyHint: String
)

sealed interface ChatAiCommand {
    data object Configure : ChatAiCommand
    data object Disable : ChatAiCommand
    data class EnableAndSpeak(val focusPrompt: String? = null) : ChatAiCommand
}

class AiAssistantService(private val context: Context) {

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun historyFile(userId: String): File {
        val safeUid = userId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "default" }
        return File(context.filesDir, "ai_chat_history_$safeUid.json")
    }

    suspend fun loadChatHistory(userId: String): List<AiChatEntry> = withContext(Dispatchers.IO) {
        val file = historyFile(userId)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val raw = file.readText()
            if (raw.isBlank()) emptyList() else jsonCodec.decodeFromString<List<AiChatEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    suspend fun saveChatHistory(userId: String, entries: List<AiChatEntry>) = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = if (entries.size > MAX_SAVED_HISTORY) entries.takeLast(MAX_SAVED_HISTORY) else entries
            historyFile(userId).writeText(jsonCodec.encodeToString(trimmed))
        }
    }

    suspend fun clearChatHistory(userId: String) = withContext(Dispatchers.IO) {
        runCatching { historyFile(userId).delete() }
    }

    suspend fun generateChatReply(
        settings: UserSettings,
        history: List<AiChatEntry>,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = settings.aiApiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Nenhuma API Key configurada. Use /ai --configure ou adicione sua chave nas configurações.")
            )
        }
        val model = normalizeModelName(settings.aiModel).ifBlank { DEFAULT_AI_MODEL }
        val persona = resolvePersonaName(settings)
        val trimmedHistory = history
            .filter { it.role == "USER" || it.role == "MODEL" }
            .takeLast(20)
        val customNote = settings.aiCustomInstructions.trim()

        try {
            val text = withTimeout(REQUEST_TIMEOUT_MS) {
                val systemPrompt = buildString {
                    append("Você é $persona, assistente de IA integrado ao mensageiro WhatwillIdo. ")
                    append("Responda de forma clara, útil, natural e no mesmo idioma do usuário (geralmente português do Brasil). ")
                    append("O usuário pode trocar o modelo com /model nome-do-modelo ou configurar provedores com /ai --configure.")
                    if (customNote.isNotBlank()) {
                        append(" Instruções adicionais do usuário: $customNote")
                    }
                }
                dispatchModelRequest(
                    settings = settings,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    turns = trimmedHistory + AiChatEntry(
                        id = "current",
                        role = "USER",
                        text = userPrompt
                    ),
                    expectJson = false
                )
            }
            if (text.isBlank()) {
                Result.failure(IllegalStateException("O modelo '$model' retornou uma resposta vazia."))
            } else {
                Result.success(text.trim())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateRoomReply(
        settings: UserSettings,
        chatTitle: String,
        isGroup: Boolean,
        participantNames: List<String>,
        recentMessages: List<ChatMessage>,
        currentUserId: String,
        currentUserName: String,
        focusPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = settings.aiApiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Nenhuma API Key configurada. Rode /ai --configure para configurar.")
            )
        }
        val model = normalizeModelName(settings.aiModel).ifBlank { DEFAULT_AI_MODEL }
        val persona = resolvePersonaName(settings)
        val customNote = settings.aiCustomInstructions.trim()

        val transcript = formatRoomTranscript(
            chatTitle = chatTitle,
            recentMessages = recentMessages,
            currentUserId = currentUserId,
            currentUserName = currentUserName,
            maxMessages = 60
        )

        val systemPrompt = buildString {
            append("Você é $persona participando diretamente dentro de uma conversa ")
            append(if (isGroup) "em grupo" else "privada")
            append(" no aplicativo WhatwillIdo. ")
            append("Você tem visão completa do histórico da conversa enviado abaixo e pode conversar naturalmente com todos os participantes, responder perguntas, comentar o assunto, ajudar ou brincar de forma autêntica. ")
            append("Nunca invente que não consegue ver a conversa — use o histórico fornecido. ")
            append("Responda diretamente apenas com a sua fala (sem colocar seu nome ou 'IA:' antes da resposta), no mesmo idioma dos participantes (geralmente português do Brasil), de forma concisa e natural.")
            if (customNote.isNotBlank()) {
                append("\nEstilo / personalidade definida pelo usuário: $customNote")
            }
        }

        val participantsLine = if (participantNames.isNotEmpty()) {
            participantNames.distinct().joinToString(", ")
        } else {
            chatTitle
        }

        val userPrompt = buildString {
            append("Chat: $chatTitle\n")
            append("Participantes: $participantsLine\n")
            append("Quem te ativou neste dispositivo: ${currentUserName.ifBlank { "Usuário" }}\n\n")
            append("--- HISTÓRICO COMPLETO RECENTE DO CHAT ---\n")
            append(transcript)
            append("\n--- FIM DO HISTÓRICO ---\n\n")
            if (!focusPrompt.isNullOrBlank()) {
                append("Pedido direto de ${currentUserName.ifBlank { "Usuário" }} ao te chamar: \"$focusPrompt\"\n")
                append("Responda considerando todo o histórico do chat e esse pedido.")
            } else {
                append("Entre na conversa de forma natural comentando ou respondendo ao momento atual do chat.")
            }
        }

        try {
            val reply = withTimeout(REQUEST_TIMEOUT_MS) {
                dispatchModelRequest(
                    settings = settings,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    turns = listOf(
                        AiChatEntry(
                            id = "room_ctx",
                            role = "USER",
                            text = userPrompt
                        )
                    ),
                    expectJson = false
                )
            }
            val cleaned = cleanRoomReplyPrefix(reply, persona)
            if (cleaned.isBlank()) {
                Result.failure(IllegalStateException("A IA ($persona) retornou uma resposta vazia."))
            } else {
                Result.success(cleaned)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun suggestReplies(
        settings: UserSettings,
        chatTitle: String,
        recentMessages: List<ChatMessage>,
        currentUserId: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val apiKey = settings.aiApiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Configure sua API Key para gerar sugestões de resposta.")
            )
        }
        val model = normalizeModelName(settings.aiModel).ifBlank { DEFAULT_AI_MODEL }

        val contextLines = recentMessages
            .filter { it.messageType != "SYSTEM" }
            .takeLast(14)
            .mapNotNull { msg ->
                val sender = when {
                    msg.aiGenerated -> "IA (${msg.aiPersona?.takeIf { it.isNotBlank() } ?: "Assistente"})"
                    msg.senderId == currentUserId -> "Você"
                    else -> msg.senderName.ifBlank { chatTitle }
                }
                val content = summarizeMessageForAi(msg)
                if (content.isBlank()) null else "$sender: $content"
            }

        val conversationSummary = if (contextLines.isEmpty()) {
            "A conversa com $chatTitle acabou de começar e ainda não há mensagens anteriores."
        } else {
            contextLines.joinToString("\n")
        }

        val systemPrompt =
            "Você gera sugestões curtas e naturais de resposta para um aplicativo de mensagens chamado WhatwillIdo. " +
                "Crie exatamente 3 opções de resposta distintas para 'Você' enviar agora na conversa, no mesmo idioma das mensagens (português se estiver em português ou vazio). " +
                "Varie o tom (uma direta/rápida, uma amigável/detalhada e uma com pergunta ou continuação). " +
                "Responda APENAS com um JSON válido no formato: {\"suggestions\": [\"opção 1\", \"opção 2\", \"opção 3\"]}. " +
                "Sem explicações extras e sem prefixos como 'Você:'."

        val userPrompt =
            "Conversa com: $chatTitle\n\nMensagens recentes:\n$conversationSummary\n\nGere 3 sugestões curtas (máximo ~18 palavras cada) para eu responder agora."

        try {
            val rawResponse = withTimeout(REQUEST_TIMEOUT_MS) {
                val useGemini = resolveTransport(settings, apiKey, model) == TransportKind.GEMINI
                if (useGemini) {
                    runCatching {
                        callGeminiGenerateContent(
                            apiKey = apiKey,
                            model = model,
                            systemPrompt = systemPrompt,
                            turns = listOf(AiChatEntry(id = "suggest", role = "USER", text = userPrompt)),
                            expectJson = true
                        )
                    }.getOrElse {
                        callGeminiGenerateContent(
                            apiKey = apiKey,
                            model = model,
                            systemPrompt = systemPrompt,
                            turns = listOf(AiChatEntry(id = "suggest", role = "USER", text = userPrompt)),
                            expectJson = false
                        )
                    }
                } else {
                    dispatchModelRequest(
                        settings = settings,
                        apiKey = apiKey,
                        model = model,
                        systemPrompt = systemPrompt,
                        turns = listOf(AiChatEntry(id = "suggest", role = "USER", text = userPrompt)),
                        expectJson = false
                    )
                }
            }

            val parsed = parseSuggestions(rawResponse)
            if (parsed.isEmpty()) {
                Result.failure(IllegalStateException("Não foi possível extrair sugestões da resposta da IA."))
            } else {
                Result.success(parsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private enum class TransportKind {
        GEMINI,
        ANTHROPIC,
        OPENAI_COMPATIBLE
    }

    private fun dispatchModelRequest(
        settings: UserSettings,
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<AiChatEntry>,
        expectJson: Boolean
    ): String {
        return when (resolveTransport(settings, apiKey, model)) {
            TransportKind.GEMINI -> callGeminiGenerateContent(
                apiKey = apiKey,
                model = model,
                systemPrompt = systemPrompt,
                turns = turns,
                expectJson = expectJson
            )
            TransportKind.ANTHROPIC -> callAnthropicMessages(
                apiKey = apiKey,
                model = model,
                baseUrl = resolveAnthropicEndpoint(settings),
                systemPrompt = systemPrompt,
                turns = turns
            )
            TransportKind.OPENAI_COMPATIBLE -> callOpenAiCompatibleChat(
                apiKey = apiKey,
                model = model,
                baseUrl = resolveOpenAiEndpoint(settings, apiKey),
                systemPrompt = systemPrompt,
                turns = turns
            )
        }
    }

    private fun resolveTransport(settings: UserSettings, apiKey: String, model: String): TransportKind {
        val mLower = model.lowercase(Locale.ROOT)
        val urlLower = settings.aiCustomBaseUrl.lowercase(Locale.ROOT)
        return when (settings.aiProvider) {
            AI_PROVIDER_GEMINI -> TransportKind.GEMINI
            AI_PROVIDER_CLAUDE -> TransportKind.ANTHROPIC
            AI_PROVIDER_GROK,
            AI_PROVIDER_CHATGPT,
            AI_PROVIDER_GROQ,
            AI_PROVIDER_DEEPSEEK,
            AI_PROVIDER_OPENROUTER,
            AI_PROVIDER_OPENAI -> TransportKind.OPENAI_COMPATIBLE
            AI_PROVIDER_CUSTOM -> when {
                urlLower.contains("generativelanguage.googleapis.com") -> TransportKind.GEMINI
                urlLower.contains("anthropic.com") -> TransportKind.ANTHROPIC
                else -> TransportKind.OPENAI_COMPATIBLE
            }
            else -> when {
                urlLower.contains("generativelanguage.googleapis.com") -> TransportKind.GEMINI
                urlLower.contains("anthropic.com") -> TransportKind.ANTHROPIC
                urlLower.isNotBlank() -> TransportKind.OPENAI_COMPATIBLE
                apiKey.startsWith("AIza") -> TransportKind.GEMINI
                apiKey.startsWith("sk-ant-") -> TransportKind.ANTHROPIC
                apiKey.startsWith("xai-") ||
                    apiKey.startsWith("gsk_") ||
                    apiKey.startsWith("sk-or-") -> TransportKind.OPENAI_COMPATIBLE
                mLower.startsWith("claude") -> TransportKind.ANTHROPIC
                mLower.startsWith("gemini") || mLower.startsWith("gemma") -> TransportKind.GEMINI
                else -> TransportKind.OPENAI_COMPATIBLE
            }
        }
    }

    private fun resolveAnthropicEndpoint(settings: UserSettings): String {
        val custom = settings.aiCustomBaseUrl.trim().trimEnd('/')
        if (custom.isNotBlank() && isValidExternalHttpsUrl(custom)) {
            return if (custom.endsWith("/messages")) custom else "$custom/v1/messages"
        }
        return "https://api.anthropic.com/v1/messages"
    }

    private fun resolveOpenAiEndpoint(settings: UserSettings, apiKey: String): String {
        val custom = settings.aiCustomBaseUrl.trim().trimEnd('/')
        if (custom.isNotBlank()) {
            if (!isValidExternalHttpsUrl(custom)) {
                throw IllegalArgumentException("Base URL inválida: use um endereço https:// público válido.")
            }
            return when {
                custom.endsWith("/chat/completions") -> custom
                custom.endsWith("/v1") -> "$custom/chat/completions"
                else -> "$custom/v1/chat/completions"
            }
        }
        return when (settings.aiProvider) {
            AI_PROVIDER_GROK -> "https://api.x.ai/v1/chat/completions"
            AI_PROVIDER_CHATGPT -> "https://api.openai.com/v1/chat/completions"
            AI_PROVIDER_GROQ -> "https://api.groq.com/openai/v1/chat/completions"
            AI_PROVIDER_DEEPSEEK -> "https://api.deepseek.com/chat/completions"
            AI_PROVIDER_OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
            else -> when {
                apiKey.startsWith("xai-") -> "https://api.x.ai/v1/chat/completions"
                apiKey.startsWith("gsk_") -> "https://api.groq.com/openai/v1/chat/completions"
                apiKey.startsWith("sk-or-") -> "https://openrouter.ai/api/v1/chat/completions"
                settings.aiModel.lowercase(Locale.ROOT).startsWith("grok") -> "https://api.x.ai/v1/chat/completions"
                settings.aiModel.lowercase(Locale.ROOT).startsWith("deepseek") -> "https://api.deepseek.com/chat/completions"
                else -> "https://api.openai.com/v1/chat/completions"
            }
        }
    }

    private fun callGeminiGenerateContent(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<AiChatEntry>,
        expectJson: Boolean
    ): String {
        val cleanModel = model.removePrefix("models/").trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${Uri.encode(cleanModel)}:generateContent?key=${Uri.encode(apiKey)}"

        val contentsArray = JSONArray()
        turns.forEach { entry ->
            val role = if (entry.role == "MODEL") "model" else "user"
            val parts = JSONArray().put(JSONObject().put("text", entry.text))
            contentsArray.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", parts)
            )
        }

        val generationConfig = JSONObject().apply {
            put("temperature", 0.75)
            if (expectJson) {
                put("responseMimeType", "application/json")
            }
        }

        val payload = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put("contents", contentsArray)
            .put("generationConfig", generationConfig)

        val responseText = postJson(
            urlStr = url,
            bodyJson = payload.toString(),
            headers = mapOf("x-goog-api-key" to apiKey)
        )

        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates")
        val firstCandidate = candidates?.optJSONObject(0)
        val parts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val partText = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (partText.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(partText)
                }
            }
        }
        if (sb.isBlank()) {
            val finishReason = firstCandidate?.optString("finishReason").orEmpty()
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            val detail = blockReason.ifBlank { finishReason }
            if (detail.isNotBlank()) {
                throw IllegalStateException("Resposta bloqueada ou vazia pela API ($detail).")
            }
        }
        return sb.toString().trim()
    }

    private fun callAnthropicMessages(
        apiKey: String,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        turns: List<AiChatEntry>
    ): String {
        val messagesArray = JSONArray()
        turns.forEach { entry ->
            val role = if (entry.role == "MODEL") "assistant" else "user"
            messagesArray.put(
                JSONObject()
                    .put("role", role)
                    .put("content", entry.text)
            )
        }

        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", systemPrompt)
            .put("messages", messagesArray)

        val responseText = postJson(
            urlStr = baseUrl,
            bodyJson = payload.toString(),
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )

        val root = JSONObject(responseText)
        val contentArr = root.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                val txt = block.optString("text")
                if (txt.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(txt)
                }
            }
        }
        return sb.toString().trim()
    }

    private fun callOpenAiCompatibleChat(
        apiKey: String,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        turns: List<AiChatEntry>
    ): String {
        val messagesArray = JSONArray().apply {
            put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt)
            )
            turns.forEach { entry ->
                val role = if (entry.role == "MODEL") "assistant" else "user"
                put(
                    JSONObject()
                        .put("role", role)
                        .put("content", entry.text)
                )
            }
        }

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("temperature", 0.75)

        val responseText = postJson(
            urlStr = baseUrl,
            bodyJson = payload.toString(),
            headers = mapOf("Authorization" to "Bearer $apiKey")
        )

        val root = JSONObject(responseText)
        val choices = root.optJSONArray("choices")
        val firstChoice = choices?.optJSONObject(0)
        val messageObj = firstChoice?.optJSONObject("message")
        return messageObj?.optString("content")?.trim().orEmpty()
    }

    private fun postJson(
        urlStr: String,
        bodyJson: String,
        headers: Map<String, String>
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WhatwillIdo-Android")
            headers.forEach { (k, v) ->
                if (v.isNotBlank()) setRequestProperty(k, v)
            }
        }

        return try {
            conn.outputStream.use { out ->
                out.write(bodyJson.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val apiMsg = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { !it.isNullOrBlank() }
                throw IllegalStateException(
                    apiMsg?.let { "Erro da IA (HTTP $code): $it" }
                        ?: "Erro na API da IA (HTTP $code): ${body.take(200)}"
                )
            }
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSuggestions(raw: String): List<String> {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        runCatching {
            val obj = JSONObject(cleaned)
            val arr = obj.optJSONArray("suggestions")
                ?: obj.optJSONArray("replies")
                ?: obj.optJSONArray("options")
            if (arr != null) {
                val list = (0 until arr.length())
                    .mapNotNull { arr.optString(it)?.trim()?.takeIf { s -> s.isNotBlank() } }
                if (list.isNotEmpty()) return list.take(3)
            }
        }

        runCatching {
            val arr = JSONArray(cleaned)
            val list = (0 until arr.length())
                .mapNotNull { arr.optString(it)?.trim()?.takeIf { s -> s.isNotBlank() } }
            if (list.isNotEmpty()) return list.take(3)
        }

        return cleaned.lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("^[-*•\\d.)]+\\s*"), "")
                    .trim('"', '\'', ' ')
            }
            .filter { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("}") }
            .distinct()
            .take(3)
            .toList()
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 60_000L
        private const val MAX_SAVED_HISTORY = 120

        val AI_PROVIDERS: List<AiProviderPreset> = listOf(
            AiProviderPreset(
                id = AI_PROVIDER_GEMINI,
                displayName = "Gemini",
                defaultPersona = "Gemini",
                defaultModel = "gemini-3.8-flash",
                suggestedModels = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-4-argon"),
                defaultBaseUrl = "",
                keyHint = "AIza..."
            ),
            AiProviderPreset(
                id = AI_PROVIDER_GROK,
                displayName = "Grok",
                defaultPersona = "Grok",
                defaultModel = "grok-3-mini",
                suggestedModels = listOf("grok-3-mini", "grok-3", "grok-2-latest"),
                defaultBaseUrl = "https://api.x.ai/v1/chat/completions",
                keyHint = "xai-..."
            ),
            AiProviderPreset(
                id = AI_PROVIDER_CHATGPT,
                displayName = "ChatGPT",
                defaultPersona = "ChatGPT",
                defaultModel = "gpt-4o-mini",
                suggestedModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "o4-mini"),
                defaultBaseUrl = "https://api.openai.com/v1/chat/completions",
                keyHint = "sk-proj-..."
            ),
            AiProviderPreset(
                id = AI_PROVIDER_CLAUDE,
                displayName = "Claude",
                defaultPersona = "Claude",
                defaultModel = "claude-sonnet-4-20250514",
                suggestedModels = listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-latest", "claude-opus-4-20250514"),
                defaultBaseUrl = "https://api.anthropic.com/v1/messages",
                keyHint = "sk-ant-..."
            ),
            AiProviderPreset(
                id = AI_PROVIDER_GROQ,
                displayName = "Groq",
                defaultPersona = "Groq",
                defaultModel = "llama-3.3-70b-versatile",
                suggestedModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
                defaultBaseUrl = "https://api.groq.com/openai/v1/chat/completions",
                keyHint = "gsk_..."
            ),
            AiProviderPreset(
                id = AI_PROVIDER_DEEPSEEK,
                displayName = "DeepSeek",
                defaultPersona = "DeepSeek",
                defaultModel = "deepseek-chat",
                suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
                defaultBaseUrl = "https://api.deepseek.com/chat/completions",
                keyHint = "sk-..."
            ),
            AiProviderPreset(
                id = AI_PROVIDER_OPENROUTER,
                displayName = "OpenRouter",
                defaultPersona = "OpenRouter",
                defaultModel = "openai/gpt-4o-mini",
                suggestedModels = listOf("openai/gpt-4o-mini", "x-ai/grok-3-mini-beta", "anthropic/claude-sonnet-4"),
                defaultBaseUrl = "https://openrouter.ai/api/v1/chat/completions",
                keyHint = "sk-or-..."
            ),
            AiProviderPreset(
                id = AI_PROVIDER_CUSTOM,
                displayName = "Outro",
                defaultPersona = "IA",
                defaultModel = "gpt-4o-mini",
                suggestedModels = listOf("gpt-4o-mini", "gemini-3.8-flash", "grok-3-mini"),
                defaultBaseUrl = "",
                keyHint = "API Key..."
            ),
            AiProviderPreset(
                id = AI_PROVIDER_AUTO,
                displayName = "Auto",
                defaultPersona = "IA",
                defaultModel = DEFAULT_AI_MODEL,
                suggestedModels = listOf("gemini-3.8-flash", "grok-3-mini", "gpt-4o-mini", "claude-sonnet-4-20250514", "llama-3.3-70b-versatile"),
                defaultBaseUrl = "",
                keyHint = "AIza... / xai-... / sk-..."
            )
        )

        fun presetFor(providerId: String): AiProviderPreset {
            return AI_PROVIDERS.firstOrNull { it.id == providerId }
                ?: if (providerId == AI_PROVIDER_OPENAI) {
                    AI_PROVIDERS.first { it.id == AI_PROVIDER_CHATGPT }
                } else {
                    AI_PROVIDERS.last()
                }
        }

        fun resolvePersonaName(settings: UserSettings): String {
            val custom = settings.aiPersonaName.trim()
            if (custom.isNotBlank()) return custom
            if (settings.aiProvider != AI_PROVIDER_AUTO && settings.aiProvider != AI_PROVIDER_OPENAI) {
                return presetFor(settings.aiProvider).defaultPersona
            }
            val modelLower = settings.aiModel.lowercase(Locale.ROOT)
            val key = settings.aiApiKey.trim()
            return when {
                modelLower.contains("grok") || key.startsWith("xai-") -> "Grok"
                modelLower.contains("claude") || key.startsWith("sk-ant-") -> "Claude"
                modelLower.contains("gpt") || modelLower.startsWith("o1") || modelLower.startsWith("o3") || modelLower.startsWith("o4") -> "ChatGPT"
                modelLower.contains("deepseek") -> "DeepSeek"
                modelLower.contains("llama") || key.startsWith("gsk_") -> "Groq"
                modelLower.contains("gemini") || key.startsWith("AIza") -> "Gemini"
                else -> "IA WhatwillIdo"
            }
        }

        fun isValidExternalHttpsUrl(rawUrl: String): Boolean {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank()) return true
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            if (host == "localhost" ||
                host.endsWith(".local") ||
                host == "127.0.0.1" ||
                host == "::1" ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                host.startsWith("169.254.") ||
                host.matches(Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\..*"))
            ) {
                return false
            }
            return true
        }

        fun normalizeModelName(raw: String): String {
            return raw.trim()
                .trim('*', '`', '"', '\'', ' ')
                .replace(Regex("\\s+"), "-")
                .ifBlank { DEFAULT_AI_MODEL }
        }

        fun parseSlashModelCommand(input: String): String? {
            val trimmed = input.trim()
            if (!trimmed.equals("/model", ignoreCase = true) &&
                !trimmed.startsWith("/model ", ignoreCase = true)
            ) {
                return null
            }
            val rawArg = trimmed.drop("/model".length).trim()
            if (rawArg.isBlank()) return ""
            return rawArg
                .trim('*', '`', '"', '\'', ' ')
                .trim()
        }

        fun parseChatAiCommand(input: String): ChatAiCommand? {
            val trimmed = input.trim()
            if (!trimmed.equals("/ai", ignoreCase = true) &&
                !trimmed.startsWith("/ai ", ignoreCase = true)
            ) {
                return null
            }
            val arg = trimmed.drop("/ai".length).trim()
            val argLower = arg.lowercase(Locale.ROOT)
            return when {
                argLower == "--configure" ||
                    argLower == "-configure" ||
                    argLower == "--config" ||
                    argLower == "-c" ||
                    argLower == "configure" ||
                    argLower == "config" ||
                    argLower == "configurar" -> ChatAiCommand.Configure

                argLower == "--off" ||
                    argLower == "-off" ||
                    argLower == "off" ||
                    argLower == "sair" ||
                    argLower == "stop" ||
                    argLower == "disable" ||
                    argLower == "desativar" -> ChatAiCommand.Disable

                arg.isBlank() -> ChatAiCommand.EnableAndSpeak(focusPrompt = null)
                else -> ChatAiCommand.EnableAndSpeak(focusPrompt = arg)
            }
        }

        fun summarizeMessageForAi(msg: ChatMessage): String {
            if (msg.viewOnce) {
                val typeName = when (msg.messageType) {
                    "AUDIO" -> "áudio de visualização única"
                    "VIDEO" -> "vídeo de visualização única"
                    "GIF" -> "GIF de visualização única"
                    "STICKER" -> "fig de visualização única"
                    "GIF_AUDIO" -> "GIF com áudio de visualização única"
                    "IMAGE" -> "foto de visualização única"
                    else -> "mensagem de texto de visualização única"
                }
                return "[Enviou $typeName (conteúdo privado)]"
            }
            return when {
                msg.text.isNotBlank() -> msg.text.trim()
                msg.messageType == "IMAGE" -> "[Enviou uma foto]"
                msg.messageType == "GIF" -> "[Enviou um GIF animado]"
                msg.messageType == "STICKER" -> "[Enviou uma figurinha/fig]"
                msg.messageType == "GIF_AUDIO" -> "[Enviou um GIF com áudio]"
                msg.messageType == "VIDEO" -> "[Enviou um vídeo]"
                msg.messageType == "AUDIO" -> "[Enviou um áudio]"
                msg.messageType in setOf("PDF", "APK", "ZIP", "FILE") ->
                    "[Enviou arquivo: ${msg.fileName ?: msg.messageType}]"
                else -> ""
            }
        }

        fun formatRoomTranscript(
            chatTitle: String,
            recentMessages: List<ChatMessage>,
            currentUserId: String,
            currentUserName: String,
            maxMessages: Int = 60
        ): String {
            val lines = recentMessages
                .takeLast(maxMessages)
                .mapNotNull { msg ->
                    if (msg.messageType == "SYSTEM") {
                        if (msg.text.isBlank()) null else "[Sistema]: ${msg.text.trim()}"
                    } else {
                        val speaker = when {
                            msg.aiGenerated -> "IA (${msg.aiPersona?.takeIf { it.isNotBlank() } ?: "Assistente"})"
                            msg.senderId == currentUserId -> currentUserName.ifBlank { "Você" }
                            else -> msg.senderName.ifBlank { chatTitle }
                        }
                        val body = summarizeMessageForAi(msg)
                        if (body.isBlank()) null else "$speaker: $body"
                    }
                }
            return if (lines.isEmpty()) {
                "(A conversa com $chatTitle ainda não tem mensagens.)"
            } else {
                lines.joinToString("\n")
            }
        }

        private fun cleanRoomReplyPrefix(raw: String, persona: String): String {
            var txt = raw.trim()
            val prefixes = listOf(
                "$persona:",
                "IA ($persona):",
                "IA:",
                "🤖 $persona:"
            )
            for (p in prefixes) {
                if (txt.startsWith(p, ignoreCase = true)) {
                    txt = txt.drop(p.length).trim()
                }
            }
            return txt
        }
    }
}
