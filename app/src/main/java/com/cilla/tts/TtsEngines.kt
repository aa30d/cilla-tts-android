package com.cilla.tts

import android.content.Context

/**
 * The two checkpoints, loaded on demand and reused: loading is the slow part (a Cilla checkpoint
 * takes seconds), and each model must only ever be handed its own voices.
 *
 * Cilla runs with `lang = "none"` because the runtime's text normalizer has no Persian and
 * refuses `"fa"`; Phonon runs with `lang = "en"` so numbers and dates are expanded for it. Phonon
 * is loaded only once some English text actually needs it, which halves the memory an idle app
 * holds.
 */
class TtsEngines(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private var persian: PhononTTS? = null
    private var english: PhononTTS? = null
    private var persianError: String? = null
    private var englishError: String? = null

    fun hasPersianModel(): Boolean = ModelStorage.isReady(ModelStorage.modelDir(appContext, ModelKind.PERSIAN))

    fun hasEnglishModel(): Boolean = ModelStorage.isReady(ModelStorage.modelDir(appContext, ModelKind.ENGLISH))

    fun voices(kind: ModelKind): List<String> = ModelStorage.voiceNames(ModelStorage.modelDir(appContext, kind))

    @Synchronized
    fun persianOrNull(): PhononTTS? {
        persian?.let { return it }
        val dir = ModelStorage.modelDir(appContext, ModelKind.PERSIAN)
        if (!ModelStorage.isReady(dir)) {
            persianError = "مدل فارسی نصب نیست"
            return null
        }
        // The loader takes every voice file in the folder at once, so a voice that belongs to the
        // other checkpoint has to be set aside first: see ModelStorage.prepare.
        ModelStorage.prepare(dir)
        return try {
            PhononTTS(dir.absolutePath, LANGUAGE_PERSIAN).also {
                persian = it
                persianError = null
            }
        } catch (e: Exception) {
            persianError = e.message
            null
        }
    }

    @Synchronized
    fun englishOrNull(): PhononTTS? {
        english?.let { return it }
        val dir = ModelStorage.modelDir(appContext, ModelKind.ENGLISH)
        if (!ModelStorage.isReady(dir)) {
            englishError = "مدل انگلیسی نصب نیست"
            return null
        }
        ModelStorage.prepare(dir)
        return try {
            PhononTTS(dir.absolutePath, LANGUAGE_ENGLISH).also {
                english = it
                englishError = null
            }
        } catch (e: Exception) {
            englishError = e.message
            null
        }
    }

    val persianFailure: String? get() = persianError
    val englishFailure: String? get() = englishError

    /**
     * Speaks [segments] in order, handing each piece of audio to [sink]; returning false from the
     * sink stops there. Returns the timings of the last utterance, or null when nothing was said.
     */
    @Synchronized
    fun speak(
        segments: List<TextSegment>,
        temperature: Float,
        seed: Long,
        sink: (FloatArray) -> Boolean
    ): PhononTTS.Result? {
        var last: PhononTTS.Result? = null
        val englishEngine = if (segments.any { it.isEnglish }) englishOrNull() else null
        for (segment in segments) {
            val useEnglish = segment.isEnglish && englishEngine != null
            val engine = if (useEnglish) {
                englishEngine!!
            } else {
                persianOrNull() ?: throw IllegalStateException(persianError ?: "مدل فارسی در دسترس نیست")
            }
            configure(engine, useEnglish, temperature, seed)
            last = engine.speak(segment.text, sink)
        }
        return last
    }

    /** Applies the saved voice of the model behind [engine], plus the shared temperature and seed. */
    private fun configure(engine: PhononTTS, english: Boolean, temperature: Float, seed: Long) {
        val available = try {
            engine.voices
        } catch (_: Exception) {
            emptyList()
        }
        val wanted = if (english) {
            TtsPrefs.getEnglishVoice(appContext)
        } else {
            TtsPrefs.getPersianVoice(appContext)
        }
        val voice = wanted?.takeIf { available.contains(it) } ?: available.firstOrNull()
        if (voice != null) {
            try {
                engine.setVoice(voice)
                if (english) TtsPrefs.setEnglishVoice(appContext, voice) else TtsPrefs.setPersianVoice(appContext, voice)
            } catch (_: Exception) {
                // A voice name the engine does not know is not worth failing the utterance for.
            }
        }
        engine.setLanguage(if (english) LANGUAGE_ENGLISH else LANGUAGE_PERSIAN)
        engine.setTemperature(temperature)
        engine.setSeed(seed)
    }

    /** Releases [kind]'s model, so its files can be replaced or deleted. */
    @Synchronized
    fun close(kind: ModelKind) {
        when (kind) {
            ModelKind.PERSIAN -> {
                persian?.close()
                persian = null
                persianError = null
            }
            ModelKind.ENGLISH -> {
                english?.close()
                english = null
                englishError = null
            }
        }
    }

    @Synchronized
    override fun close() {
        persian?.close()
        persian = null
        english?.close()
        english = null
    }

    companion object {
        /** Cilla is Persian only, and the runtime has no Persian normalizer: read it as written. */
        const val LANGUAGE_PERSIAN = "none"
        const val LANGUAGE_ENGLISH = "en"
    }
}
