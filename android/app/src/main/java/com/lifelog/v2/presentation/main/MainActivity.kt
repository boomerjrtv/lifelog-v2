package com.lifelog.v2.presentation.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.lifelog.v2.data.SettingsRepository
import com.lifelog.v2.presentation.setup.SetupScreen
import com.lifelog.v2.ui.theme.LifeLogV2Theme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LifeLogV2Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isConfigured = remember {
                        mutableStateOf(settingsRepository.isConfigured)
                    }

                    if (isConfigured.value) {
                        // Placeholder — will be replaced with main app navigation
                        MainPlaceholder(onReset = { isConfigured.value = false })
                    } else {
                        SetupScreen(onConnected = { isConfigured.value = true })
                    }
                }
            }
        }
    }
}

@Composable
fun MainPlaceholder(onReset: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            androidx.compose.material3.Text(
                text = "LifeLog V2",
                style = MaterialTheme.typography.headlineLarge
            )
            androidx.compose.material3.Text(
                text = "Connected ✓",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.height(24.dp)
            )
            androidx.compose.material3.TextButton(onClick = onReset) {
                androidx.compose.material3.Text("Change Server")
            }
        }
    }
}
