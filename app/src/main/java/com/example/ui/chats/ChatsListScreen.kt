package com.example.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.DEFAULT_AI_MODEL
import com.example.data.model.ChatRoom
import com.example.data.model.PublicProfile
import com.example.data.model.UiState
import com.example.ui.components.Zap2Avatar
import com.example.ui.components.Zap2ProfileBanner
import com.example.ui.components.formatRelativeDateTime
import com.example.ui.theme.Zap2ExpressiveShapes

@Composable
fun ChatsListScreen(
    viewModel: ChatsViewModel,
    onOpenChat: (String) -> Unit,
    onNavigateToContacts: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenAiChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val chatsState by viewModel.chatsState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val discoveredUsers by viewModel.discoveredUsers.collectAsStateWithLifecycle()
    val isSearchingUsers by viewModel.isSearchingUsers.collectAsStateWithLifecycle()
    val contacts by viewModel.savedContacts.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val myProfile by viewModel.myProfile.collectAsStateWithLifecycle()
    val userSettings by viewModel.userSettings.collectAsStateWithLifecycle()
    val peerProfiles by viewModel.peerProfiles.collectAsStateWithLifecycle()

    val effectiveBannerUrl = myProfile?.bannerUrl?.takeIf { it.isNotBlank() }
        ?: userSettings.cachedBannerUrl.takeIf { it.isNotBlank() }
    val effectivePhotoUrl = myProfile?.photoUrl?.takeIf { it.isNotBlank() }
        ?: userSettings.cachedPhotoUrl.takeIf { it.isNotBlank() }
    val effectiveName = myProfile?.displayName?.takeIf { it.isNotBlank() }
        ?: userSettings.cachedDisplayName.ifBlank { "WhatwillIdo" }
    val effectiveUsername = myProfile?.username?.takeIf { it.isNotBlank() }
        ?: userSettings.cachedUsername.takeIf { it.isNotBlank() }

    var showCreateGroup by remember { mutableStateOf(false) }

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
            // Expressive Profile Banner Header above Chats Tab
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp)
                    .height(112.dp)
                    .testTag("chats_top_banner_card")
            ) {
                Zap2ProfileBanner(
                    bannerUrl = effectiveBannerUrl,
                    onClick = onOpenProfile,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val hasCustomBanner = !effectiveBannerUrl.isNullOrBlank()
                    val scrimBrush = if (hasCustomBanner) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.22f),
                                Color.Black.copy(alpha = 0.68f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(scrimBrush)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomStart),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Zap2Avatar(
                                    name = effectiveName,
                                    photoUrl = effectivePhotoUrl,
                                    online = true,
                                    size = 48.dp,
                                    modifier = Modifier.border(
                                        2.dp,
                                        if (hasCustomBanner) Color.White.copy(alpha = 0.85f)
                                        else MaterialTheme.colorScheme.surface,
                                        Zap2ExpressiveShapes.SquircleAvatar
                                    )
                                )
                                Column {
                                    Text(
                                        text = "WhatwillIdo",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (hasCustomBanner) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                    val subtitle = buildString {
                                        if (effectiveName != "WhatwillIdo") {
                                            append("Olá, $effectiveName")
                                        } else {
                                            append("Conectado em tempo real")
                                        }
                                        if (!effectiveUsername.isNullOrBlank()) {
                                            append(" • @$effectiveUsername")
                                        }
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (hasCustomBanner) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            FilledGroupShortcut(
                                onClick = { showCreateGroup = true }
                            )
                        }
                    }
                }
            }

            // Expressive Search Pill
            TextField(
                value = searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text(stringResource(R.string.search_chats_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Buscar"
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Limpar busca")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("search_chats_input")
            )

            // AI Assistant Direct Chat Button Card
            val activeAiModel = userSettings.aiModel.ifBlank { DEFAULT_AI_MODEL }
            val hasAiKey = userSettings.aiApiKey.isNotBlank()
            Surface(
                onClick = onOpenAiChat,
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .testTag("chats_ai_banner_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = Zap2ExpressiveShapes.SquircleAvatar,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = "Assistente IA",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Conversar com IA",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = if (hasAiKey) {
                                "Modelo: $activeAiModel • use /model para trocar"
                            } else {
                                "Toque para colar sua API Key e conversar"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = "Abrir chat da IA",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (actionMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = actionMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::clearMessage) {
                            Icon(Icons.Rounded.Close, contentDescription = "Fechar aviso")
                        }
                    }
                }
            }

            when (val state = chatsState) {
                UiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Button(onClick = onNavigateToContacts) {
                                Text("Ir para Contatos")
                            }
                        }
                    }
                }
                is UiState.Success -> {
                    val isSearching = searchQuery.isNotBlank()
                    val existingDirectPeerUids = state.data.mapNotNull { it.peerUidForDirect(viewModel.currentUserId) }.toSet()
                    val newDiscoveredUsers = if (isSearching) {
                        discoveredUsers.filter { it.uid !in existingDirectPeerUids }
                    } else {
                        emptyList()
                    }

                    if (!isSearching && state.data.isEmpty()) {
                        EmptyChatsView(
                            onNewChat = onNavigateToContacts,
                            onCreateGroup = { showCreateGroup = true }
                        )
                    } else if (isSearching && state.data.isEmpty() && newDiscoveredUsers.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                if (isSearchingUsers) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                    Text(
                                        text = "Buscando pessoas no WhatwillIdo…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "Nenhum resultado para \"${searchQuery.trim()}\"",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Tente buscar pelo @username, nome ou e-mail exato do Google.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (state.data.isNotEmpty()) {
                                items(state.data, key = { it.id }) { chat ->
                                    AnimatedVisibility(
                                        visible = true,
                                        enter = fadeIn() + scaleIn(
                                            initialScale = 0.96f,
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                        )
                                    ) {
                                        val peerProfile = chat.peerUidForDirect(viewModel.currentUserId)?.let { peerProfiles[it] }
                                        ChatListCard(
                                            chat = chat,
                                            currentUserId = viewModel.currentUserId,
                                            peerPhotoUrl = peerProfile?.photoUrl,
                                            peerOnline = peerProfile?.online == true,
                                            onClick = { onOpenChat(chat.id) }
                                        )
                                    }
                                }
                            }

                            if (isSearching && (isSearchingUsers || newDiscoveredUsers.isNotEmpty())) {
                                item(key = "discovered_users_header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Pessoas no WhatwillIdo",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (isSearchingUsers) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                }

                                items(newDiscoveredUsers, key = { "discover_${it.uid}" }) { profile ->
                                    DiscoveredUserCard(
                                        profile = profile,
                                        onClick = {
                                            viewModel.startDirectChat(
                                                peerUid = profile.uid,
                                                peerName = profile.displayName,
                                                onChatReady = onOpenChat
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Expressive Floating Action Stack
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmallFloatingActionButton(
                onClick = onOpenAiChat,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.testTag("fab_ai_chat")
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = "Conversar com IA")
            }
            SmallFloatingActionButton(
                onClick = { showCreateGroup = true },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.testTag("fab_new_group")
            ) {
                Icon(Icons.Rounded.GroupAdd, contentDescription = stringResource(R.string.new_group))
            }
            ExtendedFloatingActionButton(
                onClick = onNavigateToContacts,
                icon = { Icon(Icons.Rounded.AddComment, contentDescription = null) },
                text = { Text(stringResource(R.string.new_chat)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("fab_new_chat")
            )
        }
    }

    if (showCreateGroup) {
        CreateGroupDialog(
            contacts = contacts,
            onDismiss = { showCreateGroup = false },
            onConfirm = { title, desc, selected ->
                showCreateGroup = false
                viewModel.createGroup(title, desc, selected, onOpenChat)
            }
        )
    }
}

@Composable
private fun FilledGroupShortcut(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.testTag("header_new_group_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.GroupAdd,
                contentDescription = "Novo grupo",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Grupo",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun ChatListCard(
    chat: ChatRoom,
    currentUserId: String,
    peerPhotoUrl: String? = null,
    peerOnline: Boolean = false,
    onClick: () -> Unit
) {
    val displayTitle = chat.resolveDisplayTitle(currentUserId)
    val isGroup = chat.chatType == "GROUP"
    val avatarUrl = if (isGroup) chat.groupPhotoUrl else peerPhotoUrl

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .testTag("chat_card_${chat.id}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Zap2Avatar(
                name = displayTitle,
                photoUrl = avatarUrl,
                isGroup = isGroup,
                online = !isGroup && peerOnline,
                size = 54.dp
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatRelativeDateTime(chat.lastMessageAt?.toDate()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (chat.lastMessageType) {
                        "IMAGE", "GIF", "STICKER" -> Icon(
                            Icons.Rounded.Image,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        "GIF_AUDIO" -> Icon(
                            Icons.Rounded.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        "VIDEO" -> Icon(
                            Icons.Rounded.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        "AUDIO" -> Icon(
                            Icons.Rounded.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        "PDF" -> Icon(
                            Icons.Rounded.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        "APK" -> Icon(
                            Icons.Rounded.Android,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        "ZIP" -> Icon(
                            Icons.Rounded.FolderZip,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        "FILE" -> Icon(
                            Icons.Rounded.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = chat.lastMessageText.ifBlank { "Toque para conversar" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveredUserCard(
    profile: PublicProfile,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .testTag("discovered_user_${profile.uid}"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Zap2Avatar(
                name = profile.displayName,
                photoUrl = profile.photoUrl,
                online = profile.online,
                size = 48.dp
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = profile.displayName.ifBlank { "Usuário WhatwillIdo" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (profile.username.isNotBlank()) {
                            append("@${profile.username}")
                        }
                        if (profile.about.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append(profile.about)
                        }
                    }.ifBlank { "Toque para conversar" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = "Conversar",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyChatsView(
    onNewChat: () -> Unit,
    onCreateGroup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_chats),
            contentDescription = "Sem conversas",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(190.dp)
                .clip(RoundedCornerShape(36.dp))
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.empty_chats_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_chats_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onNewChat, shape = RoundedCornerShape(20.dp)) {
                Text("Adicionar Contato")
            }
            OutlinedButton(onClick = onCreateGroup, shape = RoundedCornerShape(20.dp)) {
                Text("Criar Grupo")
            }
        }
    }
}
