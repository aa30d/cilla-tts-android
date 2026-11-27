package com.cilla.tts

import android.content.Context
import java.io.File
import kotlin.math.sqrt

/**
 * Manages the installed checkpoints (`cilla-mini`, `cilla-lite`, `phonon-31m-en`), loaded lazily
 * on demand and reused across utterances.
 *
 * At most one Persian checkpoint (`cilla-mini` or `cilla-lite`) is kept resident in memory at a
 * time so switching models never doubles native memory usage.
 */
class TtsEngines(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private var persianKindLoaded: ModelKind? = null
    private var persian: PhononTTS? = null
    private var english: PhononTTS? = null
    private var persianError: String? = null
    private var englishError: String? = null

    fun activePersianKind(): ModelKind = TtsPrefs.getActivePersianKind(appContext)

    fun hasPersianModel(kind: ModelKind = activePersianKind()): Boolean =
        ModelStorage.isReady(appContext, kind)

    fun hasAnyPersianModel(): Boolean =
        ModelStorage.isReady(appContext, ModelKind.PERSIAN) ||
            ModelStorage.isReady(appContext, ModelKind.PERSIAN_LITE)

    fun hasEnglishModel(): Boolean = ModelStorage.isReady(appContext, ModelKind.ENGLISH)

    fun voices(kind: ModelKind): List<String> =
        ModelStorage.voiceNames(ModelStorage.modelDir(appContext, kind))

    @Synchronized
    fun persianOrNull(kind: ModelKind = activePersianKind()): PhononTTS? {
        if (persian != null && persianKindLoaded == kind) return persian
        // Unload the other Persian model first to keep memory bounded.
        if (persian != null && persianKindLoaded != kind) {
            persian?.close()
            persian = null
            persianKindLoaded = null
            persianError = null
        }

        val dir = ModelStorage.modelDir(appContext, kind)
        if (!ModelStorage.isReady(dir)) {
            persianError = if (ModelStorage.hasCoreFiles(dir)) {
                "مدل ${kind.label} نصب است اما هیچ صدایی ندارد؛ یک صدا کلون یا اضافه کنید"
            } else {
                "مدل ${kind.label} نصب نیست"
            }
            return null
        }
        ModelStorage.prepare(dir)
        return try {
            PhononTTS(dir.absolutePath, LANGUAGE_PERSIAN).also {
                persian = it
                persianKindLoaded = kind
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
     * Registers a newly cloned voice on the already-loaded engine if possible (zero reload),
     * or closes the engine so the next call loads it cleanly.
     */
    @Synchronized
    fun registerVoiceFile(kind: ModelKind, voiceName: String, voiceFile: File) {
        val loaded = when (kind) {
            ModelKind.ENGLISH -> english
            else -> if (persianKindLoaded == kind) persian else null
        } ?: return
        try {
            if (!loaded.addVoiceFile(voiceName, voiceFile)) {
                close(kind)
            }
        } catch (_: Exception) {
            close(kind)
        }
    }

    /**
     * Speaks [segments] in order, handing each piece of audio to [sink]; returning false from the
     * sink stops early.
     *
     * When [exactPostProcess] is true (used for WAV export), each Persian sentence chunk is
     * buffered for exact `speak_fa.py` runaway recovery + RMS silence trimming + 150 ms pause.
     * When false (live streaming playback / TalkBack), audio streams immediately with online
     * leading-silence trimming and 150 ms inter-sentence pauses.
     */
    @Synchronized
    fun speak(
        segments: List<TextSegment>,
        temperature: Float,
        seed: Long,
        sink: (FloatArray) -> Boolean
    ): PhononTTS.Result? = speak(segments, temperature, seed, exactPostProcess = false, sink = sink)

    @Synchronized
    fun speak(
        segments: List<TextSegment>,
        temperature: Float,
        seed: Long,
        exactPostProcess: Boolean,
        sink: (FloatArray) -> Boolean
    ): PhononTTS.Result? {
        var last: PhononTTS.Result? = null
        val englishEngine = if (segments.any { it.isEnglish }) englishOrNull() else null
        val faKind = activePersianKind()
        val pauseSilence = FloatArray(PersianFrontend.PAUSE_SAMPLES)
        var emittedAny = false
        var stopped = false
        val guardedSink: (FloatArray) -> Boolean = { pcm ->
            if (stopped) {
                false
            } else {
                val keepGoing = sink(pcm)
                if (!keepGoing) stopped = true
                keepGoing
            }
        }

        for (segment in segments) {
            if (stopped) break
            val useEnglish = segment.isEnglish && englishEngine != null
            if (useEnglish) {
                val eng = englishEngine!!
                val engTemp = TtsPrefs.getTemperature(appContext, ModelKind.ENGLISH)
                configure(eng, ModelKind.ENGLISH, engTemp, seed)
                if (emittedAny && !guardedSink(pauseSilence)) break
                last = eng.speak(segment.text, guardedSink)
                emittedAny = true
            } else {
                val engine = persianOrNull(faKind)
                    ?: throw IllegalStateException(persianError ?: "مدل فارسی در دسترس نیست")
                configure(engine, faKind, temperature, seed)
                val countTokens: (String) -> Int = { s ->
                    engine.countTokens(s) ?: PersianFrontend.estimateTokens(s)
                }
                val chunks = PersianFrontend.splitSentences(
                    PersianFrontend.withEnd(segment.text),
                    countTokens,
                    maxTokens = PersianFrontend.MAX_TOKENS,
                    minTokens = PersianFrontend.MIN_TOKENS
                )
                val framesAfterEos = if (faKind == ModelKind.PERSIAN_LITE) 0 else 1

                for (chunk in chunks) {
                    if (stopped) break
                    if (emittedAny && !guardedSink(pauseSilence)) break
                    last = if (exactPostProcess) {
                        speakPersianBuffered(engine, chunk, framesAfterEos, seed, countTokens, guardedSink)
                    } else {
                        speakPersianStreaming(engine, chunk, framesAfterEos, guardedSink)
                    }
                    emittedAny = true
                }
            }
        }
        return last
    }

    /**
     * Live streaming path for Persian sentences: skips dead leading silence (up to ~1.5 s) while
     * keeping one lead-in frame (~80 ms) so onset consonants are never clipped and TTFA stays low.
     */
    private fun speakPersianStreaming(
        engine: PhononTTS,
        chunk: String,
        framesAfterEos: Int,
        sink: (FloatArray) -> Boolean
    ): PhononTTS.Result {
        var speechStarted = false
        var skippedSamples = 0
        var prevFrame: FloatArray? = null
        val maxLeadSkip = (PersianFrontend.SAMPLE_RATE * 1.5).toInt()
        val gateRms = 0.004

        val res = engine.speakRaw(chunk, framesAfterEos) { pcm ->
            if (speechStarted) {
                sink(pcm)
            } else {
                val rms = frameRms(pcm)
                if (rms >= gateRms || skippedSamples + pcm.size >= maxLeadSkip) {
                    speechStarted = true
                    val lead = prevFrame
                    prevFrame = null
                    if (lead != null && !sink(lead)) return@speakRaw false
                    sink(pcm)
                } else {
                    skippedSamples += pcm.size
                    prevFrame = pcm
                    true
                }
            }
        }
        if (!speechStarted && prevFrame != null) {
            sink(prevFrame!!)
        }
        return res
    }

    /**
     * Exact `speak_fa.py` chunk generation: buffers one sentence chunk, detects runaway no-EOS
     * generation (`> 0.97 * cap`), retries once with an alternate seed, then recursively halves
     * words up to `MAX_SPLIT_DEPTH = 2`, and applies RMS silence trimming (`keepMs = 120`).
     */
    private fun speakPersianBuffered(
        engine: PhononTTS,
        chunk: String,
        framesAfterEos: Int,
        baseSeed: Long,
        countTokens: (String) -> Int,
        sink: (FloatArray) -> Boolean,
        depth: Int = 0
    ): PhononTTS.Result {
        val tokens = countTokens(chunk).coerceAtLeast(1)

        fun runOnce(runSeed: Long): Pair<FloatArray, PhononTTS.Result> {
            engine.setSeed(runSeed)
            val pieces = ArrayList<FloatArray>()
            var total = 0
            val r = engine.speakRaw(chunk, framesAfterEos) { pcm ->
                pieces.add(pcm)
                total += pcm.size
                true
            }
            val merged = FloatArray(total)
            var off = 0
            for (p in pieces) {
                p.copyInto(merged, off)
                off += p.size
            }
            return merged to r
        }

        var (audio, result) = runOnce(baseSeed)
        if (PersianFrontend.isRunaway(audio.size, PersianFrontend.SAMPLE_RATE, tokens)) {
            // Retry once with a shifted seed before splitting words in half.
            val retry = runOnce(baseSeed xor 0x5A5A_1234L)
            audio = retry.first
            result = retry.second

            if (depth < PersianFrontend.MAX_SPLIT_DEPTH &&
                PersianFrontend.isRunaway(audio.size, PersianFrontend.SAMPLE_RATE, tokens)
            ) {
                val split = PersianFrontend.splitWordsInHalf(chunk)
                if (split != null) {
                    val seam = FloatArray(PersianFrontend.SEAM_SAMPLES)
                    speakPersianBuffered(
                        engine, split.first, framesAfterEos, baseSeed, countTokens, sink, depth + 1
                    )
                    if (!sink(seam)) return result
                    return speakPersianBuffered(
                        engine, split.second, framesAfterEos, baseSeed + 1, countTokens, sink, depth + 1
                    )
                }
            }
        }

        val trimmed = PersianFrontend.trimSilence(audio, PersianFrontend.SAMPLE_RATE, 120f)
        if (trimmed.isNotEmpty()) sink(trimmed)
        return result
    }

    private fun frameRms(pcm: FloatArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sumSq = 0.0
        for (s in pcm) sumSq += s.toDouble() * s.toDouble()
        return sqrt(sumSq / pcm.size.toDouble())
    }

    /** Applies the saved voice of [kind], plus temperature and seed. */
    private fun configure(engine: PhononTTS, kind: ModelKind, temperature: Float, seed: Long) {
        val available = try {
            engine.voices
        } catch (_: Exception) {
            emptyList()
        }
        val wanted = TtsPrefs.getVoice(appContext, kind)
        val voice = wanted?.takeIf { available.contains(it) } ?: available.firstOrNull()
        if (voice != null) {
            try {
                engine.setVoice(voice)
                TtsPrefs.setVoice(appContext, kind, voice)
            } catch (_: Exception) {
                // Keep going with the model's current default voice.
            }
        }
        engine.setLanguage(if (kind == ModelKind.ENGLISH) LANGUAGE_ENGLISH else LANGUAGE_PERSIAN)
        engine.setTemperature(temperature)
        engine.setSeed(seed)
    }

    /** Releases [kind]'s model so its files can be replaced or deleted. */
    @Synchronized
    fun close(kind: ModelKind) {
        when (kind) {
            ModelKind.ENGLISH -> {
                english?.close()
                english = null
                englishError = null
            }
            ModelKind.PERSIAN, ModelKind.PERSIAN_LITE -> {
                if (persianKindLoaded == kind) {
                    persian?.close()
                    persian = null
                    persianKindLoaded = null
                    persianError = null
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        persian?.close()
        persian = null
        persianKindLoaded = null
        english?.close()
        english = null
    }

    companion object {
        /** Persian models use our Kotlin PersianFrontend; disable xn-ptts's non-Persian normalizer. */
        const val LANGUAGE_PERSIAN = "none"
        const val LANGUAGE_ENGLISH = "en"
    }
}
