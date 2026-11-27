// Phonon text to speech for Android: a Kotlin wrapper over libptts_ffi.so (see README.md), called
// through JNA.
package com.cilla.tts

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.File

/**
 * A loaded model. Loading is the slow part, so keep one around and reuse it, and [close] it when
 * done: nothing else frees the model.
 *
 * Every call blocks, so make them off the main thread, and from one thread at a time.
 *
 * @param modelDir a checkpoint folder: `tokenizer.json`, the weights and the voices.
 * @param lang how to normalize text: `en`, `fr`, `de`, `es`, `pt`, or `none` to read it as written.
 */
class PhononTTS(modelDir: String, lang: String) : AutoCloseable {
    private var handle: Pointer? =
        lib.ptts_new(modelDir, 0, lang) ?: throw IllegalStateException(lib.ptts_last_error(null))

    private var onAudio: (FloatArray) -> Boolean = { true }

    // What onAudio threw. JNA would log it and hand native code `false`, which reads as "stop", so
    // speak() would return as if nothing went wrong: keep it, stop, and rethrow it from speak().
    private var thrown: Throwable? = null

    // A field rather than a local, so the callback outlives every call that hands it to native code.
    private val onFrame = Lib.FrameFn { pcm, n, _ ->
        try {
            onAudio(pcm.getFloatArray(0, n.toInt()))
        } catch (t: Throwable) {
            thrown = t
            false
        }
    }

    /** The voices this checkpoint ships, sorted. */
    val voices: List<String>
        get() = names(lib.ptts_voices(open()))

    /** The languages the model was trained for, as its config lists them. */
    val languages: List<String>
        get() = names(lib.ptts_languages(open()))

    /** True if the checkpoint includes a speaker encoder and the native library supports cloning. */
    val supportsVoiceCloning: Boolean
        get() = hasVoiceCloningFfi && extLib.ptts_supports_voice_cloning(open()).toInt() != 0

    private fun names(block: Pointer): List<String> {
        val names = mutableListOf<String>()
        var offset = 0L
        while (true) {
            val name = block.getString(offset, "UTF-8")
            if (name.isEmpty()) return names
            names += name
            offset += name.toByteArray(Charsets.UTF_8).size + 1
        }
    }

    /** Speak in [name], one of [voices], from now on. */
    fun setVoice(name: String) = check(lib.ptts_set_voice(open(), name))

    /** Normalize text as [lang] (a code [load] takes, or none) from now on, with no reload. */
    fun setLanguage(lang: String) = check(lib.ptts_set_language(open(), lang))

    /**
     * Sample at [temperature] from now on: higher varies more, 0 removes the randomness. The
     * default is the model's. Throws for a negative or infinite temperature.
     */
    fun setTemperature(temperature: Float) = check(lib.ptts_set_temperature(open(), temperature))

    /**
     * Seed the sampling from now on, until set again: the same seed gives the same audio, a new
     * one another take.
     */
    fun setSeed(seed: Long) = lib.ptts_set_seed(open(), seed)

    /**
     * Register a newly created `.safetensors` voice on the live handle without reloading the model.
     */
    fun addVoiceFile(name: String, file: File): Boolean {
        if (!hasAddVoiceFfi) return false
        check(extLib.ptts_add_voice_file(open(), name, file.absolutePath))
        return true
    }

    /**
     * Token count of [text] using the checkpoint's loaded Hugging Face tokenizer, or null if the
     * loaded native library predates `ptts_count_tokens`.
     */
    fun countTokens(text: String): Int? {
        if (!hasCountTokensFfi) return null
        val n = extLib.ptts_count_tokens(open(), text)
        return if (n >= 0) n else null
    }

    /**
     * Speak [text], handing [onAudio] each piece of audio as soon as it is made: 24 kHz mono
     * floats in [-1, 1]. Return false from [onAudio] to stop early. Returns once all the audio
     * has been handed over, with the utterance's timings. What [onAudio] throws, [speak] rethrows.
     * [onAudio] must not call back into this object.
     */
    fun speak(text: String, onAudio: (FloatArray) -> Boolean): Result {
        this.onAudio = onAudio
        thrown = null
        val out = Result()
        val ok = lib.ptts_speak(open(), text, onFrame, null, out)
        thrown?.let {
            thrown = null
            throw it
        }
        check(ok)
        return out
    }

    /**
     * Speak a single pre-normalized chunk without `prepare_text_prompt` (no forced punctuation or
     * re-chunking) and with explicit [framesAfterEos] (e.g. `0` for Cilla Lite). Falls back to
     * [speak] if an older `libptts_ffi.so` is loaded.
     */
    fun speakRaw(text: String, framesAfterEos: Int, onAudio: (FloatArray) -> Boolean): Result {
        if (!hasRawSpeakFfi) {
            return speak(text, onAudio)
        }
        this.onAudio = onAudio
        thrown = null
        val out = Result()
        val ok = extLib.ptts_speak_raw(open(), text, framesAfterEos, onFrame, null, out)
        thrown?.let {
            thrown = null
            throw it
        }
        check(ok)
        return out
    }

    override fun close() {
        handle?.let { lib.ptts_free(it) }
        handle = null
    }

    private fun open() = handle ?: throw IllegalStateException("PhononTTS is closed")

    private fun check(ok: Byte) {
        if (ok.toInt() == 0) throw IllegalStateException(lib.ptts_last_error(handle))
    }

    /** One utterance's timings: `PttsResult` in ptts.h, whose `size_t` is a `Long` on 64 bits. */
    @Structure.FieldOrder("frames", "samples", "ttfaMs", "totalMs")
    class Result : Structure() {
        @JvmField var frames: Int = 0
        @JvmField var samples: Long = 0
        @JvmField var ttfaMs: Double = 0.0
        @JvmField var totalMs: Double = 0.0
    }

    /** ptts.h. A C `bool` return is one byte, which JNA's `Boolean` (four) would misread. */
    @Suppress("FunctionName")
    private interface Lib : Library {
        fun ptts_new(dir: String, unit: Int, lang: String): Pointer?
        fun ptts_speak(h: Pointer, text: String, cb: FrameFn, user: Pointer?, out: Result): Byte
        fun ptts_voices(h: Pointer): Pointer
        fun ptts_set_voice(h: Pointer, name: String): Byte
        fun ptts_set_language(h: Pointer, lang: String): Byte
        fun ptts_languages(h: Pointer): Pointer
        fun ptts_set_temperature(h: Pointer, temperature: Float): Byte
        fun ptts_set_seed(h: Pointer, seed: Long)
        fun ptts_last_error(h: Pointer?): String?
        fun ptts_free(h: Pointer)

        /** `PttsFrameFn`. `n` is a `size_t`, 64 bits on both ABIs `build.sh` makes. */
        fun interface FrameFn : Callback {
            fun invoke(pcm: Pointer, n: Long, user: Pointer?): Boolean
        }
    }

    /** Extended FFI functions added by `native/xn-ptts.patch` for Cilla Lite and voice cloning. */
    @Suppress("FunctionName")
    private interface ExtLib : Library {
        fun ptts_speak_raw(
            h: Pointer,
            text: String,
            framesAfterEos: Int,
            cb: Lib.FrameFn,
            user: Pointer?,
            out: Result
        ): Byte

        fun ptts_count_tokens(h: Pointer, text: String): Int
        fun ptts_supports_voice_cloning(h: Pointer): Byte
        fun ptts_add_voice_file(h: Pointer, name: String, path: String): Byte
        fun ptts_clone_voice_to_file(
            modelDir: String,
            pcm: FloatArray,
            nSamples: Long,
            sampleRate: Int,
            outputPath: String,
            normalizeLoudness: Byte
        ): Byte
    }

    companion object {
        private val lib: Lib = Native.load("ptts_ffi", Lib::class.java)
        private val extLib: ExtLib by lazy { Native.load("ptts_ffi", ExtLib::class.java) }

        private fun hasSymbol(name: String): Boolean = runCatching {
            NativeLibrary.getInstance("ptts_ffi").getFunction(name) != null
        }.getOrDefault(false)

        val hasRawSpeakFfi: Boolean by lazy { hasSymbol("ptts_speak_raw") }
        val hasCountTokensFfi: Boolean by lazy { hasSymbol("ptts_count_tokens") }
        val hasAddVoiceFfi: Boolean by lazy { hasSymbol("ptts_add_voice_file") }
        val hasVoiceCloningFfi: Boolean by lazy { hasSymbol("ptts_clone_voice_to_file") }

        /**
         * Encode 24 kHz mono float PCM with [modelDir]'s speaker encoder and write an `"emb"`
         * `.safetensors` voice file to [outputFile]. Works without keeping the full TTS model
         * loaded in memory.
         */
        fun cloneVoiceToFile(
            modelDir: File,
            pcm: FloatArray,
            sampleRate: Int = AudioDecoder.TARGET_SAMPLE_RATE,
            outputFile: File,
            normalizeLoudness: Boolean = false
        ) {
            if (!hasVoiceCloningFfi) {
                throw IllegalStateException(
                    "نسخه کتابخانه بومی libptts_ffi.so در این APK از کلون صدا پشتیبانی نمی‌کند. " +
                        "لطفاً نسخه ساخته‌شده با native/build-ptts-ffi.sh را نصب کنید."
                )
            }
            val ok = extLib.ptts_clone_voice_to_file(
                modelDir.absolutePath,
                pcm,
                pcm.size.toLong(),
                sampleRate,
                outputFile.absolutePath,
                if (normalizeLoudness) 1.toByte() else 0.toByte()
            )
            if (ok.toInt() == 0) {
                throw IllegalStateException(
                    lib.ptts_last_error(null) ?: "خطا در استخراج بردار صدا"
                )
            }
        }
    }
}
