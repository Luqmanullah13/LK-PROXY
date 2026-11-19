package com.example.data

import androidx.annotation.DrawableRes
import com.example.R

enum class ConnectionStatus {
    IDLE,
    LINKING,
    ACTIVE
}

enum class VoicePreset(
    val displayName: String,
    val shortLabel: String,
    @DrawableRes val iconRes: Int,
    val defaultSpeed: Int,
    val defaultPitch: Int
) {
    BABY("Baby", "Baby", R.drawable.ic_voice_baby, 20, 58),
    GIRL("Girl", "Girl", R.drawable.ic_voice_girl, 10, 36),
    OLD_MAN("Old Man", "Old Man", R.drawable.ic_voice_old_man, -18, -46),
    FEMALE("Female", "Female", R.drawable.ic_voice_female, 0, 24),
    TUNG_TUNG_SAHUR("Tung Tung Sahur", "Tung Tung", R.drawable.ic_voice_tung_sahur, 14, 22),
    CUSTOM("Custom", "Custom", R.drawable.ic_voice_custom, 0, 0)
}

data class MenuFeaturesState(
    val gameBoosterEnabled: Boolean = false,
    val fpsRate: Int = 60,
    val voiceChangerEnabled: Boolean = false,
    val voicePreset: VoicePreset = VoicePreset.BABY,
    val voiceSpeedRate: Int = VoicePreset.BABY.defaultSpeed,
    val voicePitchRate: Int = VoicePreset.BABY.defaultPitch,
    val internalAudioEnabled: Boolean = false,
    val signalSpeedEnabled: Boolean = false
)

data class TelemetrySnapshot(
    val downloadKbps: Double = 0.0,
    val uploadKbps: Double = 0.0,
    val pingMs: Int = 0,
    val slowRateLabel: String = "STANDBY",
    val availableRamMb: Long = 0L,
    val totalRamMb: Long = 0L,
    val liveFps: Int = 60,
    val voiceStatusLabel: String = "OFF",
    val recordingStatusLabel: String = "OFF",
    val lastSavedAudioFile: String? = null
)

data class LkProxyUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val linkingDotsCount: Int = 1,
    val features: MenuFeaturesState = MenuFeaturesState(),
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val hasOverlayPermission: Boolean = false,
    val hasRecordAudioPermission: Boolean = false,
    val showOverlayPermissionDialog: Boolean = false,
    val showWhatsappDialog: Boolean = false,
    val statusBannerMessage: String? = null
)
