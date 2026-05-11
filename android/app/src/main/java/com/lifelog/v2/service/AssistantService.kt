package com.lifelog.v2.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.lifelog.v2.presentation.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AssistantService : Service() {

    companion object {
        const val CHANNEL_ID = "assistant_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.lifelog.v2.action.START"
        const val ACTION_STOP = "com.lifelog.v2.action.STOP"
        const val ACTION_WAKE_DETECTED = "com.lifelog.v2.action.WAKE_DETECTED"
        const val EXTRA_KEYWORD = "keyword"

        private const val TAG = "AssistantService"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    }

    @Volatile private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var kws: KeywordSpotter? = null
    private var kwsStream: OnlineStream? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning) return START_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("Starting wake word engine..."))

        if (!hasMicPermission()) {
            updateNotification("Waiting for microphone permission")
            return START_STICKY
        }

        startWakeWordDetection()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startWakeWordDetection() {
        try {
            initKws()
            initAudioRecord()
            isRunning = true

            updateNotification("Listening — say \"Hey Assistant\"")

            recordingThread = Thread {
                processAudioLoop()
            }.also { it.isDaemon = true; it.start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word detection", e)
            updateNotification("Error: ${e.message}")
        }
    }

    private fun initKws() {
        Log.i(TAG, "Initializing Sherpa-ONNX keyword spotter...")

        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                modelType = "zipformer2",
            ),
            keywordsFile = "$MODEL_DIR/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            maxActivePaths = 4,
            numTrailingBlanks = 2,
        )

        kws = KeywordSpotter(
            assetManager = application.assets,
            config = config,
        )

        // Create stream with no custom keywords - uses keywordsFile from config
        val stream = kws!!.createStream()
        if (stream.ptr == 0L) {
            Log.e(TAG, "Failed to create KWS stream - trying with keyword string")
            // Fallback: pass raw keyword text
            val fallback = kws!!.createStream("HELLO WORLD / HEY SIRI / HI GOOGLE")
            if (fallback.ptr == 0L) {
                Log.e(TAG, "Fallback stream also failed, cannot start")
                updateNotification("Error: KWS stream creation failed")
                return
            }
            kwsStream = fallback
        } else {
            kwsStream = stream
        }
        Log.i(TAG, "KWS stream created, ptr=${kwsStream!!.ptr}")

        Log.i(TAG, "Keyword spotter initialized successfully")
    }

    private fun initAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            throw IllegalStateException("AudioRecord min buffer unavailable: $minBuf")
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        audioRecord!!.startRecording()
        Log.i(TAG, "AudioRecord started at ${SAMPLE_RATE}Hz")
    }

    private fun processAudioLoop() {
        val interval = 0.1 // 100ms chunks
        val bufferSize = (interval * SAMPLE_RATE).toInt()
        val buffer = ShortArray(bufferSize)
        var loopCount = 0

        Log.i(TAG, "Audio processing loop started")

        while (isRunning) {
            val ret = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (ret <= 0) continue

            loopCount++

            val samples = FloatArray(ret) { buffer[it] / 32768.0f }

            val stream = kwsStream ?: break
            val spotter = kws ?: break

            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)

            // Log every 50th loop (~5 seconds)
            if (loopCount % 50 == 0) {
                val energy = samples.sumOf { abs(it.toDouble()) } / samples.size
                Log.d(TAG, "loop #$loopCount, energy=$energy, isReady=${spotter.isReady(stream)}")
            }

            while (spotter.isReady(stream)) {
                spotter.decode(stream)

                val result = spotter.getResult(stream)
                if (result.keyword.isNotBlank()) {
                    val keyword = result.keyword
                    Log.i(TAG, "Wake word detected: $keyword")

                    // Reset stream for next detection
                    spotter.reset(stream)

                    // Notify UI
                    sendBroadcast(Intent(ACTION_WAKE_DETECTED).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_KEYWORD, keyword)
                    })

                    updateNotification("Wake word detected: \"$keyword\" — Listening...")

                    // Reset notification after 3 seconds
                    Thread.sleep(3000)
                    if (isRunning) {
                        updateNotification("Listening — say \"Hey Assistant\"")
                    }
                }
            }
        }

        Log.i(TAG, "Audio processing loop exited")
    }

    private fun stopDetection() {
        isRunning = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            kwsStream?.release()
        } catch (_: Exception) {}
        kwsStream = null

        try {
            kws?.release()
        } catch (_: Exception) {}
        kws = null

        Log.i(TAG, "Detection stopped")
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LifeLog V2")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
