package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.UserSettings
import com.example.ui.navigation.Zap2AppRoot
import com.example.ui.theme.Zap2Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as Zap2Application
        val container = app.container

        setContent {
            val settings by container.settingsStore.settingsFlow
                .collectAsStateWithLifecycle(initialValue = UserSettings())

            Zap2Theme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                Zap2AppRoot(container = container)
            }
        }
    }
}
