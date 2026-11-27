package com.cilla.tts

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** The checkpoints the app can hold, each in its own isolated folder under `filesDir`. */
enum class ModelKind(val dirName: String, val label: String) {
    PERSIAN("model", "سیلا مینی (فارسی)"),
    PERSIAN_LITE("model_lite", "سیلا لایت (فارسی)"),
    ENGLISH("model_en", "فونون (انگلیسی)");

    val isPersian: Boolean get() = this == PERSIAN || this == PERSIAN_LITE
}

/** What could be done with the voice files in a checkpoint folder. */
class VoiceReport(
    /** Voice files the native loader can take. */
    val usable: List<String>,
    /** File name to the reason it was set aside. */
    val unsupported: List<Pair<String, String>>
)

/** Outcome of copying a folder of voice files in. */
class VoiceImportSummary(
    val persian: Int,
    val persianLite: Int,
    val english: Int,
    val skipped: List<Pair<String, String>>
)

/**
 * The models, their voices and the app's storage rules.
 *
 * Each checkpoint lives in its own folder (`model` for Cilla Mini, `model_lite` for Cilla Lite,
 * `model_en` for Phonon) and never shares a `voices/` folder: Cilla Mini has `d_model=512` and
 * 32-channel latents, Cilla Lite has `d_model=1024` and 32-channel latents, and Phonon has
 * `d_model=512` and 512-channel latents. [prepare] quarantines incompatible files into
 * `voices_unsupported/` before `ptts_new` ever sees them.
 */
object ModelStorage {
    private const val CONFIG_JSON = "config.json"
    private const val CONFIG_YAML = "config.yaml"
    private const val TOKENIZER_JSON = "tokenizer.json"
    private const val TOKENIZER_MODEL = "tokenizer.model"
    private const val DEFAULT_VOICE = "default-voice.safetensors"

    /** Every weight file the native loader accepts, in the order it tries them. */
    private val WEIGHTS = listOf("model.q8.gguf", "model.safetensors")

    private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "ogg", "opus", "flac", "aac")

    fun modelDir(ctx: Context, kind: ModelKind): File = File(ctx.filesDir, kind.dirName)

    fun voicesDir(ctx: Context, kind: ModelKind): File = File(modelDir(ctx, kind), "voices")

    fun promptsDir(ctx: Context, kind: ModelKind): File = File(modelDir(ctx, kind), "prompts")

    fun weights(dir: File): File? = WEIGHTS.map { File(dir, it) }.firstOrNull { it.isFile }

    /** The files `ptts_new` needs besides voices. */
    fun hasCoreFiles(dir: File): Boolean =
        File(dir, CONFIG_JSON).isFile && File(dir, TOKENIZER_JSON).isFile && weights(dir) != null

    /**
     * True when [kind] can clone a voice from audio right now: it is a Persian checkpoint whose
     * f32 `model.safetensors` is installed, whose config names a speaker encoder, and whose native
     * library was built with the cloning symbols.
     *
     * The GGUF path is deliberately excluded: `quantize --no-mimi-encoder` drops the encoder, so a
     * `model.q8.gguf`-only checkpoint loads for synthesis but cannot encode a prompt, and a
     * `model.q8.gguf` beside a `model.safetensors` would make the UI promise cloning that then fails
     * inside the native call.
     */
    fun canCloneVoices(ctx: Context, kind: ModelKind): Boolean {
        if (!kind.isPersian) return false
        if (!PhononTTS.hasVoicePromptFfi) return false
        val dir = modelDir(ctx, kind)
        if (!hasCoreFiles(dir) || !File(dir, "model.safetensors").isFile) return false
        return ModelProfile.load(dir)?.hasSpeakerEncoder == true
    }

    /** Why [kind] cannot clone a voice, or null when it can. */
    fun cloneUnavailableReason(ctx: Context, kind: ModelKind): String? {
        if (!kind.isPersian) return "کلون صدا فقط برای مدل‌های فارسی است"
        if (!PhononTTS.hasVoicePromptFfi) {
            return if (PhononTTS.hasVoiceCloningFfi) {
                "کتابخانه بومی نصب‌شده از کلون صدا با فایل پشتیبانی می‌کند اما نسخه کامل نیست؛ " +
                    "با native/build-ptts-ffi.sh دوباره بسازید"
            } else {
                "کتابخانه بومی این APK از کلون صدا پشتیبانی نمی‌کند (با native/build-ptts-ffi.sh بسازید)"
            }
        }
        val dir = modelDir(ctx, kind)
        if (!hasCoreFiles(dir)) return "مدل ${kind.label} نصب نیست"
        if (!File(dir, "model.safetensors").isFile) {
            return "مدل ${kind.label} وزن f32 (model.safetensors) ندارد، پس نمی‌تواند صدا کلون کند"
        }
        if (ModelProfile.load(dir)?.hasSpeakerEncoder != true) {
            return "این نسخه از مدل ${kind.label} رمزگذار گوینده (speaker encoder) ندارد"
        }
        return null
    }

    /**
     * The checkpoint's `audio_prompt_min_duration` / `audio_prompt_max_duration`, in seconds,
     * clamped to a sane range. The native clone applies exactly these, so trimming a prompt here
     * with the same numbers keeps the two from disagreeing about what "too short" means.
     */
    fun promptDurationBounds(ctx: Context, kind: ModelKind): Pair<Float, Float> {
        val profile = ModelProfile.load(modelDir(ctx, kind))
        val min = profile?.promptMinSeconds?.toFloat()?.coerceIn(0.5f, 10f) ?: 1.0f
        val max = profile?.promptMaxSeconds?.toFloat()?.coerceIn(5f, 60f) ?: 30.0f
        return if (max <= min) min to (min + 1f) else min to max
    }

    /** Every voice file of a checkpoint, as the loader scans them: the safetensors in `voices/`, plus a root `default-voice.safetensors`. */
    fun voiceFiles(dir: File): List<File> {
        val inVoices = File(dir, "voices").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".safetensors") }
            ?.sortedBy { it.name }
            ?: emptyList()
        val root = File(dir, DEFAULT_VOICE).takeIf { it.isFile }
        return if (root != null) inVoices + root else inVoices
    }

    /** Voice names as the native loader lists them: a file's name without `.safetensors`. */
    fun voiceNames(dir: File): List<String> = voiceFiles(dir).map {
        if (it.name == DEFAULT_VOICE) "default" else it.name.removeSuffix(".safetensors")
    }

    fun voiceNames(ctx: Context, kind: ModelKind): List<String> = voiceNames(modelDir(ctx, kind))

    /** Core files and at least one voice: enough to load and speak. */
    fun isReady(dir: File): Boolean = hasCoreFiles(dir) && voiceFiles(dir).isNotEmpty()

    fun isReady(ctx: Context, kind: ModelKind): Boolean = isReady(modelDir(ctx, kind))

    fun hasCoreFiles(ctx: Context, kind: ModelKind): Boolean = hasCoreFiles(modelDir(ctx, kind))

    fun prepare(ctx: Context, kind: ModelKind, quarantine: Boolean = true): VoiceReport =
        prepare(modelDir(ctx, kind), quarantine)

    /**
     * Checks every voice file against the checkpoint's config and, when [quarantine] is set,
     * moves the ones it cannot use into `voices_unsupported/`, where the loader never looks.
     * Returns what is left. A folder without a readable config is left as it is.
     */
    fun prepare(dir: File, quarantine: Boolean = true): VoiceReport {
        val files = voiceFiles(dir)
        val profile = ModelProfile.load(dir)
        val usable = mutableListOf<String>()
        val unsupported = mutableListOf<Pair<String, String>>()
        if (profile == null) {
            for (file in files) usable += file.name
            return VoiceReport(usable, unsupported)
        }
        for (file in files) {
            val reason = VoiceCompat.incompatibility(VoiceCompat.inspect(file), profile)
            if (reason == null) {
                usable += file.name
                continue
            }
            unsupported += file.name to reason
            if (quarantine) {
                moveFile(file, File(File(dir, VoiceCompat.UNSUPPORTED_DIR), file.name))
            }
        }
        return VoiceReport(usable, unsupported)
    }

    /**
     * Copies a checkpoint folder picked through SAF into the app's internal storage.
     *
     * Supports both already-converted `xn-ptts` folders (`config.json` + `tokenizer.json`) AND
     * raw Hugging Face `mehdi-hf/cilla-lite` folders (`config.yaml` + `tokenizer.model` +
     * optional `prompts/` WAV files), converting config, tokenizer, and prompt WAVs automatically.
     */
    fun importModel(ctx: Context, kind: ModelKind, treeUri: Uri, onStatus: (String) -> Unit): VoiceReport {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: throw IllegalStateException("دسترسی به پوشه امکان‌پذیر نیست")

        val configJsonDoc = root.findFile(CONFIG_JSON)?.takeIf { it.isFile }
        val configYamlDoc = root.findFile(CONFIG_YAML)?.takeIf { it.isFile }
        if (configJsonDoc == null && configYamlDoc == null) {
            throw IllegalStateException("فایل config.json یا config.yaml در پوشه یافت نشد")
        }

        val tokJsonDoc = root.findFile(TOKENIZER_JSON)?.takeIf { it.isFile }
        val tokModelDoc = root.findFile(TOKENIZER_MODEL)?.takeIf { it.isFile }
        if (tokJsonDoc == null && tokModelDoc == null) {
            throw IllegalStateException("فایل tokenizer.json یا tokenizer.model در پوشه یافت نشد")
        }

        val weightName = WEIGHTS.firstOrNull { root.findFile(it)?.isFile == true }
            ?: throw IllegalStateException("فایل وزن مدل (model.safetensors یا model.q8.gguf) در پوشه یافت نشد")
        val weightDoc = root.findFile(weightName)!!

        val target = modelDir(ctx, kind)
        val voices = File(target, "voices")
        val prompts = File(target, "prompts")
        target.mkdirs()
        voices.mkdirs()

        onStatus("در حال کپی $weightName...")
        copyDoc(ctx, weightDoc, File(target, weightName))

        if (configJsonDoc != null) {
            onStatus("در حال کپی config.json...")
            copyDoc(ctx, configJsonDoc, File(target, CONFIG_JSON))
        } else {
            val yamlFile = File(target, CONFIG_YAML)
            onStatus("در حال کپی و تبدیل config.yaml...")
            copyDoc(ctx, configYamlDoc!!, yamlFile)
            CillaLiteConverter.convertFile(yamlFile, File(target, CONFIG_JSON))
        }

        if (tokJsonDoc != null) {
            onStatus("در حال کپی tokenizer.json...")
            copyDoc(ctx, tokJsonDoc, File(target, TOKENIZER_JSON))
        } else {
            val spmFile = File(target, TOKENIZER_MODEL)
            onStatus("در حال تبدیل tokenizer.model به tokenizer.json...")
            copyDoc(ctx, tokModelDoc!!, spmFile)
            SentencePieceConverter.convertFile(spmFile, File(target, TOKENIZER_JSON))
        }

        val rootVoice = root.findFile(DEFAULT_VOICE)?.takeIf { it.isFile }
        if (rootVoice != null) {
            onStatus("در حال کپی $DEFAULT_VOICE...")
            copyDoc(ctx, rootVoice, File(target, DEFAULT_VOICE))
        }

        val voicesDoc = root.findFile("voices")?.takeIf { it.isDirectory }
        val voiceFiles = voicesDoc?.listFiles()?.filter { it.isFile && it.name?.endsWith(".safetensors") == true }
            ?: emptyList()
        for (doc in voiceFiles) {
            val name = doc.name ?: continue
            onStatus("در حال کپی گوینده $name...")
            copyDoc(ctx, doc, File(voices, name))
        }

        // Copy and encode reference audio prompts (e.g. cilla-lite's prompts/voice1.wav, voice2.wav).
        val promptsDoc = root.findFile("prompts")?.takeIf { it.isDirectory }
        val promptAudioDocs = promptsDoc?.listFiles()?.filter { doc ->
            val ext = doc.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            doc.isFile && ext in AUDIO_EXTENSIONS
        } ?: emptyList()

        if (promptAudioDocs.isNotEmpty()) {
            prompts.mkdirs()
            for (doc in promptAudioDocs) {
                val fileName = doc.name ?: continue
                val destPrompt = File(prompts, fileName)
                onStatus("در حال کپی فایل نمونه $fileName...")
                copyDoc(ctx, doc, destPrompt)
                val stem = sanitizeVoiceName(fileName.substringBeforeLast('.'))
                val voiceOut = File(voices, "$stem.safetensors")
                if (!voiceOut.isFile && PhononTTS.hasVoiceCloningFfi && File(target, "model.safetensors").isFile) {
                    onStatus("در حال ساخت بردار صدای $stem...")
                    try {
                        val pcm = AudioDecoder.decodeFileToMono24k(destPrompt)
                        PhononTTS.cloneVoiceToFile(
                            modelDir = target,
                            pcm = pcm,
                            sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                            outputFile = voiceOut,
                            normalizeLoudness = false
                        )
                    } catch (_: Exception) {
                        // Keep the copied prompt file; user can retry or inspect error when cloning.
                    }
                }
            }
        }

        configMismatch(target)?.let { reason ->
            throw IllegalStateException("مدل ${kind.label} قابل بارگذاری نیست: $reason")
        }
        val report = prepare(target)
        if (report.usable.isEmpty() && kind != ModelKind.PERSIAN_LITE) {
            throw IllegalStateException(
                "هیچ گوینده سازگاری با مدل ${kind.label} پیدا نشد" +
                    report.unsupported.firstOrNull()?.let { " (${it.first}: ${it.second})" }.orEmpty()
            )
        }
        return report
    }

    /**
     * A reason this folder's weights cannot work with its `config.json`, or null when they can.
     *
     * Only the one mismatch that silently produces bad audio instead of an error is checked here:
     * a config that declares a `speaker_wavs` prefix sink (Cilla Lite, converted from a
     * `flow_lm.bos_before_voice` checkpoint) needs that learnt token in the weights. Without it
     * `ptts_new` fails on a missing tensor, so an imported folder is refused with the reason
     * instead of at the first attempt to speak.
     */
    fun configMismatch(dir: File): String? {
        val profile = ModelProfile.load(dir) ?: return null
        if (!profile.hasSpeakerSink) return null
        val weights = weights(dir) ?: return null
        if (!weights.name.endsWith(".safetensors")) return null
        val names = VoiceCompat.tensorNames(weights)
        if (names.isEmpty()) return null
        val hasSink = names.any {
            it == "flow_lm.bos_before_voice" ||
                it == "flow_lm.condition_provider.conditioners.speaker_wavs.sink"
        }
        return if (hasSink) {
            null
        } else {
            "config.json یک توکن speaker_wavs.sink اعلام می‌کند ولی ${weights.name} آن را ندارد"
        }
    }

    /** A voice file the loader will actually bind to this checkpoint. */
    private fun isUsableVoice(file: File, profile: ModelProfile?): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        if (profile == null) return true
        return VoiceCompat.incompatibility(VoiceCompat.inspect(file), profile) == null
    }

    /**
     * Decode and encode the prompt audio files in `prompts/` that have no `.safetensors` voice
     * yet, in a single native call so the checkpoint's weights are loaded once.
     *
     * Returns the names written; prompts the model refuses are reported through [onStatus] and
     * left in place (deleting the user's recording would be worse than retrying).
     */
    fun encodePendingPrompts(ctx: Context, kind: ModelKind, onStatus: (String) -> Unit): Int {
        val dir = modelDir(ctx, kind)
        val prompts = promptsDir(ctx, kind)
        val voices = voicesDir(ctx, kind).apply { mkdirs() }
        if (!prompts.isDirectory || !File(dir, "model.safetensors").isFile) return 0
        if (!PhononTTS.hasVoicePromptFfi) {
            onStatus("کتابخانه بومی کلون صدا را پشتیبانی نمی‌کند؛ فایل‌های نمونه کپی شدند")
            return 0
        }

        val audioFiles = prompts.listFiles()?.filter { f ->
            f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS
        }?.sortedBy { it.name } ?: emptyList()
        val pending = audioFiles.mapNotNull { file ->
            val stem = sanitizeVoiceName(file.nameWithoutExtension)
            if (File(voices, "$stem.safetensors").isFile) null else stem to file
        }
        if (pending.isEmpty()) return 0

        onStatus("در حال استخراج ${pending.size} بردار صدا (یک‌بار بارگذاری وزن‌ها)...")
        val decoded = mutableListOf<Pair<String, FloatArray>>()
        val undecodable = mutableListOf<Pair<String, String>>()
        for ((stem, file) in pending) {
            try {
                decoded += stem to AudioDecoder.decodeFileToMono24k(file)
            } catch (e: Exception) {
                undecodable += stem to (e.message ?: "فایل صوتی خوانده نشد")
            }
        }
        val (written, refused) = PhononTTS.cloneVoicesToDir(dir, voices, decoded)
        val profile = ModelProfile.load(dir)
        val created = written.count { isUsableVoice(File(voices, "$it.safetensors"), profile) }
        for ((name, reason) in undecodable + refused) {
            onStatus("صدا برای $name ساخته نشد: $reason")
        }
        if (created > 0) prepare(dir)
        return created
    }

    /**
     * Clones a voice from 24 kHz mono float PCM into [kind]'s `voices/` directory and validates it.
     * Returns `(voiceName, savedFile)`.
     */
    fun cloneVoiceFromPcm(
        ctx: Context,
        kind: ModelKind,
        rawName: String,
        pcm: FloatArray
    ): Pair<String, File> {
        val dir = modelDir(ctx, kind)
        if (!canCloneVoices(ctx, kind)) {
            throw IllegalStateException("مدل ${kind.label} از کلون صدا پشتیبانی نمی‌کند یا فایل model.safetensors ندارد")
        }
        val safeName = sanitizeVoiceName(rawName)
        val (minSeconds, maxSeconds) = promptDurationBounds(ctx, kind)
        val validated = AudioDecoder.validateAndTrimPrompt(
            pcm,
            AudioDecoder.TARGET_SAMPLE_RATE,
            minSeconds = minSeconds,
            maxSeconds = maxSeconds
        )
        val voices = voicesDir(ctx, kind).apply { mkdirs() }
        val out = File(voices, "$safeName.safetensors")
        val cloned = PhononTTS.cloneVoiceToFile(
            modelDir = dir,
            pcm = validated,
            sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
            outputFile = out,
            normalizeLoudness = false
        )
        // Check the file against the config before reporting success: `prepare` removes it when it
        // does not fit, so "written but unusable" has to fail here rather than at the next load.
        val profile = ModelProfile.load(dir)
        val mismatch = profile?.let { VoiceCompat.incompatibility(VoiceCompat.inspect(out), it) }
        val dimensionMismatch = cloned.dim != 0 && profile?.embWidth != null &&
            cloned.dim != profile.embWidth
        if (!isUsableVoice(out, profile) || dimensionMismatch) {
            out.delete()
            val reason = mismatch ?: if (dimensionMismatch) "عرض بردار ${cloned.dim} با مدل ${profile?.embWidth} هم‌خوان نیست" else null
            throw IllegalStateException(
                "صدای ساخته‌شده معتبر نبود" + reason?.let { ": $it" }.orEmpty()
            )
        }
        prepare(dir)
        return safeName to out
    }

    fun cloneVoiceFromUri(
        ctx: Context,
        kind: ModelKind,
        rawName: String,
        audioUri: Uri
    ): Pair<String, File> {
        val pcm = AudioDecoder.decodeUriToMono24k(ctx, audioUri)
        return cloneVoiceFromPcm(ctx, kind, rawName, pcm)
    }

    fun sanitizeVoiceName(raw: String): String {
        val trimmed = raw.trim()
            .replace(Regex("[/\\\\:*?\"<>|.]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')
        return trimmed.ifEmpty { "cloned_${System.currentTimeMillis() % 10000}" }
    }

    private fun copyDoc(ctx: Context, doc: DocumentFile, dest: File) {
        dest.parentFile?.mkdirs()
        val input = ctx.contentResolver.openInputStream(doc.uri)
            ?: throw IllegalStateException("خطا در خواندن ${doc.name}")
        input.use { source ->
            FileOutputStream(dest).use { output -> source.copyTo(output, bufferSize = 64 * 1024) }
        }
    }

    /**
     * Copies every `.safetensors` in the picked folder into the checkpoint whose config can use it.
     */
    fun importVoices(ctx: Context, treeUri: Uri, onStatus: (String) -> Unit): VoiceImportSummary {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: throw IllegalStateException("دسترسی به پوشه امکان‌پذیر نیست")
        val profiles = mutableMapOf<ModelKind, ModelProfile>()
        for (kind in ModelKind.entries) {
            val dir = modelDir(ctx, kind)
            ModelProfile.load(dir)?.let { profiles[kind] = it }
        }
        if (profiles.isEmpty()) {
            throw IllegalStateException("ابتدا حداقل یک مدل را نصب کنید، بعد پوشه صدا را انتخاب کنید")
        }
        val sources = collectVoiceDocs(root)
        if (sources.isEmpty()) {
            throw IllegalStateException("هیچ فایل safetensors در پوشه انتخاب‌شده پیدا نشد")
        }
        val staging = File(ctx.cacheDir, "voice_import").apply {
            deleteRecursively()
            mkdirs()
        }
        var persian = 0
        var persianLite = 0
        var english = 0
        val skipped = mutableListOf<Pair<String, String>>()
        try {
            for (doc in sources) {
                val name = doc.name ?: continue
                val temp = File(staging, name)
                onStatus("در حال بررسی $name...")
                copyDoc(ctx, doc, temp)
                val info = VoiceCompat.inspect(temp)
                val usable = profiles.filterValues { VoiceCompat.incompatibility(info, it) == null }.keys
                if (usable.isEmpty()) {
                    val reason = profiles.values.firstNotNullOfOrNull { VoiceCompat.incompatibility(info, it) }
                        ?: "با هیچ مدل نصب‌شده‌ای سازگار نیست"
                    skipped += name to reason
                    continue
                }
                val targets = if (usable.size == 1) usable.toList() else listOf(pickKind(info, usable, profiles))
                for ((index, kind) in targets.withIndex()) {
                    val dest = File(voicesDir(ctx, kind), name)
                    dest.parentFile?.mkdirs()
                    if (dest.exists()) dest.delete()
                    if (index == targets.lastIndex) {
                        moveFile(temp, dest)
                    } else {
                        temp.inputStream().use { input ->
                            FileOutputStream(dest).use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
                        }
                    }
                    when (kind) {
                        ModelKind.PERSIAN -> persian++
                        ModelKind.PERSIAN_LITE -> persianLite++
                        ModelKind.ENGLISH -> english++
                    }
                }
            }
        } finally {
            staging.deleteRecursively()
        }
        for (kind in ModelKind.entries) {
            if (profiles.containsKey(kind)) prepare(modelDir(ctx, kind))
        }
        return VoiceImportSummary(persian, persianLite, english, skipped)
    }

    private fun pickKind(
        info: VoiceFileInfo,
        usable: Set<ModelKind>,
        profiles: Map<ModelKind, ModelProfile>
    ): ModelKind {
        val modelExt = info.modelExt
        if (modelExt != null) {
            usable.firstOrNull { profiles[it]?.embeddingsExt?.contains(modelExt) == true }?.let { return it }
        }
        // `emb` width 1024 only matches PERSIAN_LITE; 512 matches PERSIAN or ENGLISH.
        if (ModelKind.PERSIAN_LITE in usable && info.width == 1024) return ModelKind.PERSIAN_LITE
        if (ModelKind.PERSIAN in usable) return ModelKind.PERSIAN
        if (ModelKind.PERSIAN_LITE in usable) return ModelKind.PERSIAN_LITE
        return usable.first()
    }

    /** Every `.safetensors` under [root], one folder deep. */
    private fun collectVoiceDocs(root: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        for (child in root.listFiles()) {
            if (child.isFile && child.name?.endsWith(".safetensors") == true) {
                result += child
            } else if (child.isDirectory) {
                for (grandChild in child.listFiles()) {
                    if (grandChild.isFile && grandChild.name?.endsWith(".safetensors") == true) {
                        result += grandChild
                    }
                }
            }
        }
        return result.sortedBy { it.name }
    }

    private fun moveFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (source.renameTo(target)) return
        source.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
        }
        source.delete()
    }

    /** Deletes a single voice from [kind]. Returns true if removed. */
    fun deleteVoice(ctx: Context, kind: ModelKind, voiceName: String): Boolean {
        val dir = modelDir(ctx, kind)
        val file = if (voiceName == "default") {
            File(dir, DEFAULT_VOICE)
        } else {
            File(File(dir, "voices"), "$voiceName.safetensors")
        }
        val deleted = file.delete()
        if (deleted && TtsPrefs.getVoice(ctx, kind) == voiceName) {
            TtsPrefs.clearVoice(ctx, kind)
        }
        return deleted
    }

    /** Removes every voice file across all models, keeping the models. Returns how many files went. */
    fun deleteVoices(ctx: Context): Int {
        var removed = 0
        for (kind in ModelKind.entries) {
            val dir = modelDir(ctx, kind)
            for (sub in listOf("voices", VoiceCompat.UNSUPPORTED_DIR)) {
                val folder = File(dir, sub)
                removed += folder.listFiles()?.size ?: 0
                folder.deleteRecursively()
            }
            if (File(dir, DEFAULT_VOICE).delete()) removed += 1
        }
        TtsPrefs.clearVoices(ctx)
        return removed
    }

    /** Removes a single checkpoint and its voices. */
    fun deleteModel(ctx: Context, kind: ModelKind) {
        modelDir(ctx, kind).deleteRecursively()
        TtsPrefs.clearVoice(ctx, kind)
    }

    /** Removes all checkpoints and their voices from the app's internal storage. */
    fun deleteModels(ctx: Context) {
        for (kind in ModelKind.entries) {
            modelDir(ctx, kind).deleteRecursively()
        }
        TtsPrefs.clearVoices(ctx)
    }

    fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0
        val files = dir.listFiles() ?: return 0
        var total = 0L
        for (file in files) {
            total += if (file.isDirectory) sizeOf(file) else file.length()
        }
        return total
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }
}
