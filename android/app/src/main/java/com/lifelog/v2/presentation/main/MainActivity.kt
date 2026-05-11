package com.lifelog.v2.presentation.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "LifeLog V2",
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = "Connected ✓",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onReset) {
                Text("Change Server")
            }
        }
    }
}
