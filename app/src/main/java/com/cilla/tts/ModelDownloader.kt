package com.cilla.tts

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches checkpoints into the app's files directory. Every call blocks: run it on a background
 * thread.
 *
 * Downloads land in `<file>.part` and are renamed when complete, so an interrupted download never
 * leaves a half-written file that looks usable; a file already in place is kept for cheap retries.
 */
object ModelDownloader {

    private const val PERSIAN_BASE =
        "https://huggingface.co/spaces/mehdi-hf/cilla-mini-demo/resolve/main/model"
    private const val PERSIAN_VOICES_API =
        "https://huggingface.co/api/spaces/mehdi-hf/cilla-mini-demo/tree/main/model/voices"

    private const val CILLA_LITE_BASE = "https://huggingface.co/mehdi-hf/cilla-lite/resolve/main"
    private val CILLA_LITE_PROMPTS = listOf("voice1.wav", "voice2.wav")

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
            progress.onMessage("در حال دانلود $name (سیلا مینی)...")
            downloadFile("$PERSIAN_BASE/$name", File(dir, name), null, progress.onFraction)
        }

        val names = persianVoiceNames(progress)
        for (name in names) {
            progress.onMessage("در حال دانلود گوینده $name...")
            downloadFile(
                "$PERSIAN_BASE/voices/$name.safetensors",
                File(voices, "$name.safetensors"),
                null,
                progress.onFraction
            )
        }

        val report = ModelStorage.prepare(dir)
        if (!ModelStorage.isReady(dir)) {
            throw IllegalStateException("مدل سیلا مینی کامل نیست؛ دوباره تلاش کنید")
        }
        return report
    }

    /**
     * Downloads `mehdi-hf/cilla-lite` (gated repo — requires a Hugging Face read token after
     * accepting the model terms), converts `config.yaml` -> `config.json` and `tokenizer.model`
     * -> `tokenizer.json` on-device, and encodes `prompts/voice1.wav` & `prompts/voice2.wav`
     * into ready-to-use `.safetensors` voices.
     */
    fun installCillaLite(ctx: Context, hfToken: String?, progress: Progress): VoiceReport {
        val token = hfToken?.trim()?.takeIf { it.isNotEmpty() }
        val dir = ModelStorage.modelDir(ctx, ModelKind.PERSIAN_LITE)
        val voices = ModelStorage.voicesDir(ctx, ModelKind.PERSIAN_LITE)
        val prompts = ModelStorage.promptsDir(ctx, ModelKind.PERSIAN_LITE)
        dir.mkdirs()
        voices.mkdirs()
        prompts.mkdirs()

        val yamlFile = File(dir, "config.yaml")
        progress.onMessage("در حال دانلود config.yaml (سیلا لایت)...")
        downloadFile("$CILLA_LITE_BASE/config.yaml", yamlFile, token, progress.onFraction)

        val spmFile = File(dir, "tokenizer.model")
        progress.onMessage("در حال دانلود tokenizer.model (سیلا لایت)...")
        downloadFile("$CILLA_LITE_BASE/tokenizer.model", spmFile, token, progress.onFraction)
        progress.onMessage("در حال تبدیل tokenizer.model به tokenizer.json...")
        SentencePieceConverter.convertFile(spmFile, File(dir, "tokenizer.json"))

        progress.onMessage("در حال دانلود model.safetensors (سیلا لایت ~۴۳۸ مگابایت)...")
        downloadFile(
            "$CILLA_LITE_BASE/model.safetensors",
            File(dir, "model.safetensors"),
            token,
            progress.onFraction
        )

        progress.onMessage("در حال تبدیل config.yaml به config.json...")
        CillaLiteConverter.convertFile(yamlFile, File(dir, "config.json"))

        for (promptName in CILLA_LITE_PROMPTS) {
            progress.onMessage("در حال دانلود فایل نمونه $promptName...")
            downloadFile(
                "$CILLA_LITE_BASE/prompts/$promptName",
                File(prompts, promptName),
                token,
                progress.onFraction
            )
        }

        if (PhononTTS.hasVoiceCloningFfi) {
            ModelStorage.encodePendingPrompts(ctx, ModelKind.PERSIAN_LITE) { msg ->
                progress.onMessage(msg)
                progress.onFraction(null)
            }
        }

        val report = ModelStorage.prepare(dir)
        if (!ModelStorage.hasCoreFiles(dir)) {
            throw IllegalStateException("مدل سیلا لایت کامل نیست؛ دوباره تلاش کنید")
        }
        return report
    }

    fun installEnglish(ctx: Context, progress: Progress): VoiceReport {
        val dir = ModelStorage.modelDir(ctx, ModelKind.ENGLISH)
        dir.mkdirs()
        for (name in listOf("config.json", "tokenizer.json", "model.safetensors")) {
            progress.onMessage("در حال دانلود $name (مدل انگلیسی)...")
            downloadFile("$ENGLISH_BASE/$name", File(dir, name), null, progress.onFraction)
        }
        progress.onMessage("در حال دانلود صدای پیش‌فرض انگلیسی...")
        downloadFile(
            "$ENGLISH_BASE/default-voice.safetensors",
            File(dir, "default-voice.safetensors"),
            null,
            progress.onFraction
        )

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
     * Downloads [url] to [dest] unless it is already there and non-empty. Two attempts on transient
     * network errors (auth failures 401/403 fail immediately with a clear Persian explanation).
     */
    private fun downloadFile(
        url: String,
        dest: File,
        hfToken: String?,
        onFraction: (Int?) -> Unit
    ) {
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
                fetch(url, part, hfToken, onFraction)
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                return
            } catch (e: AuthException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                part.delete()
            }
        }
        throw IllegalStateException("دانلود ${dest.name} ناموفق بود: ${lastError?.message}")
    }

    private class AuthException(message: String) : IllegalStateException(message)

    /**
     * Follows redirects manually so the Hugging Face `Authorization: Bearer` header is sent ONLY
     * to `huggingface.co` and stripped before following pre-signed CDN/Xet/S3 redirect URLs
     * (which reject requests carrying a second Authorization header with HTTP 400).
     */
    private fun fetch(url: String, dest: File, hfToken: String?, onFraction: (Int?) -> Unit) {
        var current = url
        var conn: HttpURLConnection? = null
        try {
            repeat(8) {
                val parsed = URL(current)
                val next = (parsed.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (!hfToken.isNullOrEmpty() && parsed.host.endsWith("huggingface.co", ignoreCase = true)) {
                        setRequestProperty("Authorization", "Bearer $hfToken")
                    }
                }
                next.connect()
                val code = next.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = next.getHeaderField("Location")
                    next.disconnect()
                    if (loc.isNullOrBlank()) {
                        throw IllegalStateException("پاسخ تغییر مسیر نامعتبر (HTTP $code)")
                    }
                    current = URL(parsed, loc).toString()
                } else {
                    conn = next
                    if (code == 401 || code == 403) {
                        throw AuthException(
                            "دسترسی به مدل مجاز نیست (HTTP $code). لطفاً ابتدا در صفحه mehdi-hf/cilla-lite روی Hugging Face شرایط دسترسی را تأیید کرده و توکن معتبر (hf_...) وارد کنید."
                        )
                    }
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code برای $url")
                    }
                    val total = next.contentLengthLong
                    var written = 0L
                    next.inputStream.use { input ->
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
                    return
                }
            }
            throw IllegalStateException("تعداد تغییر مسیرها بیش از حد بود")
        } finally {
            conn?.disconnect()
        }
    }

    private const val USER_AGENT = "CillaTTS-Android/1.2"
}
