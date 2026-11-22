package com.cilla.tts

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the two checkpoints into the app's files directory. Every call blocks: run it on a
 * background thread.
 *
 * Nothing is bundled in the APK (Cilla alone is 112 MB), so the first run downloads. Downloads
 * land in `<file>.part` and are renamed when complete, so an interrupted one never leaves a file
 * that looks usable; a file already in place is kept, which makes a retry cheap.
 */
object ModelDownloader {

    private const val PERSIAN_BASE =
        "https://huggingface.co/spaces/mehdi-hf/cilla-mini-demo/resolve/main/model"
    private const val PERSIAN_VOICES_API =
        "https://huggingface.co/api/spaces/mehdi-hf/cilla-mini-demo/tree/main/model/voices"

    private const val ENGLISH_BASE = "https://huggingface.co/gradium/phonon-31m-en/resolve/main"

    /** The voices the Cilla demo publishes, used when its file listing cannot be read. */
    private val PERSIAN_VOICE_FALLBACK = listOf("parisa", "voice1", "voice2")

    class Progress(
        val onMessage: (String) -> Unit,
        val onFraction: (Int?) -> Unit
    )

    fun installPersian(ctx: Context, progress: Progress): VoiceReport {
        val dir = ModelStorage.modelDir(ctx, ModelKind.PERSIAN)
        val voices = ModelStorage.voicesDir(ctx, ModelKind.PERSIAN)
        dir.mkdirs()
        voices.mkdirs()

        for (name in listOf("config.json", "tokenizer.json", "model.safetensors")) {
            progress.onMessage("در حال دانلود $name (مدل فارسی)...")
            downloadFile("$PERSIAN_BASE/$name", File(dir, name), progress.onFraction)
        }

        val names = persianVoiceNames(progress)
        for (name in names) {
            progress.onMessage("در حال دانلود گوینده $name...")
            downloadFile("$PERSIAN_BASE/voices/$name.safetensors", File(voices, "$name.safetensors"), progress.onFraction)
        }

        val report = ModelStorage.prepare(dir)
        if (!ModelStorage.isReady(dir)) {
            throw IllegalStateException("مدل فارسی کامل نیست؛ دوباره تلاش کنید")
        }
        return report
    }

    fun installEnglish(ctx: Context, progress: Progress): VoiceReport {
        val dir = ModelStorage.modelDir(ctx, ModelKind.ENGLISH)
        dir.mkdirs()
        for (name in listOf("config.json", "tokenizer.json", "model.safetensors")) {
            progress.onMessage("در حال دانلود $name (مدل انگلیسی)...")
            downloadFile("$ENGLISH_BASE/$name", File(dir, name), progress.onFraction)
        }
        progress.onMessage("در حال دانلود صدای پیش‌فرض انگلیسی...")
        downloadFile("$ENGLISH_BASE/default-voice.safetensors", File(dir, "default-voice.safetensors"), progress.onFraction)

        val report = ModelStorage.prepare(dir)
        if (!ModelStorage.isReady(dir)) {
            throw IllegalStateException("مدل انگلیسی کامل نیست؛ دوباره تلاش کنید")
        }
        return report
    }

    private fun persianVoiceNames(progress: Progress): List<String> {
        return try {
            val conn = (URL(PERSIAN_VOICES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            val items = JSONArray(body)
            val names = mutableListOf<String>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("type") != "file") continue
                val path = item.optString("path")
                if (!path.endsWith(".safetensors")) continue
                names += path.substringAfterLast("/").removeSuffix(".safetensors")
            }
            if (names.isEmpty()) PERSIAN_VOICE_FALLBACK else names
        } catch (e: Exception) {
            progress.onMessage("فهرست گوینده‌ها دریافت نشد؛ از فهرست پیش‌فرض استفاده می‌شود")
            PERSIAN_VOICE_FALLBACK
        }
    }

    /**
     * Downloads [url] to [dest] unless it is already there and non-empty. Two attempts: Hugging
     * Face redirects to a CDN and a dropped connection is common enough to be worth retrying.
     */
    private fun downloadFile(url: String, dest: File, onFraction: (Int?) -> Unit) {
        if (dest.isFile && dest.length() > 0) {
            onFraction(null)
            return
        }
        val part = File(dest.parentFile, dest.name + ".part")
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 2) {
            attempt++
            try {
                fetch(url, part, onFraction)
                if (!part.renameTo(dest)) throw IllegalStateException("نوشتن فایل ${dest.name} ممکن نشد")
                return
            } catch (e: Exception) {
                lastError = e
                part.delete()
            }
        }
        throw IllegalStateException("دانلود ${dest.name} ناموفق بود: ${lastError?.message}")
    }

    private fun fetch(url: String, dest: File, onFraction: (Int?) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code برای $url")
            val total = conn.contentLengthLong
            var written = 0L
            conn.inputStream.use { input ->
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            onFraction(((written * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            if (written == 0L) throw IllegalStateException("فایل خالی دریافت شد: $url")
            onFraction(null)
        } finally {
            conn.disconnect()
        }
    }

    private const val USER_AGENT = "CillaTTS-Android"
}
