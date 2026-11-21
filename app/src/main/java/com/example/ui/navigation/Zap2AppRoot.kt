package com.example.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.AppContainer
import com.example.R
import com.example.data.local.UserSettings
import com.example.service.Zap2NotificationHelper
import com.example.service.Zap2NotificationNavigation
import com.example.ui.aichat.AiChatScreen
import com.example.ui.aichat.AiChatViewModel
import com.example.ui.auth.AuthScreen
import com.example.ui.backup.BackupAndSettingsScreen
import com.example.ui.backup.BackupViewModel
import com.example.ui.backup.EmailVerificationGateScreen
import com.example.ui.calls.ActiveCallScreen
import com.example.ui.calls.CallsHistoryScreen
import com.example.ui.calls.CallsViewModel
import com.example.ui.calls.IncomingCallBanner
import com.example.ui.chatdetail.ApkInstallPermissionDialog
import com.example.ui.chatdetail.ChatDetailScreen
import com.example.ui.chatdetail.ChatDetailViewModel
import com.example.ui.chats.ChatsListScreen
import com.example.ui.chats.ChatsViewModel
import com.example.ui.contacts.ContactsScreen
import com.example.ui.contacts.ContactsViewModel
import com.example.ui.group.GroupInfoScreen
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

@Composable
fun Zap2AppRoot(
    container: AppContainer,
    pendingNotificationNav: Zap2NotificationNavigation? = null,
    onConsumeNotificationNav: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appUpdateState by container.appUpdateCoordinator.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        container.appUpdateCoordinator.checkForUpdatesAutomatically()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> container.appUpdateCoordinator.checkForUpdatesAutomatically()
                Lifecycle.Event.ON_RESUME -> container.appUpdateCoordinator.onResumeAfterInstallSettings()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val authUser by container.authRepository.observeAuthState()
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    val user = authUser
    Box(modifier = Modifier.fillMaxSize()) {
        if (user == null) {
            AuthScreen(
                authRepository = container.authRepository,
                onAuthenticated = {}
            )
        } else {
            key(user.uid) {
                LaunchedEffect(user.uid) {
                    container.userRepository.ensureUserDocuments(user)
                    try {
                        val token = FirebaseMessaging.getInstance().token.await()
                        container.userRepository.saveFcmToken(token)
                    } catch (e: Exception) {
                        Log.d("Zap2FCM", "FCM token unavailable: ${e.message}")
                    }
                }

                Zap2AuthenticatedShell(
                    container = container,
                    currentUserId = user.uid,
                    pendingNotificationNav = pendingNotificationNav,
                    onConsumeNotificationNav = onConsumeNotificationNav
                )
            }
        }

        if (appUpdateState.isDownloading) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 6.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val pct = appUpdateState.downloadFraction?.let { "${(it * 100).toInt()}%" } ?: "..."
                    Text(
                        text = "Baixando nova atualização do WhatwillIdo ($pct)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    val fraction = appUpdateState.downloadFraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        if (appUpdateState.needsInstallPermissionDialog) {
            ApkInstallPermissionDialog(
                description = "Uma nova versão do WhatwillIdo foi baixada. Autorize \"Instalar apps desconhecidos\" para abrir o instalador do Android.",
                onOpenSettings = {
                    container.appUpdateCoordinator.dismissInstallPermissionDialog()
                    runCatching {
                        context.startActivity(container.appUpdateCoordinator.buildManageUnknownAppSourcesIntent())
                    }
                },
                onDismiss = container.appUpdateCoordinator::dismissInstallPermissionDialog
            )
        }
    }
}

@Composable
private fun Zap2AuthenticatedShell(
    container: AppContainer,
    currentUserId: String,
    pendingNotificationNav: Zap2NotificationNavigation?,
    onConsumeNotificationNav: () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val chatsViewModel: ChatsViewModel = viewModel(
        key = "chats_$currentUserId",
        factory = viewModelFactory {
            initializer {
                ChatsViewModel(
                    chatRepository = container.chatRepository,
                    userRepository = container.userRepository,
                    settingsStore = container.settingsStore,
                    currentUserId = currentUserId
                )
            }
        }
    )

    val contactsViewModel: ContactsViewModel = viewModel(
        key = "contacts_$currentUserId",
        factory = viewModelFactory {
            initializer {
                ContactsViewModel(
                    userRepository = container.userRepository,
                    chatRepository = container.chatRepository,
                    currentUserId = currentUserId
                )
            }
        }
    )

    val callsViewModel: CallsViewModel = viewModel(
        key = "calls_$currentUserId",
        factory = viewModelFactory {
            initializer {
                CallsViewModel(
                    signalingRepo = container.callSignalingRepository,
                    webRtcCallManager = container.webRtcCallManager,
                    currentUserId = currentUserId
                )
            }
        }
    )

    val backupViewModel: BackupViewModel = viewModel(
        key = "backup_$currentUserId",
        factory = viewModelFactory {
            initializer {
                BackupViewModel(
                    authRepository = container.authRepository,
                    userRepository = container.userRepository,
                    backupRepository = container.backupRepository,
                    settingsStore = container.settingsStore,
                    freeStorageManager = container.freeStorageManager,
                    appUpdateCoordinator = container.appUpdateCoordinator,
                    currentUserId = currentUserId
                )
            }
        }
    )

    val incomingCalls by callsViewModel.incomingCalls.collectAsStateWithLifecycle()
    val myProfile by backupViewModel.myProfile.collectAsStateWithLifecycle()
    val activeVerificationChallenge by backupViewModel.activeVerificationChallenge.collectAsStateWithLifecycle()
    val backupBusy by backupViewModel.isBusy.collectAsStateWithLifecycle()
    val backupStatusMessage by backupViewModel.statusMessage.collectAsStateWithLifecycle()
    val needsMailerSetupHint by backupViewModel.needsMailerSetupHint.collectAsStateWithLifecycle()
    val lastSentMaskedEmail by backupViewModel.lastSentMaskedEmail.collectAsStateWithLifecycle()
    val userSettings by container.settingsStore.settingsFlow
        .collectAsStateWithLifecycle(initialValue = UserSettings())
    val lifecycleOwner = LocalLifecycleOwner.current

    val isTopLevelTab = currentDest?.hasRoute<Zap2Route.ChatsRoute>() == true ||
        currentDest?.hasRoute<Zap2Route.ContactsRoute>() == true ||
        currentDest?.hasRoute<Zap2Route.CallsHistoryRoute>() == true ||
        currentDest?.hasRoute<Zap2Route.BackupSettingsRoute>() == true

    val isOnCallScreen = currentDest?.hasRoute<Zap2Route.ActiveCallRoute>() == true
    val activeChatId = if (currentDest?.hasRoute<Zap2Route.ChatDetailRoute>() == true) {
        runCatching { backStackEntry?.toRoute<Zap2Route.ChatDetailRoute>()?.chatId }.getOrNull()
    } else {
        null
    }

    val latestActiveChatId by rememberUpdatedState(activeChatId)
    val latestNotificationsEnabled by rememberUpdatedState(userSettings.notificationsEnabled)

    LaunchedEffect(activeChatId) {
        if (!activeChatId.isNullOrBlank()) {
            Zap2NotificationHelper.cancelChatNotification(context, activeChatId)
        }
    }

    LaunchedEffect(pendingNotificationNav) {
        when (val target = pendingNotificationNav) {
            is Zap2NotificationNavigation.OpenChat -> {
                Zap2NotificationHelper.cancelChatNotification(context, target.chatId)
                navController.navigate(Zap2Route.ChatDetailRoute(target.chatId)) {
                    launchSingleTop = true
                }
                onConsumeNotificationNav()
            }
            is Zap2NotificationNavigation.OpenCall -> {
                Zap2NotificationHelper.cancelCallNotification(context, target.callId)
                navController.navigate(
                    Zap2Route.ActiveCallRoute(
                        callId = target.callId,
                        isIncoming = target.isIncoming,
                        isVideo = target.isVideo
                    )
                ) {
                    launchSingleTop = true
                }
                onConsumeNotificationNav()
            }
            null -> Unit
        }
    }

    // Real-time message notification watcher
    LaunchedEffect(currentUserId) {
        val seenChatTimestamps = mutableMapOf<String, Long>()
        var initialized = false
        container.chatRepository.observeChats(currentUserId).collect { chats ->
            if (!initialized) {
                chats.forEach { chat ->
                    seenChatTimestamps[chat.id] = chat.lastMessageAt?.toDate()?.time ?: 0L
                }
                initialized = true
            } else {
                val isAppResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                chats.forEach { chat ->
                    val ts = chat.lastMessageAt?.toDate()?.time ?: 0L
                    val prev = seenChatTimestamps[chat.id] ?: 0L
                    if (ts > prev) {
                        seenChatTimestamps[chat.id] = ts
                        val fromOther = chat.lastMessageSenderId.isNotBlank() &&
                            chat.lastMessageSenderId != currentUserId
                        val viewingThisChat = isAppResumed && latestActiveChatId == chat.id
                        if (latestNotificationsEnabled && fromOther && !viewingThisChat) {
                            Zap2NotificationHelper.showMessageNotification(
                                context = context,
                                chatId = chat.id,
                                title = chat.resolveDisplayTitle(currentUserId),
                                body = Zap2NotificationHelper.messagePreviewForChat(chat)
                            )
                        }
                    }
                }
            }
        }
    }

    // Real-time persistent CallStyle incoming call notification watcher
    val notifiedIncomingCallIds = remember { mutableSetOf<String>() }
    LaunchedEffect(incomingCalls, isOnCallScreen, userSettings.notificationsEnabled) {
        val activeIds = incomingCalls.map { it.id }.toSet()
        notifiedIncomingCallIds.filter { it !in activeIds || !userSettings.notificationsEnabled }.forEach { staleId ->
            if (!isOnCallScreen) {
                Zap2NotificationHelper.cancelCallNotification(context, staleId)
            }
            notifiedIncomingCallIds.remove(staleId)
        }
        if (userSettings.notificationsEnabled && !isOnCallScreen) {
            incomingCalls.forEach { call ->
                Zap2NotificationHelper.showIncomingCallNotification(
                    context = context,
                    callId = call.id,
                    callerName = call.callerName,
                    isVideo = call.callType == "VIDEO",
                    chatId = call.chatId
                )
                notifiedIncomingCallIds.add(call.id)
            }
        }
    }

    val navItems = listOf(
        Triple(Zap2Route.ChatsRoute, stringResource(R.string.nav_chats), Pair(Icons.Rounded.ChatBubble, Icons.Outlined.ChatBubbleOutline)),
        Triple(Zap2Route.ContactsRoute, stringResource(R.string.nav_contacts), Pair(Icons.Rounded.Contacts, Icons.Outlined.Contacts)),
        Triple(Zap2Route.CallsHistoryRoute, stringResource(R.string.nav_calls), Pair(Icons.Rounded.Call, Icons.Outlined.Call)),
        Triple(Zap2Route.BackupSettingsRoute, stringResource(R.string.nav_backup), Pair(Icons.Rounded.Backup, Icons.Outlined.Backup))
    )

    val verificationEnabled = myProfile?.emailVerificationEnabled ?: userSettings.emailVerificationEnabled
    val primaryVerified = myProfile?.emailCodeVerified ?: userSettings.emailCodeVerified

    if (verificationEnabled && !primaryVerified) {
        EmailVerificationGateScreen(
            primaryEmail = myProfile?.email ?: Firebase.auth.currentUser?.email,
            activeChallenge = activeVerificationChallenge,
            settings = userSettings,
            needsMailerSetupHint = needsMailerSetupHint,
            lastSentMaskedEmail = lastSentMaskedEmail,
            isBusy = backupBusy,
            statusMessage = backupStatusMessage,
            onSendCodeToGmail = { backupViewModel.generatePrimaryEmailCode(sendFirebaseFallback = true) },
            onSendFirebaseLink = backupViewModel::sendFirebaseVerificationLink,
            onConfirmViaFirebaseLink = {
                backupViewModel.refreshFirebaseVerificationStatus(markCodeVerifiedIfConfirmed = true)
            },
            onSubmitCode = backupViewModel::submitEmailVerificationCode,
            onDisableVerification = { backupViewModel.setEmailVerificationEnabled(false) },
            onSaveEmailDeliveryConfig = backupViewModel::saveEmailDeliveryConfig,
            onSignOut = { backupViewModel.signOut(context) {} }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp && isTopLevelTab

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (isTopLevelTab && !useNavRail) {
                    NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                        navItems.forEach { (route, label, icons) ->
                            val selected = currentDest?.hasRoute(route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(route) {
                                        popUpTo<Zap2Route.ChatsRoute> { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) icons.first else icons.second,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(modifier = Modifier.statusBarsPadding()) {
                        navItems.forEach { (route, label, icons) ->
                            val selected = currentDest?.hasRoute(route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(route) {
                                        popUpTo<Zap2Route.ChatsRoute> { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) icons.first else icons.second,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(visible = incomingCalls.isNotEmpty() && !isOnCallScreen) {
                            incomingCalls.firstOrNull()?.let { call ->
                                Box(modifier = Modifier.statusBarsPadding()) {
                                    IncomingCallBanner(
                                        call = call,
                                        onAccept = {
                                            Zap2NotificationHelper.cancelCallNotification(context, call.id)
                                            navController.navigate(
                                                Zap2Route.ActiveCallRoute(
                                                    callId = call.id,
                                                    isIncoming = true,
                                                    isVideo = call.callType == "VIDEO"
                                                )
                                            )
                                        },
                                        onDecline = {
                                            Zap2NotificationHelper.cancelCallNotification(context, call.id)
                                            callsViewModel.declineIncomingCall(call.id)
                                        }
                                    )
                                }
                            }
                        }

                        NavHost(
                            navController = navController,
                            startDestination = Zap2Route.ChatsRoute,
                            enterTransition = { fadeIn() + slideInHorizontally { it / 6 } },
                            exitTransition = { fadeOut() },
                            popEnterTransition = { fadeIn() },
                            popExitTransition = { fadeOut() + slideOutHorizontally { it / 6 } },
                            modifier = Modifier.weight(1f)
                        ) {
                            composable<Zap2Route.ChatsRoute> {
                                ChatsListScreen(
                                    viewModel = chatsViewModel,
                                    onOpenChat = { chatId ->
                                        navController.navigate(Zap2Route.ChatDetailRoute(chatId))
                                    },
                                    onNavigateToContacts = {
                                        navController.navigate(Zap2Route.ContactsRoute) {
                                            popUpTo<Zap2Route.ChatsRoute> { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onOpenProfile = {
                                        navController.navigate(Zap2Route.BackupSettingsRoute) {
                                            popUpTo<Zap2Route.ChatsRoute> { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onOpenAiChat = {
                                        navController.navigate(Zap2Route.AiChatRoute) {
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier.statusBarsPadding()
                                )
                            }

                            composable<Zap2Route.ContactsRoute> {
                                ContactsScreen(
                                    viewModel = contactsViewModel,
                                    onOpenChat = { chatId ->
                                        navController.navigate(Zap2Route.ChatDetailRoute(chatId))
                                    },
                                    modifier = Modifier.statusBarsPadding()
                                )
                            }

                            composable<Zap2Route.CallsHistoryRoute> {
                                CallsHistoryScreen(
                                    viewModel = callsViewModel,
                                    onStartCall = { callId, isVideo ->
                                        navController.navigate(
                                            Zap2Route.ActiveCallRoute(
                                                callId = callId,
                                                isIncoming = false,
                                                isVideo = isVideo
                                            )
                                        )
                                    },
                                    modifier = Modifier.statusBarsPadding()
                                )
                            }

                            composable<Zap2Route.BackupSettingsRoute> {
                                BackupAndSettingsScreen(
                                    viewModel = backupViewModel,
                                    onSignedOut = {},
                                    onOpenAiChat = {
                                        navController.navigate(Zap2Route.AiChatRoute) {
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier.statusBarsPadding()
                                )
                            }

                            composable<Zap2Route.ChatDetailRoute> { entry ->
                                val route = entry.toRoute<Zap2Route.ChatDetailRoute>()
                                val detailVm: ChatDetailViewModel = viewModel(
                                    key = "chat_${currentUserId}_${route.chatId}",
                                    factory = viewModelFactory {
                                        initializer {
                                            ChatDetailViewModel(
                                                chatId = route.chatId,
                                                chatRepository = container.chatRepository,
                                                userRepository = container.userRepository,
                                                callSignalingRepository = container.callSignalingRepository,
                                                audioPlayer = container.audioRecorderPlayer,
                                                settingsStore = container.settingsStore,
                                                aiAssistantService = container.aiAssistantService,
                                                currentUserId = currentUserId
                                            )
                                        }
                                    }
                                )
                                ChatDetailScreen(
                                    viewModel = detailVm,
                                    onBack = { navController.popBackStack() },
                                    onOpenGroupInfo = {
                                        navController.navigate(Zap2Route.GroupInfoRoute(route.chatId))
                                    },
                                    onStartCall = { callId, isVideo ->
                                        navController.navigate(
                                            Zap2Route.ActiveCallRoute(
                                                callId = callId,
                                                isIncoming = false,
                                                isVideo = isVideo
                                            )
                                        )
                                    }
                                )
                            }

                            composable<Zap2Route.GroupInfoRoute> { entry ->
                                val route = entry.toRoute<Zap2Route.GroupInfoRoute>()
                                val detailVm: ChatDetailViewModel = viewModel(
                                    key = "chat_${currentUserId}_${route.chatId}",
                                    factory = viewModelFactory {
                                        initializer {
                                            ChatDetailViewModel(
                                                chatId = route.chatId,
                                                chatRepository = container.chatRepository,
                                                userRepository = container.userRepository,
                                                callSignalingRepository = container.callSignalingRepository,
                                                audioPlayer = container.audioRecorderPlayer,
                                                settingsStore = container.settingsStore,
                                                aiAssistantService = container.aiAssistantService,
                                                currentUserId = currentUserId
                                            )
                                        }
                                    }
                                )
                                GroupInfoScreen(
                                    viewModel = detailVm,
                                    onBack = { navController.popBackStack() },
                                    onLeftGroup = {
                                        navController.popBackStack<Zap2Route.ChatsRoute>(inclusive = false)
                                    }
                                )
                            }

                            composable<Zap2Route.ActiveCallRoute> { entry ->
                                val route = entry.toRoute<Zap2Route.ActiveCallRoute>()
                                ActiveCallScreen(
                                    callId = route.callId,
                                    currentUserId = currentUserId,
                                    isIncoming = route.isIncoming,
                                    isVideo = route.isVideo,
                                    callManager = container.webRtcCallManager,
                                    onCallEnded = { navController.popBackStack() }
                                )
                            }

                            composable<Zap2Route.AiChatRoute> {
                                val aiChatVm: AiChatViewModel = viewModel(
                                    key = "ai_chat_$currentUserId",
                                    factory = viewModelFactory {
                                        initializer {
                                            AiChatViewModel(
                                                aiService = container.aiAssistantService,
                                                settingsStore = container.settingsStore,
                                                currentUserId = currentUserId
                                            )
                                        }
                                    }
                                )
                                AiChatScreen(
                                    viewModel = aiChatVm,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
