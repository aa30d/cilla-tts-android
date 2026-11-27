package com.cilla.tts

import android.content.Context
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Settings shared by [MainActivity] and [CillaTtsService], so the app and the system TTS engine
 * always speak with the same active Persian model, voices, temperature and seed.
 *
 * Each checkpoint (`cilla-mini`, `cilla-lite`, `phonon-31m-en`) keeps its own voice selection,
 * and `cilla-lite` keeps its own temperature (default `0.3` as trained) separate from the `0.7`
 * default of `cilla-mini` / `phonon`.
 */
object TtsPrefs {
    private const val PREFS_NAME = "cilla_tts_prefs"
    private const val KEY_VOICE_FA = "voice_fa"
    private const val KEY_VOICE_LITE = "voice_lite"
    private const val KEY_VOICE_EN = "voice_en"
    /** Before 1.1 there was one voice for both models; kept so an upgrade keeps the choice. */
    private const val KEY_VOICE_LEGACY = "voice"
    private const val KEY_ACTIVE_PERSIAN = "active_persian_model"
    private const val KEY_TEMP = "temperature"
    private const val KEY_TEMP_LITE = "temperature_lite"
    private const val KEY_RANDOM_SEED = "random_seed"
    private const val KEY_SEED = "seed"
    private const val KEY_HF_TOKEN = "hf_token"

    const val DEFAULT_TEMP_STANDARD = 0.7f
    const val DEFAULT_TEMP_LITE = 0.3f

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Which Persian model is active (`PERSIAN` = Cilla Mini, `PERSIAN_LITE` = Cilla Lite).
     * Automatically falls back to the other Persian model if the preferred one isn't installed.
     */
    fun getActivePersianKind(ctx: Context): ModelKind {
        val pref = when (prefs(ctx).getString(KEY_ACTIVE_PERSIAN, "mini")) {
            "lite" -> ModelKind.PERSIAN_LITE
            else -> ModelKind.PERSIAN
        }
        val other = if (pref == ModelKind.PERSIAN_LITE) ModelKind.PERSIAN else ModelKind.PERSIAN_LITE
        if (ModelStorage.isReady(ctx, pref)) return pref
        if (ModelStorage.isReady(ctx, other)) return other
        return pref
    }

    fun getPreferredPersianKind(ctx: Context): ModelKind =
        if (prefs(ctx).getString(KEY_ACTIVE_PERSIAN, "mini") == "lite") {
            ModelKind.PERSIAN_LITE
        } else {
            ModelKind.PERSIAN
        }

    fun setActivePersianKind(ctx: Context, kind: ModelKind) {
        val value = if (kind == ModelKind.PERSIAN_LITE) "lite" else "mini"
        prefs(ctx).edit().putString(KEY_ACTIVE_PERSIAN, value).apply()
    }

    fun getVoice(ctx: Context, kind: ModelKind): String? = when (kind) {
        ModelKind.PERSIAN -> getPersianVoice(ctx)
        ModelKind.PERSIAN_LITE -> prefs(ctx).getString(KEY_VOICE_LITE, null)
        ModelKind.ENGLISH -> getEnglishVoice(ctx)
    }

    fun setVoice(ctx: Context, kind: ModelKind, voice: String) {
        when (kind) {
            ModelKind.PERSIAN -> setPersianVoice(ctx, voice)
            ModelKind.PERSIAN_LITE -> prefs(ctx).edit().putString(KEY_VOICE_LITE, voice).apply()
            ModelKind.ENGLISH -> setEnglishVoice(ctx, voice)
        }
    }

    fun clearVoice(ctx: Context, kind: ModelKind) {
        val key = when (kind) {
            ModelKind.PERSIAN -> KEY_VOICE_FA
            ModelKind.PERSIAN_LITE -> KEY_VOICE_LITE
            ModelKind.ENGLISH -> KEY_VOICE_EN
        }
        prefs(ctx).edit().remove(key).apply()
    }

    fun getPersianVoice(ctx: Context): String? =
        prefs(ctx).getString(KEY_VOICE_FA, null) ?: prefs(ctx).getString(KEY_VOICE_LEGACY, null)

    fun setPersianVoice(ctx: Context, voice: String) =
        prefs(ctx).edit().putString(KEY_VOICE_FA, voice).apply()

    fun getEnglishVoice(ctx: Context): String? = prefs(ctx).getString(KEY_VOICE_EN, null)

    fun setEnglishVoice(ctx: Context, voice: String) =
        prefs(ctx).edit().putString(KEY_VOICE_EN, voice).apply()

    fun clearVoices(ctx: Context) = prefs(ctx).edit()
        .remove(KEY_VOICE_FA)
        .remove(KEY_VOICE_LITE)
        .remove(KEY_VOICE_EN)
        .remove(KEY_VOICE_LEGACY)
        .apply()

    fun getTemperature(ctx: Context, kind: ModelKind = getActivePersianKind(ctx)): Float =
        if (kind == ModelKind.PERSIAN_LITE) {
            prefs(ctx).getFloat(KEY_TEMP_LITE, DEFAULT_TEMP_LITE)
        } else {
            prefs(ctx).getFloat(KEY_TEMP, DEFAULT_TEMP_STANDARD)
        }

    fun setTemperature(ctx: Context, temp: Float, kind: ModelKind = getActivePersianKind(ctx)) {
        val key = if (kind == ModelKind.PERSIAN_LITE) KEY_TEMP_LITE else KEY_TEMP
        prefs(ctx).edit().putFloat(key, temp).apply()
    }

    fun isRandomSeed(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RANDOM_SEED, true)
    fun setRandomSeed(ctx: Context, random: Boolean) = prefs(ctx).edit().putBoolean(KEY_RANDOM_SEED, random).apply()

    fun getSeed(ctx: Context): Long = prefs(ctx).getLong(KEY_SEED, 42L)
    fun setSeed(ctx: Context, seed: Long) = prefs(ctx).edit().putLong(KEY_SEED, seed).apply()

    fun resolveSeed(ctx: Context): Long =
        if (isRandomSeed(ctx)) Random.nextLong(1, Long.MAX_VALUE) else getSeed(ctx)

    fun getHfToken(ctx: Context): String = prefs(ctx).getString(KEY_HF_TOKEN, "").orEmpty()
    fun setHfToken(ctx: Context, token: String) =
        prefs(ctx).edit().putString(KEY_HF_TOKEN, token.trim()).apply()
}

/** One stretch of text that a single checkpoint reads: Persian (Cilla Mini/Lite) or English (Phonon). */
data class TextSegment(val text: String, val isEnglish: Boolean)

/**
 * Prepares text for the Persian (Cilla Mini / Cilla Lite) and English (Phonon) checkpoints.
 *
 * Persian runs are normalized with [PersianFrontend.normalize] (full `normalize_fa.py` port,
 * including Persian number spelling, `% -> درصد`, diacritic/invisible cleanup, and ZWNJ rules).
 * Latin-script runs are split out and handed to Phonon (`lang = "en"`) whenever Phonon is installed.
 */
object TextCleaner {
    private const val ENGLISH_MAX_CHARS = 300

    private val englishAllowed = Regex("[^A-Za-z0-9.,!?'\"\\-:;()\\s]")

    /** A run of Latin script, with the spaces, digits and punctuation inside it. */
    private val latinRun = Regex("[A-Za-z]+(?:[\\s.,'’\\-!?:;]+[A-Za-z0-9]+)*")
    private val multiSpace = Regex("[\\s\\u200B]+")

    fun cleanPersian(text: String): String = PersianFrontend.normalize(text, spellNumbers = true)

    fun cleanEnglish(text: String): String = text
        .replace(englishAllowed, " ")
        .replace(multiSpace, " ")
        .trim()

    /**
     * [text] as the segments to speak, in order. With [splitEnglish] false everything is normalized
     * and read by the active Persian checkpoint. Persian segments stay un-chunked here so
     * [TtsEngines] can split them with exact model token counts (`8..80` tokens per chunk).
     */
    fun splitSegments(text: String, splitEnglish: Boolean): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        if (!splitEnglish) {
            val fa = cleanPersian(text)
            if (fa.isNotEmpty()) segments += TextSegment(fa, false)
            return segments
        }
        var last = 0
        for (match in latinRun.findAll(text)) {
            if (match.range.first > last) {
                val fa = cleanPersian(text.substring(last, match.range.first))
                if (fa.isNotEmpty()) segments += TextSegment(fa, false)
            }
            for (chunk in chunkEnglish(cleanEnglish(match.value), ENGLISH_MAX_CHARS)) {
                segments += TextSegment(chunk, true)
            }
            last = match.range.last + 1
        }
        if (last < text.length) {
            val fa = cleanPersian(text.substring(last))
            if (fa.isNotEmpty()) segments += TextSegment(fa, false)
        }
        return segments
    }

    private fun chunkEnglish(text: String, maxChars: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(trimmed)
        val pieces = mutableListOf<String>()
        val sentence = StringBuilder()
        var index = 0
        while (index < trimmed.length) {
            val ch = trimmed[index]
            sentence.append(ch)
            val endsSentence = ch in ".!?;:" || ch == '\n'
            if ((endsSentence && sentence.length >= maxChars / 3) || sentence.length >= maxChars) {
                val piece = sentence.toString().trim()
                if (piece.isNotEmpty()) pieces += piece
                sentence.setLength(0)
            }
            index++
        }
        val tail = sentence.toString().trim()
        if (tail.isNotEmpty()) pieces += tail
        return pieces
    }
}

object WavWriter {
    fun writePcmFloatAsWav(out: OutputStream, samples: FloatArray, sampleRate: Int = 24000) {
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1) // PCM format
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // byte rate
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
        out.write(header)

        val buf = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            buf[i * 2] = (s.toInt() and 0xFF).toByte()
            buf[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        out.write(buf)
    }
}
