package com.example.yobareta.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.yobareta.MainActivity
import com.example.yobareta.R
import com.example.yobareta.alert.AlertEngine
import com.example.yobareta.audio.AudioRingBuffer
import com.example.yobareta.audio.GateMetrics
import com.example.yobareta.audio.OnDeviceAudioGate
import com.example.yobareta.model.RecognizeAlertResult
import com.example.yobareta.model.UserWatchProfile
import com.example.yobareta.net.YobaretaCloudClient
import com.example.yobareta.queue.DailyQueueManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ListenServiceUiState(
    val isListening: Boolean = false,
    val isNetworkAvailable: Boolean = true,
    val warmStatus: String = "Idle",
    val currentDbFs: Float = -60f,
    val ambientBaselineDbFs: Float = -50f,
    val speechProbability: Float = 0f,
    val chimeProbability: Float = 0f,
    val isCapturingSegment: Boolean = false,
    val usingYamnetModel: Boolean = false,
    val percentAudioSent: Float = 0f,
    val segmentsUploadedCount: Int = 0,
    val latestTranscript: String = "",
    val latestMatchesCount: Int = 0,
    val latestMatchedInfo: String = "",
    val latestAlert: RecognizeAlertResult? = null,
    val hasReplayable10sClip: Boolean = false,
    val isReplayingAudio: Boolean = false,
    val lastError: String? = null
)

class ListenService : Service() {

    companion object {
        private const val TAG = "ListenService"
        private const val FG_CHANNEL_ID = "yobareta_listen_fg_v1"
        private const val FG_NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.yobareta.action.START_LISTENING"
        const val ACTION_STOP = "com.example.yobareta.action.STOP_LISTENING"

        private const val SAMPLE_RATE_HZ = 16000
        private const val CHUNK_SAMPLES = 1600 // 100 ms per frame at 16 kHz

        private val _uiState = MutableStateFlow(ListenServiceUiState())
        val uiState: StateFlow<ListenServiceUiState> = _uiState.asStateFlow()

        @Volatile
        var activeProfile: UserWatchProfile = UserWatchProfile()
        @Volatile
        var queueManager: DailyQueueManager? = null

        @Volatile
        private var last10sReplaySnapshot: ShortArray = ShortArray(0)

        @Volatile
        private var instance: ListenService? = null

        fun dismissAlert() {
            instance?.alertEngine?.stopVibration()
            _uiState.update { it.copy(latestAlert = null) }
        }

        fun replayLast10Seconds() {
            instance?.playReplaySnapshotInRam()
        }

        fun triggerSimulatedSegmentUpload() {
            instance?.forceUploadRecent3Seconds()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    private var replayJob: Job? = null

    private val ringBuffer = AudioRingBuffer(sampleRateHz = SAMPLE_RATE_HZ, capacitySeconds = 15)
    private lateinit var audioGate: OnDeviceAudioGate
    private lateinit var alertEngine: AlertEngine
    private val cloudClient = YobaretaCloudClient()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var previouslyCalledInSession = false
    @Volatile
    private var isUploadInFlight = false
    @Volatile
    private var lastUploadCompletedMs = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioGate = OnDeviceAudioGate(applicationContext, sampleRateHz = SAMPLE_RATE_HZ)
        alertEngine = AlertEngine(applicationContext)
        ensureForegroundChannel()
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListeningAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundWithMicType()
                startAudioCaptureLoop()
                return START_STICKY
            }
        }
    }

    private fun startForegroundWithMicType() {
        val notification = buildForegroundNotification("Listening for '${activeProfile.kanjiName}' (${activeProfile.selectedFullReading})...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FG_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(FG_NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureLoop() {
        if (captureJob?.isActive == true) return

        previouslyCalledInSession = false
        ringBuffer.clear()

        // 1. Ping /warm asynchronously when user taps Start to prevent Cloud Run cold starts
        serviceScope.launch {
            _uiState.update { it.copy(warmStatus = "Warming Cloud Run...") }
            val ok = cloudClient.warmUp(activeProfile.cloudRunBaseUrl)
            _uiState.update {
                it.copy(warmStatus = if (ok) "Cloud Run Warm (asia-northeast1)" else "Cloud Run Warm Pending")
            }
        }

        // 2. Start 16 kHz mono 16-bit PCM AudioRecord loop
        captureJob = serviceScope.launch(Dispatchers.IO) {
            val minBufBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_SAMPLES * 4)

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufBytes
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AudioRecord", e)
                _uiState.update { it.copy(isListening = false, lastError = "Mic init failed: ${e.message}") }
                return@launch
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                _uiState.update { it.copy(isListening = false, lastError = "AudioRecord not initialized") }
                audioRecord.release()
                return@launch
            }

            val chunkBuffer = ShortArray(CHUNK_SAMPLES)
            try {
                audioRecord.startRecording()
                _uiState.update { it.copy(isListening = true, lastError = null) }
                Log.i(TAG, "Started 16 kHz mono PCM capture into 15 s RAM ring buffer")

                while (isActive) {
                    val readCount = audioRecord.read(chunkBuffer, 0, CHUNK_SAMPLES)
                    if (readCount > 0) {
                        // Store in 15 s RAM ring buffer (never written to disk)
                        ringBuffer.append(chunkBuffer, readCount)

                        // Run on-device YAMNet + ambient RMS baseline gate
                        val metrics: GateMetrics = audioGate.processChunk(
                            chunk = chunkBuffer,
                            count = readCount,
                            ringBuffer = ringBuffer,
                            onSegmentCut = { segmentShorts ->
                                uploadCutSegmentAsync(segmentShorts)
                            }
                        )

                        _uiState.update { state ->
                            state.copy(
                                currentDbFs = metrics.currentDbFs,
                                ambientBaselineDbFs = metrics.ambientBaselineDbFs,
                                speechProbability = metrics.speechProbability,
                                chimeProbability = metrics.chimeProbability,
                                isCapturingSegment = metrics.isCapturingSegment,
                                usingYamnetModel = metrics.usingYamnetModel,
                                percentAudioSent = metrics.percentAudioSent
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Normal service stop
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop", e)
                _uiState.update { it.copy(lastError = "Audio capture error: ${e.message}") }
            } finally {
                try {
                    audioRecord.stop()
                } catch (_: Exception) {
                }
                audioRecord.release()
                _uiState.update { it.copy(isListening = false, isCapturingSegment = false) }
            }
        }
    }

    private fun forceUploadRecent3Seconds() {
        val samples = ringBuffer.readLastSeconds(3.5f)
        if (samples.isNotEmpty()) {
            uploadCutSegmentAsync(samples, bypassCooldown = true)
        }
    }

    private fun uploadCutSegmentAsync(segmentShorts: ShortArray, bypassCooldown: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!bypassCooldown && (isUploadInFlight || (now - lastUploadCompletedMs) < 1500L)) {
            Log.d(TAG, "Skipping segment upload (in-flight or within 1.5s billing cooldown)")
            return
        }
        isUploadInFlight = true
        val pcmBytes = AudioRingBuffer.shortsToPcm16LeBytes(segmentShorts)
        val profileSnapshot = activeProfile

        serviceScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(segmentsUploadedCount = it.segmentsUploadedCount + 1)
            }
            try {
                val qm = queueManager
                if (qm != null) {
                    try {
                        val announceRes = cloudClient.announceAudio(pcmBytes)
                        val matchedInfo = announceRes.matchedSummaries.firstOrNull()?.let { m ->
                            "#${m.ticket} ${m.name} (${m.destination})"
                        } ?: if (announceRes.transcript.isNotEmpty()) "Recognized: \"${announceRes.transcript}\"" else ""

                        _uiState.update {
                            it.copy(
                                latestTranscript = announceRes.transcript,
                                latestMatchesCount = announceRes.matchesCount,
                                latestMatchedInfo = matchedInfo,
                                lastError = null
                            )
                        }

                        if (announceRes.matchesCount > 0) {
                            announceRes.matchedSummaries.forEach { m ->
                                qm.markPatientCalledByMatch(m.ticket.ifEmpty { m.name }, m.destination)
                            }
                        }
                        return@launch
                    } catch (e: Exception) {
                        Log.w(TAG, "Nurse desk announce failed: ${e.message}")
                        return@launch
                    }
                }

                val result = cloudClient.recognizeSegment(
                    pcm16LeBytes = pcmBytes,
                    profile = profileSnapshot,
                    previouslyCalled = previouslyCalledInSession
                )
                if (result.tier in listOf("possible", "called", "recall")) {
                    if (result.tier == "called" || result.tier == "recall") {
                        previouslyCalledInSession = true
                    }
                    // Snapshot last 10 seconds in RAM for the 10 s replay button
                    last10sReplaySnapshot = ringBuffer.readLastSeconds(10.0f)
                    _uiState.update {
                        it.copy(
                            latestAlert = result,
                            hasReplayable10sClip = last10sReplaySnapshot.isNotEmpty(),
                            lastError = null
                        )
                    }
                    alertEngine.triggerAlert(result)
                } else {
                    _uiState.update { it.copy(lastError = null) }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Segment upload cancelled on stop")
            } catch (e: Exception) {
                Log.w(TAG, "Segment recognize failed: ${e.message}")
                _uiState.update {
                    it.copy(lastError = "Cloud recognize error: ${e.localizedMessage}")
                }
            } finally {
                lastUploadCompletedMs = android.os.SystemClock.elapsedRealtime()
                isUploadInFlight = false
            }
        }
    }

    private fun playReplaySnapshotInRam() {
        val samples = last10sReplaySnapshot
        if (samples.isEmpty() || replayJob?.isActive == true) return

        replayJob = serviceScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isReplayingAudio = true) }
            var track: AudioTrack? = null
            try {
                val byteLen = samples.size * 2
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(byteLen.coerceAtLeast(SAMPLE_RATE_HZ * 2))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size)
                track.play()
                val durationMs = (samples.size * 1000L) / SAMPLE_RATE_HZ
                kotlinx.coroutines.delay(durationMs + 150L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to replay 10s RAM audio", e)
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {
                }
                _uiState.update { it.copy(isReplayingAudio = false) }
            }
        }
    }

    private fun registerNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _uiState.update { it.copy(isNetworkAvailable = true) }
            }

            override fun onLost(network: Network) {
                val stillConnected = isCurrentlyOnline(cm)
                _uiState.update { it.copy(isNetworkAvailable = stillConnected) }
                if (!stillConnected && _uiState.value.isListening) {
                    // Loud warning if the network drops (never fail silently)
                    alertEngine.triggerNetworkDropWarning()
                }
            }
        }
        networkCallback = callback
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun isCurrentlyOnline(cm: ConnectivityManager): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun stopListeningAndSelf() {
        captureJob?.cancel()
        captureJob = null
        ringBuffer.clear()
        last10sReplaySnapshot = ShortArray(0)
        alertEngine.stopVibration()
        _uiState.update {
            it.copy(
                isListening = false,
                isCapturingSegment = false,
                hasReplayable10sClip = false
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureForegroundChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            FG_CHANNEL_ID,
            "Yobareta Active Listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Yobareta is actively listening in a waiting room"
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ListenService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Yobareta — Waiting Room Listener")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(0, "Stop", pendingStop)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        captureJob?.cancel()
        replayJob?.cancel()
        ringBuffer.clear()
        last10sReplaySnapshot = ShortArray(0)
        audioGate.close()
        networkCallback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
