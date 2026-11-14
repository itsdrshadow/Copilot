package com.antigravity.drivingreport.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.antigravity.drivingreport.core.settings.AppSettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class VoiceProfile(
    val id: String,
    val name: String,
    val description: String,
    val locale: Locale,
    val pitch: Float = 1.0f,
    val speechRate: Float = 0.95f
)

class AutomotiveVoiceAssistant private constructor(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isInitialized: Boolean = false
    private val pendingMessages = mutableListOf<String>()

    private val _availableVoices = MutableStateFlow<List<VoiceProfile>>(getDefaultVoiceProfiles())
    val availableVoices: StateFlow<List<VoiceProfile>> = _availableVoices.asStateFlow()

    init {
        try {
            tts = TextToSpeech(appContext, this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AutomotiveVoiceAssistant? = null

        fun getInstance(context: Context): AutomotiveVoiceAssistant {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutomotiveVoiceAssistant(context).also { INSTANCE = it }
            }
        }

        fun getDefaultVoiceProfiles(): List<VoiceProfile> {
            return listOf(
                VoiceProfile(
                    id = "en_in_natural_female",
                    name = "Indian English (Natural)",
                    description = "Clear warm Indian English voice optimized for automotive prompts",
                    locale = Locale("en", "IN"),
                    pitch = 1.05f,
                    speechRate = 0.95f
                ),
                VoiceProfile(
                    id = "en_in_deep_male",
                    name = "Indian English (Deep)",
                    description = "Authoritative deep male voice with crisp automotive clarity",
                    locale = Locale("en", "IN"),
                    pitch = 0.88f,
                    speechRate = 0.92f
                ),
                VoiceProfile(
                    id = "hi_in_natural",
                    name = "Hindi / Hinglish (Natural)",
                    description = "Smooth bilingual assistant voice for Indian roads",
                    locale = Locale("hi", "IN"),
                    pitch = 1.0f,
                    speechRate = 0.95f
                ),
                VoiceProfile(
                    id = "en_us_studio",
                    name = "Global English (Studio)",
                    description = "Modern infotainment studio navigation voice",
                    locale = Locale.US,
                    pitch = 1.0f,
                    speechRate = 0.95f
                )
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return

            applyConfiguredVoice(engine)

            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                engine.setAudioAttributes(audioAttributes)
            } catch (_: Exception) {}

            isInitialized = true

            // Try to find matching high quality system voices
            querySystemVoices(engine)

            synchronized(pendingMessages) {
                for (msg in pendingMessages) {
                    speakInternal(msg)
                }
                pendingMessages.clear()
            }
        }
    }

    private fun applyConfiguredVoice(engine: TextToSpeech) {
        val selectedVoiceId = AppSettingsManager.settingsState.value.ttsVoiceId
        val profile = _availableVoices.value.firstOrNull { it.id == selectedVoiceId } 
            ?: _availableVoices.value.first()

        val result = engine.setLanguage(profile.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.ENGLISH
        }

        // Check if device TTS engine supports selecting specific high-quality Voice objects
        try {
            val systemVoices = engine.voices
            if (!systemVoices.isNullOrEmpty()) {
                val highQualityMatch = systemVoices.firstOrNull { voice ->
                    val isLocaleMatch = voice.locale.language == profile.locale.language &&
                            (profile.locale.country.isBlank() || voice.locale.country == profile.locale.country)
                    val isHighQuality = !voice.isNetworkConnectionRequired &&
                            (voice.quality >= Voice.QUALITY_HIGH || voice.name.contains("natural", ignoreCase = true) || voice.name.contains("cxx", ignoreCase = true))
                    isLocaleMatch && isHighQuality
                } ?: systemVoices.firstOrNull { it.locale.language == profile.locale.language }

                if (highQualityMatch != null) {
                    engine.voice = highQualityMatch
                }
            }
        } catch (_: Exception) {}

        engine.setSpeechRate(profile.speechRate)
        engine.setPitch(profile.pitch)
    }

    private fun querySystemVoices(engine: TextToSpeech) {
        try {
            // Keep curated profiles and ensure engine matches chosen configuration
            applyConfiguredVoice(engine)
        } catch (_: Exception) {}
    }

    fun reconfigureVoice() {
        val engine = tts ?: return
        if (isInitialized) {
            applyConfiguredVoice(engine)
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        val settings = AppSettingsManager.settingsState.value
        if (!settings.voiceGreetingsEnabled) return

        if (!isInitialized) {
            synchronized(pendingMessages) {
                if (pendingMessages.size < 3) {
                    pendingMessages.add(text)
                }
            }
            return
        }

        speakInternal(text, flush)
    }

    private fun speakInternal(text: String, flush: Boolean = true) {
        val engine = tts ?: return
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "car_launcher_${System.currentTimeMillis()}")
        }
        try {
            engine.speak(text, queueMode, params, "car_launcher_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun testVoicePrompt(profile: VoiceProfile) {
        val engine = tts ?: return
        engine.setLanguage(profile.locale)
        engine.setPitch(profile.pitch)
        engine.setSpeechRate(profile.speechRate)

        val sample = when (profile.locale.language) {
            "hi" -> "Namaste, Car Launcher automotive voice assistant active hai. Safar shubh ho."
            else -> "Hello, your automotive cockpit assistant is ready. Have a safe drive."
        }
        speakInternal(sample, flush = true)
    }

    fun speakWelcomeDeparture(originName: String) {
        val name = if (originName.isNotBlank() && originName != "Start Location") originName else "Origin"
        speak("Journey started from $name. Have a safe drive.", flush = true)
    }

    fun speakArrival(
        destinationName: String,
        distanceKm: Double,
        mileageKmpl: Double,
        durationMinutes: Int
    ) {
        val dest = if (destinationName.isNotBlank()) destinationName else "your destination"
        val distFormatted = String.format(Locale.US, "%.1f", distanceKm)
        val mileageFormatted = if (mileageKmpl > 0.0) String.format(Locale.US, "%.1f", mileageKmpl) else "18.0"

        val speech = "Welcome to $dest. Today's journey was $distFormatted kilometers, completed in $durationMinutes minutes with an efficiency of $mileageFormatted kilometers per liter."
        speak(speech, flush = true)
    }

    fun speakRefuelPrompt(stationName: String) {
        val name = if (stationName.isNotBlank()) stationName else "Fuel Station"
        speak("Arrival detected at $name. Remember to record your fuel fill-up on the cockpit.", flush = false)
    }

    fun speakFatigueWarning(continuousHours: Double) {
        val hrsFormatted = String.format(Locale.US, "%.1f", continuousHours)
        speak("Notice: You have been driving continuously for $hrsFormatted hours. Please consider taking a rest break.", flush = true)
    }

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isInitialized = false
    }
}
