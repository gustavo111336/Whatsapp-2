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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.AI_PROVIDER_AUTO
import com.example.data.local.AI_PROVIDER_GEMINI
import com.example.data.local.AI_PROVIDER_OPENAI
import com.example.data.local.DEFAULT_AI_MODEL
import com.example.data.local.UserSettings

val SUGGESTED_AI_MODELS = listOf(
    "gemini-3.8-flash",
    "gemini-3.5-flash-lite",
    "gemini-4-argon",
    "gpt-4o-mini",
    "llama-3.3-70b-versatile"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiConfigDialog(
    currentSettings: UserSettings,
    onDismiss: () -> Unit,
    onSave: (apiKey: String, model: String, provider: String, customBaseUrl: String) -> Unit
) {
    var apiKey by remember(currentSettings.aiApiKey) { mutableStateOf(currentSettings.aiApiKey) }
    var model by remember(currentSettings.aiModel) {
        mutableStateOf(currentSettings.aiModel.ifBlank { DEFAULT_AI_MODEL })
    }
    var provider by remember(currentSettings.aiProvider) { mutableStateOf(currentSettings.aiProvider) }
    var customBaseUrl by remember(currentSettings.aiCustomBaseUrl) {
        mutableStateOf(currentSettings.aiCustomBaseUrl)
    }
    var showKey by remember { mutableStateOf(false) }

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
                text = "Configurar IA por API Key",
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
                    text = "Cole sua API Key (Google AI Studio / Gemini, OpenAI, Groq, OpenRouter ou compatível) e escreva o modelo que quiser. Você também pode trocar no chat digitando /model nome-do-modelo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("AIza... ou sk-...") },
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
                    placeholder = { Text("gemini-3.8-flash") },
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
                    SUGGESTED_AI_MODELS.forEach { preset ->
                        FilterChip(
                            selected = model.trim().equals(preset, ignoreCase = true),
                            onClick = { model = preset },
                            label = { Text(preset, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text(
                    text = "Provedor",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        AI_PROVIDER_AUTO to "Auto",
                        AI_PROVIDER_GEMINI to "Gemini",
                        AI_PROVIDER_OPENAI to "OpenAI/Outro"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = provider == key,
                            onClick = { provider = key },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                if (provider == AI_PROVIDER_OPENAI || customBaseUrl.isNotBlank()) {
                    OutlinedTextField(
                        value = customBaseUrl,
                        onValueChange = { customBaseUrl = it },
                        label = { Text("Base URL (opcional)") },
                        placeholder = { Text("https://api.openai.com/v1/chat/completions") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_ai_base_url_input")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        apiKey.trim(),
                        model.trim().ifBlank { DEFAULT_AI_MODEL },
                        provider,
                        customBaseUrl.trim()
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
        }
    )
}
