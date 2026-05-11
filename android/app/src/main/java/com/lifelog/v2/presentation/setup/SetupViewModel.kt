package com.lifelog.v2.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.v2.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val client: OkHttpClient
) : ViewModel() {

    companion object {
        private const val TAG = "SetupViewModel"
    }

    val serverUrl = MutableStateFlow(settingsRepository.serverUrl)
    val apiKey = MutableStateFlow(settingsRepository.apiKey)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    sealed class ConnectionState {
        data object Idle : ConnectionState()
        data object Connecting : ConnectionState()
        data class Success(val url: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    fun testConnection() {
        val url = serverUrl.value.trimEnd('/')
        if (url.isBlank()) {
            _connectionState.value = ConnectionState.Error("Enter a server URL")
            return
        }

        _connectionState.value = ConnectionState.Connecting
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fullUrl = "$url/health"
                Log.i(TAG, "Connecting to: $fullUrl")

                val request = Request.Builder()
                    .url(fullUrl)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                Log.i(TAG, "Response: code=${response.code} body=$body")

                if (response.isSuccessful && body?.contains("\"status\"") == true) {
                    settingsRepository.serverUrl = url
                    settingsRepository.apiKey = apiKey.value
                    _connectionState.value = ConnectionState.Success(url)
                } else {
                    _connectionState.value = ConnectionState.Error(
                        "Server responded with ${response.code}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = ConnectionState.Error(
                    e.message ?: "Connection failed"
                )
            }
        }
    }
}
