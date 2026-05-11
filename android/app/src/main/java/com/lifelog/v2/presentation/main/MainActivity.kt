package com.lifelog.v2.presentation.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lifelog.v2.data.SettingsRepository
import com.lifelog.v2.presentation.setup.SetupScreen
import com.lifelog.v2.service.AssistantService
import com.lifelog.v2.ui.theme.LifeLogV2Theme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private var wakeKeyword = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        registerWakeReceiver()

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
                        MainScreen(
                            wakeKeyword = wakeKeyword.value,
                            onStartService = { startAssistantService() },
                            onStopService = { stopAssistantService() },
                            onReset = { isConfigured.value = false }
                        )
                    } else {
                        SetupScreen(onConnected = {
                            isConfigured.value = true
                            // Don't auto-start service - user starts it from main screen after granting mic permission
                        })
                    }
                }
            }
        }
    }

    private fun startAssistantService() {
        val intent = Intent(this, AssistantService::class.java).apply {
            action = AssistantService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopAssistantService() {
        val intent = Intent(this, AssistantService::class.java).apply {
            action = AssistantService.ACTION_STOP
        }
        startService(intent)
    }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AssistantService.ACTION_WAKE_DETECTED) {
                wakeKeyword.value = intent.getStringExtra(AssistantService.EXTRA_KEYWORD) ?: ""
            }
        }
    }

    private fun registerWakeReceiver() {
        val filter = IntentFilter(AssistantService.ACTION_WAKE_DETECTED)
        ContextCompat.registerReceiver(this, wakeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
    }
}

@Composable
fun MainScreen(
    wakeKeyword: String,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onReset: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasMicPermission by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) }
    var hasNotifPermission by remember { mutableStateOf(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    ) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasMicPermission = perms[Manifest.permission.RECORD_AUDIO] == true
        hasNotifPermission = perms[Manifest.permission.POST_NOTIFICATIONS] == true
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "LifeLog V2",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (wakeKeyword.isNotBlank()) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Wake word detected: \"$wakeKeyword\"",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                Icons.Filled.MicOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Say \"Hey Assistant\"",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (!hasMicPermission) {
            Button(onClick = {
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
                ))
            }) {
                Text("Grant Permissions")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onStartService) {
                    Text("Start Listening")
                }
                OutlinedButton(onClick = onStopService) {
                    Text("Stop")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onReset) {
            Text("Change Server")
        }
    }
}
