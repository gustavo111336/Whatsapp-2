package com.example

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.data.local.SettingsDataStore
import com.example.data.local.Zap2Database
import com.example.data.remote.AuthRepository
import com.example.data.remote.BackupRepository
import com.example.data.remote.CallSignalingRepository
import com.example.data.remote.ChatRepository
import com.example.data.remote.FreeMediaStorageManager
import com.example.data.remote.UserAndContactsRepository
import com.example.data.remote.WebRtcCallManager
import com.example.service.AppUpdateCoordinator
import com.example.service.AppUpdateManager
import com.example.service.AudioRecorderPlayer
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val databaseId: String = appContext.getString(R.string.firestore_database_id)
    val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(databaseId)
    val settingsStore: SettingsDataStore = SettingsDataStore(appContext)
    val authRepository: AuthRepository = AuthRepository()

    val freeStorageManager: FreeMediaStorageManager by lazy {
        FreeMediaStorageManager(appContext, firestore, settingsStore)
    }

    val localDao get() = Zap2Database.getInstance(appContext).zap2Dao()

    val userRepository: UserAndContactsRepository by lazy {
        UserAndContactsRepository(appContext, firestore, localDao, settingsStore, freeStorageManager = freeStorageManager)
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(appContext, firestore, localDao, settingsStore = settingsStore, freeStorageManager = freeStorageManager)
    }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(appContext, firestore, settingsStore, freeStorageManager = freeStorageManager)
    }

    val callSignalingRepository: CallSignalingRepository = CallSignalingRepository(firestore)
    val webRtcCallManager: WebRtcCallManager by lazy {
        WebRtcCallManager(appContext, callSignalingRepository)
    }
    val audioRecorderPlayer: AudioRecorderPlayer by lazy {
        AudioRecorderPlayer(appContext)
    }
    val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManager(appContext)
    }
    val appUpdateCoordinator: AppUpdateCoordinator by lazy {
        AppUpdateCoordinator(appUpdateManager, settingsStore)
    }
}

class Zap2Application : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedActivityCount = 0

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (startedActivityCount == 0 && Firebase.auth.currentUser != null) {
                    appScope.launch { container.userRepository.setOnlineStatus(true) }
                }
                startedActivityCount++
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0 && !activity.isChangingConfigurations &&
                    Firebase.auth.currentUser != null
                ) {
                    appScope.launch { container.userRepository.setOnlineStatus(false) }
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de novas mensagens recebidas no WhatwillIdo"
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtoneAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val callsChannel = NotificationChannel(
                CHANNEL_CALLS,
                getString(R.string.notification_channel_calls),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações grandes e persistentes de chamadas recebidas e em andamento"
                setSound(ringtoneUri, ringtoneAttrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 600L, 400L, 600L)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            manager.createNotificationChannel(messagesChannel)
            manager.createNotificationChannel(callsChannel)
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "zap2_messages_v2"
        const val CHANNEL_CALLS = "zap2_calls_v2"
    }
}
