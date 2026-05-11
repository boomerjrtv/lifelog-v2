package com.lifelog.v2.presentation.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lifelog.v2.data.OrchestratorApi
import com.lifelog.v2.data.SettingsRepository
import com.lifelog.v2.presentation.setup.SetupScreen
import com.lifelog.v2.service.AssistantService
import com.lifelog.v2.ui.theme.LifeLogV2Theme
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var orchestratorApi: OrchestratorApi

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
                            onReset = { isConfigured.value = false },
                            orchestratorApi = orchestratorApi
                        )
                    } else {
                        SetupScreen(onConnected = {
                            isConfigured.value = true
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
            when (intent?.action) {
                AssistantService.ACTION_WAKE_DETECTED -> {
                    wakeKeyword.value = intent.getStringExtra(AssistantService.EXTRA_KEYWORD) ?: ""
                }
                AssistantService.ACTION_VOICE_RESULT -> {
                    // Clear wake indicator, show transcript/reply in chat
                    wakeKeyword.value = ""
                }
            }
        }
    }

    private fun registerWakeReceiver() {
        val filter = IntentFilter().apply {
            addAction(AssistantService.ACTION_WAKE_DETECTED)
            addAction(AssistantService.ACTION_VOICE_RESULT)
        }
        ContextCompat.registerReceiver(this, wakeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
    }
}

data class ChatMessage(val text: String, val isUser: Boolean, val timestamp: Long = System.currentTimeMillis())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    wakeKeyword: String,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onReset: () -> Unit,
    orchestratorApi: OrchestratorApi
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var isRecording by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasMicPermission = perms[Manifest.permission.RECORD_AUDIO] == true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LifeLog V2") },
                actions = {
                    IconButton(onClick = onReset) {
                        Text("Settings", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Wake word status
            if (wakeKeyword.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Wake: \"$wakeKeyword\"", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Chat messages
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                reverseLayout = true
            ) {
                items(messages.reversed()) { msg ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        shape = RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (msg.isUser) 16.dp else 4.dp,
                            bottomEnd = if (msg.isUser) 4.dp else 16.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.isUser)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text(
                            msg.text,
                            modifier = Modifier.padding(12.dp),
                            color = if (msg.isUser)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Status
            Text(
                statusText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Input row
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Push to talk button
                IconButton(
                    onClick = {
                        if (!hasMicPermission) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            return@IconButton
                        }
                        if (isRecording) {
                            isRecording = false
                        } else {
                            isRecording = true
                            statusText = "Recording..."
                            scope.launch {
                                try {
                                    val pcm = withContext(Dispatchers.IO) { recordAudio(3000) }
                                    isRecording = false
                                    statusText = "Transcribing..."
                                    val sttResult = withContext(Dispatchers.IO) { orchestratorApi.transcribe(pcm) }
                                    sttResult.onSuccess { text ->
                                        if (text.isNotBlank()) {
                                            messages = messages + ChatMessage(text, true)
                                            statusText = "Thinking..."
                                            val chatResult = withContext(Dispatchers.IO) { orchestratorApi.chat(text) }
                                            chatResult.onSuccess { reply ->
                                                messages = messages + ChatMessage(reply, false)
                                                statusText = "Ready"
                                            }.onFailure {
                                                statusText = "Chat error: ${it.message}"
                                            }
                                        } else {
                                            statusText = "No speech detected"
                                        }
                                    }.onFailure {
                                        statusText = "STT error: ${it.message}"
                                    }
                                } catch (e: Exception) {
                                    isRecording = false
                                    statusText = "Error: ${e.message}"
                                    Log.e("MainScreen", "Voice error", e)
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                        null,
                        tint = if (isRecording) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary
                    )
                }

                // Text input
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message") },
                    singleLine = true,
                    enabled = !isLoading
                )

                // Send button
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            val msg = inputText
                            inputText = ""
                            messages = messages + ChatMessage(msg, true)
                            statusText = "Thinking..."
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { orchestratorApi.chat(msg) }
                                    result.onSuccess { reply ->
                                        messages = messages + ChatMessage(reply, false)
                                        statusText = "Ready"
                                    }.onFailure {
                                        messages = messages + ChatMessage("Error: ${it.message}", false)
                                        statusText = "Error"
                                    }
                                } catch (e: Exception) {
                                    messages = messages + ChatMessage("Error: ${e.message}", false)
                                    statusText = "Error"
                                }
                            }
                        }
                    },
                    enabled = inputText.isNotBlank() && !isLoading
                ) {
                    Icon(Icons.Filled.Send, null)
                }
            }

            // Start/Stop service row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = {
                    if (!hasMicPermission) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    } else {
                        onStartService()
                    }
                }) { Text("Start Listening") }
                OutlinedButton(onClick = onStopService) { Text("Stop") }
            }
        }
    }
}

private fun recordAudio(durationMs: Int): ByteArray {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRate, channelConfig, audioFormat, minBuf * 2
    )

    val totalSamples = sampleRate * durationMs / 1000
    val buffer = ShortArray(minBuf)
    val result = ByteArrayOutputStream()

    recorder.startRecording()

    var totalRead = 0
    while (totalRead < totalSamples) {
        val read = recorder.read(buffer, 0, minOf(buffer.size, totalSamples - totalRead))
        if (read <= 0) break
        for (i in 0 until read) {
            result.write(buffer[i].toInt() and 0xFF)
            result.write((buffer[i].toInt() shr 8) and 0xFF)
        }
        totalRead += read
    }

    recorder.stop()
    recorder.release()

    return result.toByteArray()
}
