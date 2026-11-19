package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lkDataStore: DataStore<Preferences> by preferencesDataStore(name = "lk_proxy_prefs")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val GAME_BOOSTER = booleanPreferencesKey("game_booster_enabled")
        val FPS_RATE = intPreferencesKey("fps_rate")
        val VOICE_CHANGER = booleanPreferencesKey("voice_changer_enabled")
        val VOICE_PRESET = stringPreferencesKey("voice_preset")
        val VOICE_SPEED = intPreferencesKey("voice_speed_rate")
        val VOICE_PITCH = intPreferencesKey("voice_pitch_rate")
        val INTERNAL_AUDIO = booleanPreferencesKey("internal_audio_enabled")
        val SIGNAL_SPEED = booleanPreferencesKey("signal_speed_enabled")
    }

    val featuresFlow: Flow<MenuFeaturesState> = context.lkDataStore.data.map { prefs ->
        val presetName = prefs[Keys.VOICE_PRESET]
        val preset = VoicePreset.entries.firstOrNull { it.name == presetName } ?: VoicePreset.BABY
        MenuFeaturesState(
            gameBoosterEnabled = prefs[Keys.GAME_BOOSTER] ?: false,
            fpsRate = (prefs[Keys.FPS_RATE] ?: 60).coerceIn(0, 90),
            voiceChangerEnabled = prefs[Keys.VOICE_CHANGER] ?: false,
            voicePreset = preset,
            voiceSpeedRate = (prefs[Keys.VOICE_SPEED] ?: preset.defaultSpeed).coerceIn(-100, 100),
            voicePitchRate = (prefs[Keys.VOICE_PITCH] ?: preset.defaultPitch).coerceIn(-100, 100),
            internalAudioEnabled = prefs[Keys.INTERNAL_AUDIO] ?: false,
            signalSpeedEnabled = prefs[Keys.SIGNAL_SPEED] ?: false
        )
    }

    suspend fun setGameBooster(enabled: Boolean) {
        context.lkDataStore.edit { it[Keys.GAME_BOOSTER] = enabled }
    }

    suspend fun setFpsRate(fps: Int) {
        context.lkDataStore.edit { it[Keys.FPS_RATE] = fps.coerceIn(0, 90) }
    }

    suspend fun setVoiceChanger(enabled: Boolean) {
        context.lkDataStore.edit { it[Keys.VOICE_CHANGER] = enabled }
    }

    suspend fun setVoicePreset(preset: VoicePreset, speed: Int, pitch: Int) {
        context.lkDataStore.edit {
            it[Keys.VOICE_PRESET] = preset.name
            it[Keys.VOICE_SPEED] = speed.coerceIn(-100, 100)
            it[Keys.VOICE_PITCH] = pitch.coerceIn(-100, 100)
        }
    }

    suspend fun setVoiceSpeedRate(speed: Int) {
        context.lkDataStore.edit {
            it[Keys.VOICE_PRESET] = VoicePreset.CUSTOM.name
            it[Keys.VOICE_SPEED] = speed.coerceIn(-100, 100)
        }
    }

    suspend fun setVoicePitchRate(pitch: Int) {
        context.lkDataStore.edit {
            it[Keys.VOICE_PRESET] = VoicePreset.CUSTOM.name
            it[Keys.VOICE_PITCH] = pitch.coerceIn(-100, 100)
        }
    }

    suspend fun setInternalAudio(enabled: Boolean) {
        context.lkDataStore.edit { it[Keys.INTERNAL_AUDIO] = enabled }
    }

    suspend fun setSignalSpeed(enabled: Boolean) {
        context.lkDataStore.edit { it[Keys.SIGNAL_SPEED] = enabled }
    }
}
