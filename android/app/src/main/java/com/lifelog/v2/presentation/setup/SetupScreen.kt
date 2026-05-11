package com.lifelog.v2.presentation.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onConnected: () -> Unit = {},
    viewModel: SetupViewModel = hiltViewModel()
) {
    val serverUrl by viewModel.serverUrl.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    LaunchedEffect(connectionState) {
        if (connectionState is SetupViewModel.ConnectionState.Success) {
            onConnected()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("LifeLog V2 Setup") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Connect to your LifeLog server",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { viewModel.serverUrl.value = it },
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.0.45:8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = connectionState !is SetupViewModel.ConnectionState.Connecting
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { viewModel.apiKey.value = it },
                label = { Text("API Key (optional)") },
                placeholder = { Text("Your API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                enabled = connectionState !is SetupViewModel.ConnectionState.Connecting
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.testConnection() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = connectionState !is SetupViewModel.ConnectionState.Connecting
            ) {
                if (connectionState is SetupViewModel.ConnectionState.Connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...")
                } else {
                    Text("Connect")
                }
            }

            when (val state = connectionState) {
                is SetupViewModel.ConnectionState.Success -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Connected to ${state.url}")
                    }
                }
                is SetupViewModel.ConnectionState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {}
            }
        }
    }
}
