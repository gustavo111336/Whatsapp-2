package com.example.ui.aichat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.AI_PROVIDER_CUSTOM
import com.example.data.local.AI_PROVIDER_GEMINI
import com.example.data.local.AI_PROVIDER_OPENAI
import com.example.data.local.DEFAULT_AI_MODEL
import com.example.data.local.UserSettings
import com.example.service.AiAssistantService

val SUGGESTED_AI_MODELS = listOf(
    "gemini-3.8-flash",
    "grok-3-mini",
    "gpt-4o-mini",
    "claude-sonnet-4-20250514",
    "llama-3.3-70b-versatile"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiConfigDialog(
    currentSettings: UserSettings,
    onDismiss: () -> Unit,
    onSave: (
        apiKey: String,
        model: String,
        provider: String,
        customBaseUrl: String,
        personaName: String,
        customInstructions: String,
        autoReplyPeers: Boolean
    ) -> Unit
) {
    var provider by remember(currentSettings.aiProvider) { mutableStateOf(currentSettings.aiProvider) }
    var apiKey by remember(currentSettings.aiApiKey) { mutableStateOf(currentSettings.aiApiKey) }
    var model by remember(currentSettings.aiModel) {
        mutableStateOf(currentSettings.aiModel.ifBlank { DEFAULT_AI_MODEL })
    }
    var customBaseUrl by remember(currentSettings.aiCustomBaseUrl) {
        mutableStateOf(currentSettings.aiCustomBaseUrl)
    }
    var personaName by remember(currentSettings.aiPersonaName, currentSettings.aiProvider) {
        mutableStateOf(
            currentSettings.aiPersonaName.ifBlank {
                AiAssistantService.presetFor(currentSettings.aiProvider).defaultPersona
            }
        )
    }
    var customInstructions by remember(currentSettings.aiCustomInstructions) {
        mutableStateOf(currentSettings.aiCustomInstructions)
    }
    var autoReplyPeers by remember(currentSettings.aiAutoReplyPeers) {
        mutableStateOf(currentSettings.aiAutoReplyPeers)
    }
    var showKey by remember { mutableStateOf(false) }

    val activePreset = remember(provider) { AiAssistantService.presetFor(provider) }
    val showBaseUrlField = provider == AI_PROVIDER_CUSTOM ||
        provider == AI_PROVIDER_OPENAI ||
        customBaseUrl.isNotBlank()
    val urlError = remember(customBaseUrl) {
        customBaseUrl.isNotBlank() && !AiAssistantService.isValidExternalHttpsUrl(customBaseUrl)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Configurar IA (/ai --configure)",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Escolha quem é a IA (Gemini, Grok, ChatGPT, Claude ou outros), cole sua API Key e defina o modelo. Use /ai em qualquer conversa para ela ver o histórico e falar com todos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Quem é a IA (Provedor)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AiAssistantService.AI_PROVIDERS.forEach { preset ->
                        FilterChip(
                            selected = provider == preset.id,
                            onClick = {
                                val prevPreset = AiAssistantService.presetFor(provider)
                                provider = preset.id
                                model = preset.defaultModel
                                if (personaName.isBlank() || personaName.equals(prevPreset.defaultPersona, ignoreCase = true)) {
                                    personaName = preset.defaultPersona
                                }
                                customBaseUrl = if (preset.id == AI_PROVIDER_CUSTOM || preset.id == AI_PROVIDER_GEMINI) {
                                    if (preset.id == AI_PROVIDER_GEMINI) "" else customBaseUrl
                                } else {
                                    preset.defaultBaseUrl
                                }
                                val storedKey = currentSettings.savedKeyForProvider(preset.id)
                                if (storedKey.isNotBlank()) {
                                    apiKey = storedKey
                                }
                            },
                            label = { Text(preset.displayName, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("ai_provider_chip_${preset.id}")
                        )
                    }
                }

                OutlinedTextField(
                    value = personaName,
                    onValueChange = { personaName = it },
                    label = { Text("Nome / Persona da IA no chat") },
                    placeholder = { Text(activePreset.defaultPersona) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_ai_persona_input")
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key (${activePreset.displayName})") },
                    placeholder = { Text(activePreset.keyHint) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showKey) "Ocultar chave" else "Mostrar chave"
                            )
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_ai_api_key_input")
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Modelo de IA (escreva qualquer modelo)") },
                    placeholder = { Text(activePreset.defaultModel) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_ai_model_input")
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    activePreset.suggestedModels.forEach { presetModel ->
                        FilterChip(
                            selected = model.trim().equals(presetModel, ignoreCase = true),
                            onClick = { model = presetModel },
                            label = { Text(presetModel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = customInstructions,
                    onValueChange = { customInstructions = it },
                    label = { Text("Personalidade / Estilo (opcional)") },
                    placeholder = { Text("Ex: Responda como um amigo engraçado, direto e útil") },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_ai_instructions_input")
                )

                if (showBaseUrlField) {
                    OutlinedTextField(
                        value = customBaseUrl,
                        onValueChange = { customBaseUrl = it },
                        label = { Text("Endpoint Base URL (https://)") },
                        placeholder = { Text("https://api.openai.com/v1/chat/completions") },
                        isError = urlError,
                        supportingText = if (urlError) {
                            { Text("Informe uma URL https:// pública válida.") }
                        } else {
                            null
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_ai_base_url_input")
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Entrosar automaticamente no chat",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Quando /ai estiver ativo, responde também quando outras pessoas enviarem mensagens.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoReplyPeers,
                            onCheckedChange = { autoReplyPeers = it },
                            modifier = Modifier.testTag("dialog_ai_auto_reply_switch")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !urlError,
                onClick = {
                    onSave(
                        apiKey.trim(),
                        model.trim().ifBlank { activePreset.defaultModel },
                        provider,
                        customBaseUrl.trim(),
                        personaName.trim().ifBlank { activePreset.defaultPersona },
                        customInstructions.trim(),
                        autoReplyPeers
                    )
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("dialog_ai_save_button")
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancelar")
            }
        },
        modifier = Modifier.testTag("ai_config_dialog")
    )
}
