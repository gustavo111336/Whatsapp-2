package com.example.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.chatdetail.ChatDetailViewModel
import com.example.ui.components.Zap2Avatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    viewModel: ChatDetailViewModel,
    onBack: () -> Unit,
    onLeftGroup: () -> Unit
) {
    val chatRoom by viewModel.chatRoom.collectAsStateWithLifecycle()
    val contacts by viewModel.savedContacts.collectAsStateWithLifecycle()

    var showEditDialog by remember { mutableStateOf(false) }
    var showAddParticipantDialog by remember { mutableStateOf(false) }

    val room = chatRoom
    val isCurrentUserAdmin = room != null && viewModel.currentUserId in room.adminUids

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                    }
                },
                title = { Text("Dados do Grupo") },
                actions = {
                    if (isCurrentUserAdmin) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Editar grupo")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (room == null) return@Scaffold

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 720.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Zap2Avatar(
                            name = room.title,
                            photoUrl = room.groupPhotoUrl,
                            isGroup = true,
                            size = 82.dp
                        )
                        Text(
                            text = room.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (!room.description.isNullOrBlank()) {
                            Text(
                                text = room.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${room.participantUids.size} participantes",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Participantes",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (isCurrentUserAdmin) {
                        FilledTonalButton(
                            onClick = { showAddParticipantDialog = true },
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.testTag("add_group_member_button")
                        ) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = null)
                            Text(" Adicionar", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }

            items(room.participantUids, key = { it }) { memberUid ->
                val memberName = room.participantNames[memberUid] ?: "Usuário ${memberUid.take(6)}"
                val isAdmin = memberUid in room.adminUids
                val isSelf = memberUid == viewModel.currentUserId

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Zap2Avatar(name = memberName, photoUrl = null, size = 44.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isSelf) "$memberName (Você)" else memberName,
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (isAdmin) {
                                Text(
                                    text = "Administrador",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (isCurrentUserAdmin && !isSelf) {
                            IconButton(
                                onClick = { viewModel.toggleGroupAdmin(memberUid) },
                                modifier = Modifier.testTag("toggle_admin_$memberUid")
                            ) {
                                Icon(
                                    Icons.Rounded.AdminPanelSettings,
                                    contentDescription = if (isAdmin) "Remover admin" else "Tornar admin",
                                    tint = if (isAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { viewModel.removeGroupParticipant(memberUid) },
                                modifier = Modifier.testTag("remove_member_$memberUid")
                            ) {
                                Icon(
                                    Icons.Rounded.PersonRemove,
                                    contentDescription = "Remover do grupo",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        } else if (isAdmin) {
                            AssistChip(onClick = {}, enabled = false, label = { Text("Admin") })
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { viewModel.leaveGroup(onLeftGroup) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("leave_group_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = null)
                    Text(" Sair do Grupo", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (showEditDialog && room != null) {
        var title by remember { mutableStateOf(room.title) }
        var desc by remember { mutableStateOf(room.description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Editar Grupo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Nome do grupo") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Descrição") },
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateGroupInfo(title, desc)
                        showEditDialog = false
                    },
                    enabled = title.trim().isNotEmpty()
                ) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showAddParticipantDialog && room != null) {
        val available = contacts.filter { it.peerUid !in room.participantUids }
        AlertDialog(
            onDismissRequest = { showAddParticipantDialog = false },
            title = { Text("Adicionar Participante") },
            text = {
                if (available.isEmpty()) {
                    Text("Todos os seus contatos salvos já estão neste grupo.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(available, key = { it.peerUid }) { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.addGroupParticipant(contact)
                                        showAddParticipantDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Zap2Avatar(name = contact.displayName, photoUrl = contact.photoUrl, size = 40.dp)
                                Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddParticipantDialog = false }) { Text("Fechar") }
            }
        )
    }
}
