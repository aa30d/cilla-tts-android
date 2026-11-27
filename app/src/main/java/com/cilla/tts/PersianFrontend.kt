package com.cilla.tts

import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Persian text normalization, sentence chunking, runaway-recovery helpers, and silence trimming,
 * faithfully ported from `mehdi-hf/cilla-lite`'s `normalize_fa.py` and `speak_fa.py`.
 */
object PersianFrontend {

    const val SAMPLE_RATE = 24000
    const val FRAME_RATE = 12.5
    const val MAX_TOKENS = 80
    const val MIN_TOKENS = 8
    const val MAX_SPLIT_DEPTH = 2
    const val PAUSE_SAMPLES = (SAMPLE_RATE * 0.15).toInt()
    const val SEAM_SAMPLES = (SAMPLE_RATE * 0.05).toInt()

    private const val ZWNJ = '\u200C'
    private const val PERSIAN_LETTERS = "آابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیئ"
    private const val KEPT_PUNCT = ".،؛؟!:"

    private val PERSIAN_LETTER_SET = PERSIAN_LETTERS.toHashSet()
    private val KEPT_PUNCT_SET = KEPT_PUNCT.toHashSet()

    private val INVISIBLES = setOf(
        '\u200B', '\u200D', '\u200E', '\u200F',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
        '\uFEFF', '\u00AD'
    )

    private val DIACRITICS = Regex("[\u064B-\u0655\u0670\u06D6-\u06ED\u0656-\u065F]")
    private val THOUSANDS_COMMA = Regex("(?<=\\d),(?=\\d{3}(?!\\d))")
    private val NUMBER_PATTERN = Regex("\\d+(?:\\.\\d+)?")
    private val PERCENT_PATTERN = Regex("[٪%]")
    private val MULTI_ZWNJ = Regex("\u200C+")
    private val SPACE_BEFORE_PUNCT = Regex("\\s+([.،؛؟!:])")
    private val REPEATED_PUNCT = Regex("([.،؛؟!:])\\1+")
    private val MULTI_SPACE = Regex("\\s+")

    private val HARD_BREAK = Regex("(?<=[.!؟])\\s+")
    private val SOFT_BREAK = Regex("(?<=[،؛:])\\s+")

    private val ONES = arrayOf(
        "", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه",
        "ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده"
    )
    private val TENS = arrayOf(
        "", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود"
    )
    private val HUNDREDS = arrayOf(
        "", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد"
    )
    private val SCALES = arrayOf("", "هزار", "میلیون", "میلیارد", "تریلیون")
    private val DIGIT_WORDS = arrayOf(
        "صفر", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه"
    )
    private val FRAC_UNITS = arrayOf("دهم", "صدم", "هزارم")

    /**
     * Full normalization pipeline matching `normalize_fa.py::normalize`.
     */
    fun normalize(raw: String, spellNumbers: Boolean = true): String {
        if (raw.isBlank()) return ""
        var text = Normalizer.normalize(raw, Normalizer.Form.NFC)

        // Strip invisible control marks (keeping ZWNJ U+200C).
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch !in INVISIBLES) sb.append(ch)
        }
        text = DIACRITICS.replace(sb.toString(), "")

        // Map Arabic variants, digits, and separators.
        val mapped = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                'ي', 'ى', 'ۍ', 'ې' -> mapped.append('ی')
                'ك', 'ڪ' -> mapped.append('ک')
                'ة', 'ۀ', 'ۂ', 'ۃ' -> mapped.append('ه')
                'أ', 'إ', 'ٱ', 'ٲ', 'ٳ' -> mapped.append('ا')
                'ؤ' -> mapped.append('و')
                'ء', 'ـ' -> { /* strip */ }
                in '\u06F0'..'\u06F9' -> mapped.append(('0'.code + (ch.code - 0x06F0)).toChar())
                in '\u0660'..'\u0669' -> mapped.append(('0'.code + (ch.code - 0x0660)).toChar())
                '٬' -> { /* strip Persian thousands separator */ }
                '٫' -> mapped.append('.')
                else -> mapped.append(ch)
            }
        }
        text = mapped.toString()

        // Percent signs -> " درصد "
        text = PERCENT_PATTERN.replace(text, " درصد ")

        // Numbers -> Persian words
        if (spellNumbers) {
            text = numbersToWords(text)
        }

        // Punctuation mapping & filtering to ALLOWED alphabet.
        val filtered = StringBuilder(text.length)
        for (ch in text) {
            val out = when (ch) {
                ',' -> '،'
                '?' -> '؟'
                ';' -> '؛'
                '…' -> '.'
                else -> ch
            }
            if (out in PERSIAN_LETTER_SET || out in KEPT_PUNCT_SET || out == ZWNJ || out == ' ') {
                filtered.append(out)
            } else {
                filtered.append(' ')
            }
        }

        // Collapse ZWNJ and keep it only strictly between two Persian letters.
        val collapsedZwnj = MULTI_ZWNJ.replace(filtered.toString(), ZWNJ.toString())
        val zwnjClean = StringBuilder(collapsedZwnj.length)
        for (i in collapsedZwnj.indices) {
            val ch = collapsedZwnj[i]
            if (ch == ZWNJ) {
                val prevOk = i > 0 && collapsedZwnj[i - 1] in PERSIAN_LETTER_SET
                val nextOk = i + 1 < collapsedZwnj.length && collapsedZwnj[i + 1] in PERSIAN_LETTER_SET
                if (prevOk && nextOk) zwnjClean.append(ZWNJ)
            } else {
                zwnjClean.append(ch)
            }
        }

        text = SPACE_BEFORE_PUNCT.replace(zwnjClean.toString(), "$1")
        text = REPEATED_PUNCT.replace(text, "$1")
        text = MULTI_SPACE.replace(text, " ").trim()
        text = text.trimStart { it == ' ' || it in KEPT_PUNCT_SET }.trim()
        return text
    }

    fun numbersToWords(input: String): String {
        val noThousands = THOUSANDS_COMMA.replace(input, "")
        return NUMBER_PATTERN.replace(noThousands) { m ->
            val token = m.value
            val dot = token.indexOf('.')
            val intPart = if (dot >= 0) token.substring(0, dot) else token
            val fracPart = if (dot >= 0) token.substring(dot + 1) else null

            val intWords = if (intPart.length > 12 || (intPart.length > 1 && intPart[0] == '0')) {
                digitsToWords(intPart)
            } else {
                intToWords(intPart.toLongOrNull() ?: 0L)
            }

            val spoken = if (fracPart != null && fracPart.isNotEmpty()) {
                val fracSpoken = if (fracPart.length <= 3) {
                    val v = fracPart.toLongOrNull() ?: 0L
                    "${intToWords(v)} ${FRAC_UNITS[fracPart.length - 1]}"
                } else {
                    digitsToWords(fracPart)
                }
                "$intWords ممیز $fracSpoken"
            } else {
                intWords
            }
            " $spoken "
        }
    }

    private fun digitsToWords(digits: String): String =
        digits.mapNotNull { ch ->
            val d = ch - '0'
            if (d in 0..9) DIGIT_WORDS[d] else null
        }.joinToString(" ")

    private fun groupToWords(n: Int): String {
        val parts = ArrayList<String>(3)
        val h = n / 100
        val rem = n % 100
        if (h > 0) parts.add(HUNDREDS[h])
        if (rem >= 20) {
            parts.add(TENS[rem / 10])
            if (rem % 10 > 0) parts.add(ONES[rem % 10])
        } else if (rem > 0) {
            parts.add(ONES[rem])
        }
        return parts.joinToString(" و ")
    }

    fun intToWords(value: Long): String {
        if (value == 0L) return "صفر"
        var n = value
        val groups = ArrayList<Int>(5)
        while (n > 0L) {
            groups.add((n % 1000L).toInt())
            n /= 1000L
        }
        val parts = ArrayList<String>(groups.size)
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            val scale = SCALES.getOrElse(i) { "" }
            val text = when {
                g == 1 && scale == "هزار" -> "هزار"
                scale.isNotEmpty() -> "${groupToWords(g)} $scale"
                else -> groupToWords(g)
            }
            parts.add(text)
        }
        return parts.joinToString(" و ")
    }

    /** Ensure sentence-terminal punctuation so the model hears a clean sentence ending. */
    fun withEnd(text: String): String {
        val s = text.trim()
        if (s.isEmpty()) return ""
        return if (s.last() in ".!؟") s else "$s."
    }

    /**
     * Split normalized Persian text into sentence chunks sized between [minTokens] and
     * [maxTokens] (defaults `8..80` matching `speak_fa.py`).
     */
    fun splitSentences(
        text: String,
        countTokens: (String) -> Int,
        maxTokens: Int = MAX_TOKENS,
        minTokens: Int = MIN_TOKENS
    ): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rawSentences = HARD_BREAK.split(trimmed)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val pieces = ArrayList<String>()
        for (sentence in rawSentences) {
            if (countTokens(sentence) <= maxTokens) {
                pieces.add(sentence)
            } else {
                pieces.addAll(splitOversized(sentence, countTokens, maxTokens))
            }
        }

        // Merge tiny trailing/leading chunks (< minTokens) with a neighbour when possible.
        if (pieces.size <= 1) return pieces
        val merged = ArrayList<String>(pieces.size)
        var i = 0
        while (i < pieces.size) {
            val cur = pieces[i]
            val curToks = countTokens(cur)
            if (curToks < minTokens) {
                if (merged.isNotEmpty() && countTokens("${merged.last()} $cur") <= maxTokens) {
                    merged[merged.lastIndex] = "${merged.last()} $cur"
                    i++
                    continue
                }
                if (i + 1 < pieces.size && countTokens("$cur ${pieces[i + 1]}") <= maxTokens) {
                    merged.add("$cur ${pieces[i + 1]}")
                    i += 2
                    continue
                }
            }
            merged.add(cur)
            i++
        }
        return merged
    }

    private fun splitOversized(
        sentence: String,
        countTokens: (String) -> Int,
        maxTokens: Int
    ): List<String> {
        val clauses = SOFT_BREAK.split(sentence)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (clauses.size > 1) {
            val packed = packUnits(clauses, countTokens, maxTokens)
            val result = ArrayList<String>()
            for (chunk in packed) {
                if (countTokens(chunk) <= maxTokens) {
                    result.add(chunk)
                } else {
                    result.addAll(packWordsBalanced(chunk, countTokens, maxTokens))
                }
            }
            return result
        }
        return packWordsBalanced(sentence, countTokens, maxTokens)
    }

    private fun packUnits(
        units: List<String>,
        countTokens: (String) -> Int,
        maxTokens: Int
    ): List<String> {
        val out = ArrayList<String>()
        var current = ""
        for (u in units) {
            val candidate = if (current.isEmpty()) u else "$current $u"
            if (current.isNotEmpty() && countTokens(candidate) > maxTokens) {
                out.add(current)
                current = u
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun packWordsBalanced(
        text: String,
        countTokens: (String) -> Int,
        maxTokens: Int
    ): List<String> {
        val words = text.split(MULTI_SPACE).filter { it.isNotEmpty() }
        if (words.size <= 1) return listOf(text)
        val total = countTokens(text).coerceAtLeast(1)
        val numChunks = ceil(total.toDouble() / maxTokens.toDouble()).toInt().coerceAtLeast(2)
        val target = ceil(total.toDouble() / numChunks.toDouble()).toInt().coerceAtMost(maxTokens)
        return packUnits(words, countTokens, target)
    }

    /**
     * Approximate token counter used as a fallback if native `ptts_count_tokens` isn't available.
     * For Persian BPE-4005, average piece length is ~3.5 chars including spaces.
     */
    fun estimateTokens(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        val words = trimmed.split(MULTI_SPACE).size
        val byChars = ceil(trimmed.length / 3.4).toInt()
        return max(words, byChars)
    }

    /** Matches `speak_fa.py` runaway cap: `ceil((tokens / 3 + 2) * frame_rate) / frame_rate`. */
    fun capSeconds(tokenCount: Int, frameRate: Double = FRAME_RATE): Double {
        val frames = ceil((tokenCount.toDouble() / 3.0 + 2.0) * frameRate)
        return frames / frameRate
    }

    fun isRunaway(sampleCount: Int, sampleRate: Int, tokenCount: Int): Boolean {
        val secs = sampleCount.toDouble() / sampleRate.toDouble()
        return secs > 0.97 * capSeconds(tokenCount)
    }

    fun splitWordsInHalf(text: String): Pair<String, String>? {
        val words = text.trim().split(MULTI_SPACE).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val mid = words.size / 2
        return words.subList(0, mid).joinToString(" ") to words.subList(mid, words.size).joinToString(" ")
    }

    /**
     * Exact port of `speak_fa.py::trim_silence`: 20 ms RMS windows, threshold `0.04 * global_rms`,
     * keeping [keepMs] margin on both sides.
     */
    fun trimSilence(
        audio: FloatArray,
        sampleRate: Int = SAMPLE_RATE,
        keepMs: Float = 120f
    ): FloatArray {
        if (audio.isEmpty()) return audio
        val win = max(1, sampleRate / 50) // 20 ms
        if (audio.size <= win * 2) return audio

        var sumSq = 0.0
        for (s in audio) sumSq += s.toDouble() * s.toDouble()
        val globalRms = sqrt(sumSq / audio.size.toDouble())
        if (globalRms < 1e-6) return audio

        val threshold = 0.04 * globalRms
        val numWindows = audio.size / win
        var first = -1
        var last = -1
        for (w in 0 until numWindows) {
            val offset = w * win
            var wSq = 0.0
            for (i in offset until offset + win) {
                val s = audio[i].toDouble()
                wSq += s * s
            }
            val wRms = sqrt(wSq / win.toDouble())
            if (wRms > threshold) {
                if (first < 0) first = w
                last = w
            }
        }
        if (first < 0 || last < 0) return audio

        val keep = (sampleRate * keepMs / 1000f).toInt()
        val start = max(0, first * win - keep)
        val end = min(audio.size, (last + 1) * win + keep)
        if (start == 0 && end == audio.size) return audio
        return audio.copyOfRange(start, end)
    }
}
