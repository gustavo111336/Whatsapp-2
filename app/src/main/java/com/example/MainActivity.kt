package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.UserSettings
import com.example.service.Zap2NotificationHelper
import com.example.service.Zap2NotificationNavigation
import com.example.ui.navigation.Zap2AppRoot
import com.example.ui.theme.Zap2Theme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingNotificationNav = MutableStateFlow<Zap2NotificationNavigation?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIntentNavigation(intent)

        val app = application as Zap2Application
        val container = app.container

        setContent {
            val settings by container.settingsStore.settingsFlow
                .collectAsStateWithLifecycle(initialValue = UserSettings())
            val navTarget by pendingNotificationNav.collectAsStateWithLifecycle()

            Zap2Theme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                Zap2AppRoot(
                    container = container,
                    pendingNotificationNav = navTarget,
                    onConsumeNotificationNav = { pendingNotificationNav.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntentNavigation(intent)
    }

    private fun consumeIntentNavigation(intent: Intent?) {
        val parsed = Zap2NotificationHelper.parseNavigationFromIntent(intent)
        if (parsed != null) {
            pendingNotificationNav.value = parsed
            Zap2NotificationHelper.clearNavigationExtras(intent)
        }
    }
}
