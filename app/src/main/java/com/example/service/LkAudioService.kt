package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.LkProxyApplication
import com.example.MainActivity
import com.example.R
import com.example.data.VoicePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class LkAudioService : Service() {

    companion object {
        const val ACTION_START_VOICE = "com.example.action.START_VOICE"
        const val ACTION_STOP_VOICE = "com.example.action.STOP_VOICE"
        const val ACTION_UPDATE_VOICE_PARAMS = "com.example.action.UPDATE_VOICE_PARAMS"
        const val ACTION_START_RECORDING = "com.example.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.action.STOP_RECORDING"
        const val ACTION_STOP_ALL = "com.example.action.STOP_AUDIO_ALL"

        const val EXTRA_VOICE_PRESET = "extra_voice_preset"
        const val EXTRA_VOICE_SPEED = "extra_voice_speed"
        const val EXTRA_VOICE_PITCH = "extra_voice_pitch"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "lk_proxy_audio_channel"
        private const val NOTIFICATION_ID = 2002
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var voiceJob: Job? = null
    private var recordingJob: Job? = null
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var voiceActive = false

    @Volatile
    private var recordingActive = false

    @Volatile
    private var activePreset: VoicePreset = VoicePreset.BABY

    @Volatile
    private var speedRatePercent: Int = VoicePreset.BABY.defaultSpeed

    @Volatile
    private var pitchRatePercent: Int = VoicePreset.BABY.defaultPitch

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VOICE -> {
                updateVoiceParamsFromIntent(intent)
                startVoiceChanger()
            }
            ACTION_UPDATE_VOICE_PARAMS -> {
                updateVoiceParamsFromIntent(intent)
            }
            ACTION_STOP_VOICE -> stopVoiceChanger()
            ACTION_START_RECORDING -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    startInternalAudioRecording(resultCode, data)
                }
            }
            ACTION_STOP_RECORDING -> stopInternalAudioRecording()
            ACTION_STOP_ALL -> {
                stopVoiceChanger()
                stopInternalAudioRecording()
                stopSelfSafe()
            }
        }
        return START_NOT_STICKY
    }

    private fun updateVoiceParamsFromIntent(intent: Intent) {
        val presetName = intent.getStringExtra(EXTRA_VOICE_PRESET)
        activePreset = VoicePreset.entries.firstOrNull { it.name == presetName } ?: activePreset
        speedRatePercent = intent.getIntExtra(EXTRA_VOICE_SPEED, speedRatePercent).coerceIn(-100, 100)
        pitchRatePercent = intent.getIntExtra(EXTRA_VOICE_PITCH, pitchRatePercent).coerceIn(-100, 100)
    }

    private fun startForegroundWithCurrentTypes(requireMic: Boolean, requireProjection: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (requireMic && ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (requireProjection) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (type != 0) {
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startVoiceChanger() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelfIfIdle()
            return
        }
        voiceActive = true
        startForegroundWithCurrentTypes(requireMic = true, requireProjection = recordingActive)

        voiceJob?.cancel()
        voiceJob = serviceScope.launch {
            val sampleRate = 22050
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val audioRecord = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            }.getOrNull() ?: return@launch

            val audioTrack = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: run {
                audioRecord.release()
                return@launch
            }

            // Dual-tap circular buffer granular pitch shifter + wood resonator
            val ringSize = 4096
            val ringMask = ringSize - 1
            val ring = FloatArray(ringSize)
            var writeIdx = 0

            val grainWindow = 1024
            var phase1 = 0f
            var phase2 = grainWindow / 2f

            // Hollow wood-cavity comb buffer for Tung Tung Sahur mode
            val woodDelay1 = 92   // ~4.2 ms at 22050 Hz
            val woodDelay2 = 192  // ~8.7 ms at 22050 Hz
            val woodBuf = FloatArray(512)
            var woodWrite = 0

            // Tremor phase for Old Man mode
            var tremorPhase = 0.0
            val tremorStep = 2.0 * PI * 26.0 / sampleRate

            val inBuffer = ShortArray(512)
            val outBuffer = ShortArray(1024)
            var lastSpeedRate = Int.MIN_VALUE

            try {
                audioRecord.startRecording()
                audioTrack.play()

                while (isActive && voiceActive) {
                    val read = audioRecord.read(inBuffer, 0, inBuffer.size)
                    if (read <= 0) continue

                    val currentSpeed = speedRatePercent
                    val currentPitch = pitchRatePercent
                    val preset = activePreset

                    // Apply speed rate (-100..+100 -> 0.55x..1.65x playback rate)
                    if (currentSpeed != lastSpeedRate) {
                        lastSpeedRate = currentSpeed
                        val speedRatio = if (currentSpeed >= 0) {
                            1.0f + (currentSpeed / 100f) * 0.65f
                        } else {
                            1.0f + (currentSpeed / 100f) * 0.45f
                        }
                        val targetPlaybackHz = (sampleRate * speedRatio).toInt()
                            .coerceIn(sampleRate / 2, sampleRate * 2)
                        runCatching { audioTrack.playbackRate = targetPlaybackHz }
                    }

                    // Map pitch (-100..+100) to musical pitch ratio (0.50x..2.00x)
                    val pitchRatio = 2.0.pow(currentPitch / 100.0).toFloat().coerceIn(0.5f, 2.0f)
                    val tapAdvance = pitchRatio - 1.0f

                    for (i in 0 until read) {
                        val sample = inBuffer[i] / 32768f
                        ring[writeIdx and ringMask] = sample

                        // Tap 1 and Tap 2 crossfaded granular pitch shift
                        val delay1 = phase1
                        val delay2 = phase2

                        val pos1 = writeIdx - delay1
                        val idx1A = pos1.toInt()
                        val frac1 = pos1 - idx1A
                        val s1 = ring[idx1A and ringMask] * (1f - frac1) +
                            ring[(idx1A + 1) and ringMask] * frac1

                        val pos2 = writeIdx - delay2
                        val idx2A = pos2.toInt()
                        val frac2 = pos2 - idx2A
                        val s2 = ring[idx2A and ringMask] * (1f - frac2) +
                            ring[(idx2A + 1) and ringMask] * frac2

                        // Triangle crossfade envelope across the grain window
                        val w1 = 1f - abs((2f * phase1 / grainWindow) - 1f)
                        val w2 = 1f - abs((2f * phase2 / grainWindow) - 1f)
                        val norm = (w1 + w2).coerceAtLeast(0.001f)
                        var processed = (s1 * w1 + s2 * w2) / norm

                        // Character-specific DSP coloring
                        when (preset) {
                            VoicePreset.TUNG_TUNG_SAHUR -> {
                                // Hollow wood-log comb resonance ("tung tung" wooden timbre)
                                val tapA = woodBuf[(woodWrite - woodDelay1 + woodBuf.size) % woodBuf.size]
                                val tapB = woodBuf[(woodWrite - woodDelay2 + woodBuf.size) % woodBuf.size]
                                val woodSignal = processed + 0.52f * tapA - 0.34f * tapB
                                woodBuf[woodWrite] = (processed + 0.40f * tapA).coerceIn(-1f, 1f)
                                woodWrite = (woodWrite + 1) % woodBuf.size
                                processed = woodSignal * 0.85f
                            }
                            VoicePreset.OLD_MAN -> {
                                // Subtle vocal cord tremor / gravelly resonance
                                val tremor = (0.84 + 0.16 * sin(tremorPhase)).toFloat()
                                tremorPhase += tremorStep
                                if (tremorPhase > 2.0 * PI) tremorPhase -= 2.0 * PI
                                processed *= tremor
                            }
                            VoicePreset.BABY -> {
                                // Crisp bright boost
                                processed = (processed * 1.15f).coerceIn(-1f, 1f)
                            }
                            VoicePreset.GIRL, VoicePreset.FEMALE, VoicePreset.CUSTOM -> {
                                processed = (processed * 1.05f).coerceIn(-1f, 1f)
                            }
                        }

                        // Advance dual read-head offsets safely within [0, grainWindow)
                        phase1 -= tapAdvance
                        phase2 -= tapAdvance
                        while (phase1 < 0f) phase1 += grainWindow
                        while (phase1 >= grainWindow) phase1 -= grainWindow
                        while (phase2 < 0f) phase2 += grainWindow
                        while (phase2 >= grainWindow) phase2 -= grainWindow

                        writeIdx++
                        val pcm = (processed * 28000f).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        outBuffer[i] = pcm.toShort()
                    }
                    audioTrack.write(outBuffer, 0, read)
                }
            } finally {
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
                runCatching { audioTrack.stop() }
                runCatching { audioTrack.release() }
            }
        }
    }

    private fun stopVoiceChanger() {
        voiceActive = false
        voiceJob?.cancel()
        voiceJob = null
        stopSelfIfIdle()
    }

    private fun startInternalAudioRecording(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val repo = (application as LkProxyApplication).repository

        recordingActive = true
        startForegroundWithCurrentTypes(requireMic = voiceActive, requireProjection = true)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        mediaProjection?.stop()
        mediaProjection = projectionManager?.getMediaProjection(resultCode, data)
        val projection = mediaProjection ?: run {
            recordingActive = false
            repo.updateRecordingStatus("PROJECTION FAILED")
            stopSelfIfIdle()
            return
        }

        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
                .coerceAtLeast(4096)

            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            val recorder = runCatching {
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBuf)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            }.getOrNull() ?: run {
                recordingActive = false
                repo.updateRecordingStatus("REC INIT FAILED")
                stopSelfIfIdle()
                return@launch
            }

            val outDir = File(
                getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                "LK_Proxy_Recordings"
            ).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(outDir, "LK_AUDIO_$timestamp.wav")

            val raf = RandomAccessFile(outFile, "rw")
            raf.write(ByteArray(44))
            var totalBytes = 0L
            val buffer = ByteArray(minBuf)
            val startMs = System.currentTimeMillis()
            var lastUiTickSec = -1L

            try {
                recorder.startRecording()
                while (isActive && recordingActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        raf.write(buffer, 0, read)
                        totalBytes += read
                    }
                    val elapsedSec = (System.currentTimeMillis() - startMs) / 1000L
                    if (elapsedSec != lastUiTickSec) {
                        lastUiTickSec = elapsedSec
                        val mm = elapsedSec / 60
                        val ss = elapsedSec % 60
                        repo.updateRecordingStatus(
                            label = String.format(Locale.US, "REC • %02d:%02d", mm, ss)
                        )
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                runCatching {
                    writeWavHeader(raf, totalBytes, sampleRate, 2, 16)
                    raf.close()
                }
                repo.updateRecordingStatus(
                    label = "SAVED: ${outFile.name}",
                    savedFileName = outFile.name
                )
            }
        }
    }

    private fun stopInternalAudioRecording() {
        recordingActive = false
        recordingJob?.cancel()
        recordingJob = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        stopSelfIfIdle()
    }

    private fun writeWavHeader(
        raf: RandomAccessFile,
        pcmDataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8L
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val totalDataLen = pcmDataSize + 36L

        raf.seek(0L)
        raf.writeBytes("RIFF")
        raf.writeIntLE(totalDataLen.toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeIntLE(16)
        raf.writeShortLE(1.toShort())
        raf.writeShortLE(channels.toShort())
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(byteRate.toInt())
        raf.writeShortLE(blockAlign)
        raf.writeShortLE(bitsPerSample.toShort())
        raf.writeBytes("data")
        raf.writeIntLE(pcmDataSize.toInt())
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Short) {
        val v = value.toInt()
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private fun stopSelfIfIdle() {
        if (!voiceActive && !recordingActive) {
            stopSelfSafe()
        }
    }

    private fun stopSelfSafe() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_audio),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LK PROXY // AUDIO ENGINE")
            .setContentText("Real-time voice & audio utility active")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        voiceActive = false
        recordingActive = false
        voiceJob?.cancel()
        recordingJob?.cancel()
        runCatching { mediaProjection?.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }
}
