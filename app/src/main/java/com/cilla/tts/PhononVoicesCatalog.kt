package com.cilla.tts

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class PhononVoiceEntry(
    val filename: String,
    val name: String,
    val voiceId: String,
    val language: String,
    val locale: String,
    val gender: String?,
    val age: String?,
    val description: String,
    val safeStem: String
) {
    val localFileName: String get() = "$safeStem.safetensors"
    val downloadUrl: String get() = "${PhononVoicesCatalog.BASE_RESOLVE_URL}/$filename"

    fun localeLabel(): String = when (locale.lowercase()) {
        "en-us" -> "آمریکایی (US)"
        "en-gb" -> "بریتانیایی (UK)"
        "characters" -> "شخصیت داستانی"
        else -> locale
    }

    fun genderLabel(): String? = when (gender?.lowercase()) {
        "female" -> "زن"
        "male" -> "مرد"
        else -> null
    }

    fun shortSubtitle(): String {
        val parts = mutableListOf(localeLabel())
        genderLabel()?.let { parts.add(it) }
        age?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }
}

/**
 * Fetches the official voice catalogue from `https://huggingface.co/gradium/phonon-voices`
 * and downloads selected `.safetensors` voice embeddings into the English Phonon model's
 * `voices/` folder.
 */
object PhononVoicesCatalog {

    const val INDEX_URL = "https://huggingface.co/gradium/phonon-voices/raw/main/voices.jsonl"
    const val BASE_RESOLVE_URL = "https://huggingface.co/gradium/phonon-voices/resolve/main"
    private const val USER_AGENT = "CillaTTS-Android/1.2"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000

    private val SAFE_NAME_INVALID = Regex("[^A-Za-z0-9_\\- ]")
    private val MULTI_SPACE = Regex("\\s+")

    @Volatile
    private var cachedEnglish: List<PhononVoiceEntry>? = null

    fun fetchEnglishVoices(forceRefresh: Boolean = false): List<PhononVoiceEntry> {
        if (!forceRefresh) {
            cachedEnglish?.let { return it }
        }
        val conn = openGet(INDEX_URL)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("خطا در دریافت فهرست صداهای Phonon (HTTP $code)")
            }
            val raw = ArrayList<PhononVoiceEntry>()
            val usedStems = HashSet<String>()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val obj = try { JSONObject(trimmed) } catch (_: Exception) { continue }
                    val lang = obj.optString("language", "")
                    if (lang != "en") continue
                    val filename = obj.optString("filename", "")
                    val name = obj.optString("name", "")
                    val voiceId = obj.optString("voice_id", "")
                    if (filename.isBlank() || name.isBlank()) continue
                    val locale = obj.optString("locale", "")
                    val gender = if (obj.isNull("gender")) null else obj.optString("gender", "").takeIf { it.isNotEmpty() }
                    val age = if (obj.isNull("age")) null else obj.optString("age", "").takeIf { it.isNotEmpty() }
                    val desc = obj.optString("description", "")

                    val baseStem = sanitizeStem(name, voiceId)
                    val uniqueStem = if (usedStems.add(baseStem.lowercase())) {
                        baseStem
                    } else {
                        val suffix = voiceId.take(4).ifEmpty { usedStems.size.toString() }
                        val alt = "${baseStem}_$suffix"
                        usedStems.add(alt.lowercase())
                        alt
                    }
                    raw.add(
                        PhononVoiceEntry(
                            filename = filename,
                            name = name,
                            voiceId = voiceId,
                            language = lang,
                            locale = locale,
                            gender = gender,
                            age = age,
                            description = desc,
                            safeStem = uniqueStem
                        )
                    )
                }
            }
            if (raw.isEmpty()) {
                throw IllegalStateException("فهرست صداهای انگلیسی خالی بود")
            }
            val localeOrder = mapOf("en-us" to 0, "en-gb" to 1, "characters" to 2)
            val sorted = raw.sortedWith(
                compareBy<PhononVoiceEntry> { localeOrder[it.locale.lowercase()] ?: 3 }
                    .thenBy { it.gender ?: "z" }
                    .thenBy { it.name.lowercase() }
            )
            cachedEnglish = sorted
            return sorted
        } finally {
            conn.disconnect()
        }
    }

    fun installedStems(context: Context): Set<String> {
        val dir = ModelStorage.voicesDir(context, ModelKind.ENGLISH)
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".safetensors") }
            ?.map { it.name.removeSuffix(".safetensors").lowercase() }
            ?.toSet()
            ?: emptySet()
    }

    fun downloadSelected(
        context: Context,
        entries: List<PhononVoiceEntry>,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (index: Int, total: Int, entry: PhononVoiceEntry) -> Unit
    ): Int {
        val voicesDir = ModelStorage.voicesDir(context, ModelKind.ENGLISH).apply { mkdirs() }
        var downloaded = 0
        for ((i, entry) in entries.withIndex()) {
            if (cancelled.get()) break
            onProgress(i + 1, entries.size, entry)
            val target = File(voicesDir, entry.localFileName)
            val tmp = File(voicesDir, "${entry.localFileName}.tmp")
            try {
                downloadToFile(entry.downloadUrl, tmp, cancelled)
                if (cancelled.get()) {
                    tmp.delete()
                    break
                }
                val info = VoiceCompat.inspect(tmp)
                if (info.error != null) {
                    tmp.delete()
                    throw IllegalStateException("فایل صدای ${entry.name} معتبر نیست: ${info.error}")
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                downloaded++
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }
        ModelStorage.prepare(context, ModelKind.ENGLISH)
        return downloaded
    }

    private fun sanitizeStem(name: String, fallbackId: String): String {
        val cleaned = SAFE_NAME_INVALID.replace(name, "")
            .trim()
            .replace(MULTI_SPACE, "_")
        return cleaned.ifEmpty { fallbackId.ifEmpty { "voice" } }
    }

    private fun downloadToFile(url: String, dest: File, cancelled: AtomicBoolean) {
        val conn = openGet(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("خطا در دانلود صدا (HTTP $code)")
            }
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        if (cancelled.get()) return
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openGet(urlStr: String): HttpURLConnection {
        var current = urlStr
        repeat(8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("پاسخ تغییر مسیر نامعتبر (HTTP $code)")
                current = URL(URL(current), loc).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("تعداد تغییر مسیرها بیش از حد بود")
    }
}
