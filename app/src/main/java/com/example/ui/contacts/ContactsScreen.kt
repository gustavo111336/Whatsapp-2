package com.example.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ContactEntry
import com.example.data.model.PublicProfile
import com.example.data.model.UiState
import com.example.ui.components.Zap2Avatar

@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val myProfile by viewModel.myProfile.collectAsStateWithLifecycle()
    val savedContactsState by viewModel.savedContactsState.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val hasSearched by viewModel.hasSearched.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var selectedCandidate by remember { mutableStateOf<PublicProfile?>(null) }
    var customAlias by remember { mutableStateOf("") }

    val myHandleDisplay = myProfile?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
        ?: viewModel.currentUserId

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Contatos",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("open_search_user_button")
                ) {
                    Icon(Icons.Rounded.PersonSearch, contentDescription = null, Modifier.size(18.dp))
                    Text(" Buscar @user", modifier = Modifier.padding(start = 6.dp))
                }
            }

            // My @username sharing card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Seu @username no WhatwillIdo (compartilhe para ser encontrado):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = myHandleDisplay,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        myProfile?.email?.takeIf { it.isNotBlank() }?.let { email ->
                            Text(
                                text = "Google: $email",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("WhatwillIdo @username", myHandleDisplay))
                            viewModel.showFeedback("$myHandleDisplay copiado!")
                        },
                        modifier = Modifier.testTag("copy_my_username_button")
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = "Copiar meu @username",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            if (feedback != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { viewModel.clearFeedback() }
                ) {
                    Text(
                        text = feedback.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Text(
                        text = "Contatos Salvos no WhatwillIdo",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                when (val state = savedContactsState) {
                    UiState.Loading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    is UiState.Error -> {
                        item {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    is UiState.Success -> {
                        if (state.data.isEmpty()) {
                            item {
                                Text(
                                    text = "Nenhum contato adicionado ainda. Toque em + para buscar por @username, nome ou e-mail exato.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        } else {
                            items(state.data, key = { it.peerUid }) { contact ->
                                SavedContactRow(
                                    contact = contact,
                                    onChat = { viewModel.openChatWithContact(contact, onOpenChat) },
                                    onDelete = { viewModel.deleteContact(contact.id.ifBlank { contact.peerUid }) }
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("fab_add_contact")
        ) {
            Icon(Icons.Rounded.PersonAdd, contentDescription = "Adicionar contato")
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                selectedCandidate = null
                customAlias = ""
                viewModel.clearSearchResults()
            },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Adicionar Contato no WhatwillIdo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = {
                            searchInput = it
                            selectedCandidate = null
                        },
                        label = { Text("@username, nome ou e-mail exato") },
                        leadingIcon = {
                            Icon(Icons.Rounded.AlternateEmail, contentDescription = null)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_contact_input")
                    )
                    Button(
                        onClick = {
                            selectedCandidate = null
                            viewModel.searchUsers(searchInput)
                        },
                        enabled = !isSearching && searchInput.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("search_contact_button")
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Rounded.PersonSearch, contentDescription = null)
                        }
                        Text(" Buscar no WhatwillIdo", modifier = Modifier.padding(start = 6.dp))
                    }

                    val picked = selectedCandidate
                    if (picked != null) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Zap2Avatar(
                                        name = picked.displayName,
                                        photoUrl = picked.photoUrl,
                                        online = picked.online,
                                        size = 44.dp
                                    )
                                    Column {
                                        Text(
                                            text = picked.displayName,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        if (picked.username.isNotBlank()) {
                                            Text(
                                                text = "@${picked.username}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = picked.about,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = customAlias,
                                    onValueChange = { customAlias = it },
                                    label = { Text("Apelido local (opcional)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        showAddDialog = false
                                        val alias = customAlias
                                        selectedCandidate = null
                                        customAlias = ""
                                        viewModel.saveAndOpenChat(
                                            profile = picked,
                                            customAlias = alias,
                                            onChatReady = onOpenChat
                                        )
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("save_contact_and_chat_button")
                                ) {
                                    Text("Salvar e Conversar")
                                }
                            }
                        }
                    } else if (searchResults.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchResults, key = { it.uid }) { candidate ->
                                Card(
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedCandidate = candidate
                                            customAlias = ""
                                        }
                                        .testTag("search_result_${candidate.uid}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Zap2Avatar(
                                            name = candidate.displayName,
                                            photoUrl = candidate.photoUrl,
                                            online = candidate.online,
                                            size = 42.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = candidate.displayName,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = candidate.username.takeIf { it.isNotBlank() }?.let { "@$it" }
                                                    ?: "UID: ${candidate.uid.take(10)}…",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                showAddDialog = false
                                                viewModel.saveAndOpenChat(
                                                    profile = candidate,
                                                    customAlias = "",
                                                    onChatReady = onOpenChat
                                                )
                                            }
                                        ) {
                                            Text("Conversar")
                                        }
                                    }
                                }
                            }
                        }
                    } else if (hasSearched && !isSearching) {
                        Text(
                            text = "Nenhum usuário encontrado para \"$searchInput\". Tente @username, nome ou o e-mail exato do Google.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    selectedCandidate = null
                    customAlias = ""
                    viewModel.clearSearchResults()
                }) {
                    Text("Fechar")
                }
            }
        )
    }
}

@Composable
private fun SavedContactRow(
    contact: ContactEntry,
    onChat: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChat)
            .testTag("contact_row_${contact.peerUid}"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Zap2Avatar(
                name = contact.displayName,
                photoUrl = contact.photoUrl,
                size = 48.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = contact.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
                    ?: contact.phoneNumber?.takeIf { it.isNotBlank() }
                    ?: "ID: ${contact.peerUid.take(12)}…"
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onChat) {
                Icon(
                    Icons.Rounded.ChatBubble,
                    contentDescription = "Conversar com ${contact.displayName}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Remover contato",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
