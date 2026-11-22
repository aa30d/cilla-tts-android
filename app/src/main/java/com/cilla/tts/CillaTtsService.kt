package com.cilla.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.system.Os
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The system TTS engine. TalkBack and e-book readers hand it Persian or English text, and each
 * stretch goes to the checkpoint that speaks that language: Cilla for Persian, Phonon for
 * English. The voices come from the app's settings, one selection per model.
 */
class CillaTtsService : TextToSpeechService() {

    private lateinit var engines: TtsEngines
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var language: String? = null

    override fun onCreate() {
        super.onCreate()
        Os.setenv("RAYON_NUM_THREADS", "2", true)
        engines = TtsEngines(applicationContext)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return when (languageCode(lang)) {
            CODE_PERSIAN -> if (engines.hasPersianModel()) {
                TextToSpeech.LANG_AVAILABLE
            } else {
                TextToSpeech.LANG_MISSING_DATA
            }
            CODE_ENGLISH -> if (engines.hasEnglishModel()) {
                TextToSpeech.LANG_AVAILABLE
            } else {
                TextToSpeech.LANG_MISSING_DATA
            }
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            language = languageCode(lang)
        }
        return result
    }

    override fun onGetLanguage(): Array<String> {
        val code = language ?: if (engines.hasPersianModel()) CODE_PERSIAN else CODE_ENGLISH
        val tag = if (code == CODE_PERSIAN) "fa" else "en"
        return arrayOf(Locale.forLanguageTag(tag).isO3Language, "", "")
    }

    override fun onStop() {
        stopRequested.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested.set(false)
        val text = request.charSequenceText?.toString()?.trim().orEmpty()

        if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        if (text.isEmpty()) {
            callback.done()
            return
        }

        val withEnglish = engines.hasEnglishModel()
        // The requested language decides nothing on its own: an e-book page mixes both scripts,
        // so the text is split and each piece goes to the checkpoint that can read it.
        val segments = TextCleaner.splitSegments(text, withEnglish)
        if (segments.isEmpty()) {
            callback.done()
            return
        }

        val temperature = TtsPrefs.getTemperature(this)
        val seed = TtsPrefs.resolveSeed(this)
        val maxBuffer = callback.maxBufferSize
        val sink: (FloatArray) -> Boolean = { pcm ->
            if (stopRequested.get()) {
                false
            } else if (sendPcm(callback, pcm, maxBuffer)) {
                !stopRequested.get()
            } else {
                false
            }
        }

        try {
            engines.speak(segments, temperature, seed, sink)
            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "synthesis failed", e)
            callback.error()
        }
    }

    /** Hands [pcm] to Android in pieces no larger than its buffer, as 24 kHz 16-bit mono. */
    private fun sendPcm(callback: SynthesisCallback, pcm: FloatArray, maxBuffer: Int): Boolean {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val sample = (pcm[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            bytes[2 * i] = sample.toByte()
            bytes[2 * i + 1] = (sample shr 8).toByte()
        }
        var offset = 0
        while (offset < bytes.size) {
            if (stopRequested.get()) return false
            val length = minOf(maxBuffer, bytes.size - offset)
            if (callback.audioAvailable(bytes, offset, length) != TextToSpeech.SUCCESS) return false
            offset += length
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRequested.set(true)
        if (::engines.isInitialized) engines.close()
    }

    /** Android asks in ISO 639 codes: `fas`/`fa`/`per` for Persian, `eng`/`en` for English. */
    private fun languageCode(lang: String?): String? = when (lang?.lowercase()) {
        "fas", "fa", "per", "pes" -> CODE_PERSIAN
        "eng", "en" -> CODE_ENGLISH
        else -> null
    }

    private companion object {
        const val TAG = "CillaTtsService"
        const val SAMPLE_RATE = 24000
        const val CODE_PERSIAN = "fa"
        const val CODE_ENGLISH = "en"
    }
}
