package com.example.data

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.core.SystemTelemetryMonitor
import com.example.service.LkAudioService
import com.example.service.LkOverlayService
import com.example.service.MediaProjectionConsentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LkProxyRepository(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dataStore = SettingsDataStore(app)
    private val telemetryMonitor = SystemTelemetryMonitor(app)

    private val _uiState = MutableStateFlow(LkProxyUiState())
    val uiState: StateFlow<LkProxyUiState> = _uiState.asStateFlow()

    private var linkingJob: Job? = null
    private var telemetryJob: Job? = null

    init {
        refreshPermissions()
        scope.launch {
            dataStore.featuresFlow.collect { savedFeatures ->
                _uiState.update { state ->
                    val voiceLabel = if (savedFeatures.voiceChangerEnabled && state.hasRecordAudioPermission) {
                        "ON • ${savedFeatures.voicePreset.displayName.uppercase()}"
                    } else if (!savedFeatures.voiceChangerEnabled) {
                        "OFF"
                    } else {
                        state.telemetry.voiceStatusLabel
                    }
                    state.copy(
                        features = savedFeatures,
                        telemetry = state.telemetry.copy(voiceStatusLabel = voiceLabel)
                    )
                }
            }
        }
    }

    fun refreshPermissions() {
        val overlayGranted = Settings.canDrawOverlays(app)
        val recordGranted = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        _uiState.update {
            it.copy(
                hasOverlayPermission = overlayGranted,
                hasRecordAudioPermission = recordGranted
            )
        }
    }

    fun onStartClicked() {
        refreshPermissions()
        if (!Settings.canDrawOverlays(app)) {
            _uiState.update { it.copy(showOverlayPermissionDialog = true) }
            return
        }
        if (_uiState.value.connectionStatus != ConnectionStatus.IDLE) return

        linkingJob?.cancel()
        linkingJob = scope.launch {
            _uiState.update {
                it.copy(
                    connectionStatus = ConnectionStatus.LINKING,
                    linkingDotsCount = 1,
                    statusBannerMessage = null
                )
            }

            val probeJob = launch(Dispatchers.IO) {
                telemetryMonitor.measurePingAndSlowRate()
            }

            for (step in 0 until 6) {
                delay(500L)
                _uiState.update { state ->
                    state.copy(linkingDotsCount = (step % 3) + 1)
                }
            }
            probeJob.join()

            activateTunnelAndOverlay()
        }
    }

    private fun activateTunnelAndOverlay() {
        _uiState.update { it.copy(connectionStatus = ConnectionStatus.ACTIVE) }
        telemetryMonitor.startFpsMonitoring()
        startTelemetryLoop()
        startOverlayService()
    }

    fun onStopClicked() {
        linkingJob?.cancel()
        telemetryJob?.cancel()
        telemetryMonitor.stopFpsMonitoring()
        stopOverlayService()
        stopAudioService()
        scope.launch {
            dataStore.setVoiceChanger(false)
            dataStore.setInternalAudio(false)
        }
        _uiState.update {
            it.copy(
                connectionStatus = ConnectionStatus.IDLE,
                telemetry = TelemetrySnapshot(),
                statusBannerMessage = null
            )
        }
    }

    fun dismissOverlayPermissionDialog() {
        _uiState.update { it.copy(showOverlayPermissionDialog = false) }
    }

    fun setWhatsappDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showWhatsappDialog = visible) }
    }

    fun setGameBooster(enabled: Boolean) {
        scope.launch {
            dataStore.setGameBooster(enabled)
            if (enabled) {
                val freedMb = telemetryMonitor.trimAppCaches()
                val (avail, total) = telemetryMonitor.readMemoryInfo()
                _uiState.update {
                    it.copy(
                        telemetry = it.telemetry.copy(availableRamMb = avail, totalRamMb = total),
                        statusBannerMessage = if (freedMb > 0) "GAME BOOSTER ACTIVE (+${freedMb}MB)" else "GAME BOOSTER ACTIVE (${avail}MB FREE)"
                    )
                }
            } else {
                _uiState.update { it.copy(statusBannerMessage = "GAME BOOSTER STANDBY") }
            }
        }
    }

    fun setFpsRate(fps: Int) {
        val clamped = fps.coerceIn(0, 90)
        scope.launch {
            dataStore.setFpsRate(clamped)
        }
    }

    fun selectVoicePreset(preset: VoicePreset) {
        val current = _uiState.value.features
        val speed = if (preset == VoicePreset.CUSTOM) current.voiceSpeedRate else preset.defaultSpeed
        val pitch = if (preset == VoicePreset.CUSTOM) current.voicePitchRate else preset.defaultPitch
        scope.launch {
            dataStore.setVoicePreset(preset, speed, pitch)
            if (_uiState.value.features.voiceChangerEnabled) {
                pushVoiceParamsToService(preset, speed, pitch)
            }
        }
    }

    fun setVoiceSpeedRate(speed: Int) {
        val clamped = speed.coerceIn(-100, 100)
        val pitch = _uiState.value.features.voicePitchRate
        scope.launch {
            dataStore.setVoiceSpeedRate(clamped)
            if (_uiState.value.features.voiceChangerEnabled) {
                pushVoiceParamsToService(VoicePreset.CUSTOM, clamped, pitch)
            }
        }
    }

    fun setVoicePitchRate(pitch: Int) {
        val clamped = pitch.coerceIn(-100, 100)
        val speed = _uiState.value.features.voiceSpeedRate
        scope.launch {
            dataStore.setVoicePitchRate(clamped)
            if (_uiState.value.features.voiceChangerEnabled) {
                pushVoiceParamsToService(VoicePreset.CUSTOM, speed, clamped)
            }
        }
    }

    fun toggleVoiceChanger(enabled: Boolean, onNeedMicPermission: (() -> Unit)? = null) {
        refreshPermissions()
        if (enabled && !_uiState.value.hasRecordAudioPermission) {
            _uiState.update {
                it.copy(
                    telemetry = it.telemetry.copy(voiceStatusLabel = "MIC PERMISSION NEEDED"),
                    statusBannerMessage = "GRANT MICROPHONE PERMISSION IN APP"
                )
            }
            onNeedMicPermission?.invoke()
            return
        }

        val features = _uiState.value.features
        scope.launch {
            dataStore.setVoiceChanger(enabled)
            if (enabled) {
                val intent = Intent(app, LkAudioService::class.java).apply {
                    action = LkAudioService.ACTION_START_VOICE
                    putExtra(LkAudioService.EXTRA_VOICE_PRESET, features.voicePreset.name)
                    putExtra(LkAudioService.EXTRA_VOICE_SPEED, features.voiceSpeedRate)
                    putExtra(LkAudioService.EXTRA_VOICE_PITCH, features.voicePitchRate)
                }
                ContextCompat.startForegroundService(app, intent)
                _uiState.update {
                    it.copy(
                        telemetry = it.telemetry.copy(
                            voiceStatusLabel = "ON • ${features.voicePreset.displayName.uppercase()}"
                        )
                    )
                }
            } else {
                val intent = Intent(app, LkAudioService::class.java).apply {
                    action = LkAudioService.ACTION_STOP_VOICE
                }
                app.startService(intent)
                _uiState.update {
                    it.copy(telemetry = it.telemetry.copy(voiceStatusLabel = "OFF"))
                }
            }
        }
    }

    private fun pushVoiceParamsToService(preset: VoicePreset, speed: Int, pitch: Int) {
        val intent = Intent(app, LkAudioService::class.java).apply {
            action = LkAudioService.ACTION_UPDATE_VOICE_PARAMS
            putExtra(LkAudioService.EXTRA_VOICE_PRESET, preset.name)
            putExtra(LkAudioService.EXTRA_VOICE_SPEED, speed)
            putExtra(LkAudioService.EXTRA_VOICE_PITCH, pitch)
        }
        app.startService(intent)
    }

    fun toggleInternalAudio(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _uiState.update {
                it.copy(
                    telemetry = it.telemetry.copy(recordingStatusLabel = "API 29+ REQUIRED"),
                    statusBannerMessage = "INTERNAL AUDIO CAPTURE REQUIRES ANDROID 10+"
                )
            }
            return
        }

        if (enabled) {
            val consentIntent = Intent(app, MediaProjectionConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(consentIntent)
        } else {
            scope.launch { dataStore.setInternalAudio(false) }
            val intent = Intent(app, LkAudioService::class.java).apply {
                action = LkAudioService.ACTION_STOP_RECORDING
            }
            app.startService(intent)
            _uiState.update {
                it.copy(telemetry = it.telemetry.copy(recordingStatusLabel = "OFF"))
            }
        }
    }

    fun onMediaProjectionConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            scope.launch { dataStore.setInternalAudio(true) }
            val intent = Intent(app, LkAudioService::class.java).apply {
                action = LkAudioService.ACTION_START_RECORDING
                putExtra(LkAudioService.EXTRA_RESULT_CODE, resultCode)
                putExtra(LkAudioService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(app, intent)
            _uiState.update {
                it.copy(
                    telemetry = it.telemetry.copy(recordingStatusLabel = "REC • ACTIVE"),
                    statusBannerMessage = "INTERNAL AUDIO RECORDING STARTED"
                )
            }
        } else {
            scope.launch { dataStore.setInternalAudio(false) }
            _uiState.update {
                it.copy(
                    telemetry = it.telemetry.copy(recordingStatusLabel = "CONSENT CANCELLED"),
                    statusBannerMessage = "CAPTURE CONSENT CANCELLED"
                )
            }
        }
    }

    fun updateRecordingStatus(label: String, savedFileName: String? = null) {
        _uiState.update {
            it.copy(
                telemetry = it.telemetry.copy(
                    recordingStatusLabel = label,
                    lastSavedAudioFile = savedFileName ?: it.telemetry.lastSavedAudioFile
                )
            )
        }
    }

    fun setSignalSpeedEnabled(enabled: Boolean) {
        scope.launch {
            dataStore.setSignalSpeed(enabled)
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            var tick = 0
            while (isActive) {
                val (availMb, totalMb) = telemetryMonitor.readMemoryInfo()
                val (downKbps, upKbps) = telemetryMonitor.sampleTrafficSpeedKbps()
                val fps = telemetryMonitor.getLiveFps()

                var ping = _uiState.value.telemetry.pingMs
                var slowLabel = _uiState.value.telemetry.slowRateLabel
                if (tick % 3 == 0) {
                    val (measuredPing, label) = telemetryMonitor.measurePingAndSlowRate()
                    ping = measuredPing
                    slowLabel = label
                }

                _uiState.update { state ->
                    state.copy(
                        telemetry = state.telemetry.copy(
                            downloadKbps = downKbps,
                            uploadKbps = upKbps,
                            pingMs = ping,
                            slowRateLabel = slowLabel,
                            availableRamMb = availMb,
                            totalRamMb = totalMb,
                            liveFps = fps
                        )
                    )
                }
                tick++
                delay(1000L)
            }
        }
    }

    private fun startOverlayService() {
        val intent = Intent(app, LkOverlayService::class.java).apply {
            action = LkOverlayService.ACTION_SHOW
        }
        ContextCompat.startForegroundService(app, intent)
    }

    private fun stopOverlayService() {
        val intent = Intent(app, LkOverlayService::class.java).apply {
            action = LkOverlayService.ACTION_STOP
        }
        app.startService(intent)
    }

    private fun stopAudioService() {
        val intent = Intent(app, LkAudioService::class.java).apply {
            action = LkAudioService.ACTION_STOP_ALL
        }
        app.startService(intent)
    }
}
