package com.opennovel.reader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Wraps Android's [TextToSpeech] engine for reading a chapter aloud paragraph by
 * paragraph. Exposes the currently spoken paragraph index so the reader can
 * auto-scroll and highlight in sync. Speed/pitch/voice come from settings.
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var paragraphs: List<String> = emptyList()

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    fun init(onReady: () -> Unit = {}) {
        if (tts != null) { onReady(); return }
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(listener)
                _state.value = _state.value.copy(ready = true)
                onReady()
            } else {
                _state.value = _state.value.copy(ready = false, error = "TTS init failed")
            }
        }
    }

    fun availableVoices(): List<Voice> = tts?.voices?.toList().orEmpty()

    fun configure(speed: Float, pitch: Float, voiceName: String, languageTag: String = "") {
        tts?.setSpeechRate(speed)
        tts?.setPitch(pitch)
        if (languageTag.isNotBlank()) {
            val locale = Locale.forLanguageTag(languageTag)
            // MISSING_DATA/NOT_SUPPORTED mean the voice pack isn't installed; keep
            // the current language rather than falling back to silence.
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                _state.value = _state.value.copy(
                    error = "No installed voice for ${locale.displayLanguage}; using the default",
                )
            }
        }
        if (voiceName.isNotBlank()) {
            tts?.voices?.firstOrNull { it.name == voiceName }?.let { tts?.voice = it }
        }
    }

    fun speak(paragraphs: List<String>, startIndex: Int = 0) {
        this.paragraphs = paragraphs
        _state.value = _state.value.copy(speaking = true, paused = false, index = startIndex)
        enqueueFrom(startIndex)
    }

    private fun enqueueFrom(index: Int) {
        tts?.stop()
        for (i in index until paragraphs.size) {
            val mode = if (i == index) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(paragraphs[i], mode, null, i.toString())
        }
    }

    fun pause() {
        tts?.stop()
        _state.value = _state.value.copy(paused = true, speaking = false)
    }

    fun resume() {
        val idx = _state.value.index
        _state.value = _state.value.copy(paused = false, speaking = true)
        enqueueFrom(idx)
    }

    fun stop() {
        tts?.stop()
        _state.value = TtsState(ready = _state.value.ready)
    }

    fun skipNext() = speak(paragraphs, (_state.value.index + 1).coerceAtMost(paragraphs.lastIndex))
    fun skipPrevious() = speak(paragraphs, (_state.value.index - 1).coerceAtLeast(0))

    fun shutdown() {
        tts?.stop(); tts?.shutdown(); tts = null
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            _state.value = _state.value.copy(index = utteranceId.toIntOrNull() ?: _state.value.index)
        }
        override fun onDone(utteranceId: String) {
            if ((utteranceId.toIntOrNull() ?: -1) >= paragraphs.lastIndex) {
                _state.value = _state.value.copy(speaking = false, finished = true)
            }
        }
        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String) {
            _state.value = _state.value.copy(error = "Utterance error")
        }
    }
}

data class TtsState(
    val ready: Boolean = false,
    val speaking: Boolean = false,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val index: Int = 0,
    val error: String? = null,
)
