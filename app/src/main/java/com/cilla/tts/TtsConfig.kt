package com.cilla.tts

import android.content.Context
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Settings shared by [MainActivity] and [CillaTtsService], so the app and the system TTS engine
 * always speak with the same voices, temperature and seed.
 *
 * The two checkpoints have different voices, so each keeps its own selection: a Persian voice
 * name means nothing to Phonon and the other way round (`ptts_set_voice` refuses it).
 */
object TtsPrefs {
    private const val PREFS_NAME = "cilla_tts_prefs"
    private const val KEY_VOICE_FA = "voice_fa"
    private const val KEY_VOICE_EN = "voice_en"
    /** Before 1.1 there was one voice for both models; kept so an upgrade keeps the choice. */
    private const val KEY_VOICE_LEGACY = "voice"
    private const val KEY_TEMP = "temperature"
    private const val KEY_RANDOM_SEED = "random_seed"
    private const val KEY_SEED = "seed"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPersianVoice(ctx: Context): String? =
        prefs(ctx).getString(KEY_VOICE_FA, null) ?: prefs(ctx).getString(KEY_VOICE_LEGACY, null)

    fun setPersianVoice(ctx: Context, voice: String) =
        prefs(ctx).edit().putString(KEY_VOICE_FA, voice).apply()

    fun getEnglishVoice(ctx: Context): String? = prefs(ctx).getString(KEY_VOICE_EN, null)

    fun setEnglishVoice(ctx: Context, voice: String) =
        prefs(ctx).edit().putString(KEY_VOICE_EN, voice).apply()

    fun clearVoices(ctx: Context) = prefs(ctx).edit()
        .remove(KEY_VOICE_FA)
        .remove(KEY_VOICE_EN)
        .remove(KEY_VOICE_LEGACY)
        .apply()

    fun getTemperature(ctx: Context): Float = prefs(ctx).getFloat(KEY_TEMP, 0.7f)
    fun setTemperature(ctx: Context, temp: Float) = prefs(ctx).edit().putFloat(KEY_TEMP, temp).apply()

    fun isRandomSeed(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RANDOM_SEED, true)
    fun setRandomSeed(ctx: Context, random: Boolean) = prefs(ctx).edit().putBoolean(KEY_RANDOM_SEED, random).apply()

    fun getSeed(ctx: Context): Long = prefs(ctx).getLong(KEY_SEED, 42L)
    fun setSeed(ctx: Context, seed: Long) = prefs(ctx).edit().putLong(KEY_SEED, seed).apply()

    fun resolveSeed(ctx: Context): Long =
        if (isRandomSeed(ctx)) Random.nextLong(1, Long.MAX_VALUE) else getSeed(ctx)
}

/** One stretch of text that a single checkpoint reads: Persian (Cilla) or English (Phonon). */
data class TextSegment(val text: String, val isEnglish: Boolean)

/**
 * Prepares text for the two checkpoints.
 *
 * `cilla-mini` is Persian only and runs with `lang = "none"`, so nothing normalizes its input for
 * it: Persian text is cleaned here, and Latin-script runs are cut out and handed to Phonon, which
 * runs with `lang = "en"` and does its own number expansion. Sending English to Cilla is what
 * makes an English sentence come out as gibberish, so the split matters.
 */
object TextCleaner {
    private const val PERSIAN_MAX_CHARS = 200
    private const val ENGLISH_MAX_CHARS = 300

    /** Persian letters, ZWNJ, Persian and Latin digits, and sentence punctuation. */
    private val persianAllowed = Regex("[^\\u0621-\\u063A\\u0641-\\u064A\\u0660-\\u0669\\u067E\\u0686\\u0698\\u06A9\\u06AF\\u06CC\\u06F0-\\u06F9\\u200C0-9\\s.,!?؟،؛:()«»\\-]")
    private val englishAllowed = Regex("[^A-Za-z0-9.,!?'\"\\-:;()\\s]")

    /** A run of Latin script, with the spaces, digits and punctuation inside it. */
    private val latinRun = Regex("[A-Za-z]+(?:[\\s.,'’\\-!?:;]+[A-Za-z0-9]+)*")
    private val multiSpace = Regex("[\\s\\u200B]+")
    private val harakat = Regex("[\\u064B-\\u0652\\u0640]")

    private fun normalizePersianChars(text: String): String = text
        .replace('ي', 'ی')
        .replace('ى', 'ی')
        .replace('ك', 'ک')
        .replace('ۀ', 'ه')
        .replace(harakat, "")

    private fun toPersianDigits(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            builder.append(if (ch in '0'..'9') ('۰'.code + (ch - '0')).toChar() else ch)
        }
        return builder.toString()
    }

    fun cleanPersian(text: String): String = toPersianDigits(normalizePersianChars(text))
        .replace(persianAllowed, " ")
        .replace(multiSpace, " ")
        .trim()

    fun cleanEnglish(text: String): String = text
        .replace(englishAllowed, " ")
        .replace(multiSpace, " ")
        .trim()

    /**
     * [text] as the segments to speak, in order. With [splitEnglish] false everything is read by
     * Cilla, which is all that can be done while Phonon is not installed.
     */
    fun splitSegments(text: String, splitEnglish: Boolean): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        if (!splitEnglish) {
            for (chunk in chunk(cleanPersian(text), PERSIAN_MAX_CHARS)) {
                segments += TextSegment(chunk, false)
            }
            return segments
        }
        var last = 0
        for (match in latinRun.findAll(text)) {
            if (match.range.first > last) {
                for (chunk in chunk(cleanPersian(text.substring(last, match.range.first)), PERSIAN_MAX_CHARS)) {
                    segments += TextSegment(chunk, false)
                }
            }
            for (chunk in chunk(cleanEnglish(match.value), ENGLISH_MAX_CHARS)) {
                segments += TextSegment(chunk, true)
            }
            last = match.range.last + 1
        }
        if (last < text.length) {
            for (chunk in chunk(cleanPersian(text.substring(last)), PERSIAN_MAX_CHARS)) {
                segments += TextSegment(chunk, false)
            }
        }
        return segments
    }

    /**
     * [text] cut at sentence ends into pieces of at most [maxChars]: a checkpoint reads a long
     * paragraph less steadily than a sentence, and a stop request lands sooner.
     */
    private fun chunk(text: String, maxChars: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(trimmed)
        val pieces = mutableListOf<String>()
        val sentence = StringBuilder()
        var index = 0
        while (index < trimmed.length) {
            val ch = trimmed[index]
            sentence.append(ch)
            val endsSentence = ch in ".!?؟؛" || ch == '\n'
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
