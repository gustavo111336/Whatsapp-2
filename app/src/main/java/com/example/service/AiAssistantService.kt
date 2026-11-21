package com.example.service

import android.content.Context
import android.net.Uri
import com.example.data.local.AI_PROVIDER_AUTO
import com.example.data.local.AI_PROVIDER_GEMINI
import com.example.data.local.AI_PROVIDER_OPENAI
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
                IllegalStateException("Nenhuma API Key configurada. Adicione sua chave nas configurações da IA.")
            )
        }
        val model = normalizeModelName(settings.aiModel).ifBlank { DEFAULT_AI_MODEL }
        val trimmedHistory = history
            .filter { it.role == "USER" || it.role == "MODEL" }
            .takeLast(20)

        try {
            val text = withTimeout(REQUEST_TIMEOUT_MS) {
                val systemPrompt =
                    "Você é o assistente de IA integrado ao mensageiro WhatwillIdo. " +
                        "Responda de forma clara, útil, natural e no mesmo idioma do usuário (geralmente português do Brasil). " +
                        "Se o usuário perguntar sobre modelos, você pode lembrá-lo que é possível trocar de modelo enviando /model nome-do-modelo."
                if (shouldUseGeminiApi(settings, apiKey, model)) {
                    callGeminiGenerateContent(
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
                } else {
                    callOpenAiCompatibleChat(
                        apiKey = apiKey,
                        model = model,
                        baseUrl = resolveOpenAiEndpoint(settings, apiKey),
                        systemPrompt = systemPrompt,
                        turns = trimmedHistory + AiChatEntry(
                            id = "current",
                            role = "USER",
                            text = userPrompt
                        )
                    )
                }
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
                val sender = if (msg.senderId == currentUserId) {
                    "Você"
                } else {
                    msg.senderName.ifBlank { chatTitle }
                }
                val content = when {
                    msg.text.isNotBlank() -> msg.text.trim()
                    msg.messageType == "IMAGE" -> "[Enviou uma imagem]"
                    msg.messageType == "VIDEO" -> "[Enviou um vídeo]"
                    msg.messageType == "AUDIO" -> "[Enviou um áudio]"
                    msg.messageType == "PDF" || msg.messageType == "APK" || msg.messageType == "ZIP" || msg.messageType == "FILE" ->
                        "[Enviou o arquivo ${msg.fileName ?: msg.messageType}]"
                    else -> ""
                }
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
                if (shouldUseGeminiApi(settings, apiKey, model)) {
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
                    callOpenAiCompatibleChat(
                        apiKey = apiKey,
                        model = model,
                        baseUrl = resolveOpenAiEndpoint(settings, apiKey),
                        systemPrompt = systemPrompt,
                        turns = listOf(AiChatEntry(id = "suggest", role = "USER", text = userPrompt))
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

    private fun shouldUseGeminiApi(settings: UserSettings, apiKey: String, model: String): Boolean {
        return when (settings.aiProvider) {
            AI_PROVIDER_GEMINI -> true
            AI_PROVIDER_OPENAI -> false
            else -> {
                if (settings.aiCustomBaseUrl.isNotBlank()) {
                    settings.aiCustomBaseUrl.contains("generativelanguage.googleapis.com", ignoreCase = true)
                } else {
                    apiKey.startsWith("AIza") ||
                        model.lowercase(Locale.ROOT).startsWith("gemini") ||
                        model.lowercase(Locale.ROOT).startsWith("gemma")
                }
            }
        }
    }

    private fun resolveOpenAiEndpoint(settings: UserSettings, apiKey: String): String {
        val custom = settings.aiCustomBaseUrl.trim().trimEnd('/')
        if (custom.isNotBlank()) {
            return if (custom.endsWith("/chat/completions")) {
                custom
            } else if (custom.endsWith("/v1")) {
                "$custom/chat/completions"
            } else {
                "$custom/v1/chat/completions"
            }
        }
        return when {
            apiKey.startsWith("gsk_") -> "https://api.groq.com/openai/v1/chat/completions"
            apiKey.startsWith("sk-or-") -> "https://openrouter.ai/api/v1/chat/completions"
            else -> "https://api.openai.com/v1/chat/completions"
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
            put("temperature", 0.7)
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
            .put("temperature", 0.7)

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

        // 1. Try JSON object {"suggestions": [...]}
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

        // 2. Try JSON array [...]
        runCatching {
            val arr = JSONArray(cleaned)
            val list = (0 until arr.length())
                .mapNotNull { arr.optString(it)?.trim()?.takeIf { s -> s.isNotBlank() } }
            if (list.isNotEmpty()) return list.take(3)
        }

        // 3. Fallback line parser
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

        fun normalizeModelName(raw: String): String {
            return raw.trim()
                .trim('*', '`', '"', '\'', ' ')
                .replace(Regex("\\s+"), "-")
                .ifBlank { DEFAULT_AI_MODEL }
        }

        /**
         * Parses a `/model <nome do modelo>` slash command.
         * Returns null if [input] is not a `/model` command.
         * Returns empty string if user typed just `/model` with no argument.
         */
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
    }
}
