package com.cilla.tts

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** The two checkpoints the app can hold, each in its own folder under the app's files dir. */
enum class ModelKind(val dirName: String, val label: String) {
    PERSIAN("model", "فارسی"),
    ENGLISH("model_en", "انگلیسی")
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
    val english: Int,
    val skipped: List<Pair<String, String>>
)

/**
 * The models, their voices and the app's storage rules.
 *
 * The two checkpoints live in `filesDir/model` and `filesDir/model_en` and never share a
 * `voices/` folder: Cilla's speaker projection takes 32 channels and Phonon's 512, so a voice in
 * the wrong folder makes `ptts_new` fail for the whole model ("has 512 channels but the speaker
 * projection take 32"). [prepare] keeps that from happening, and [importVoices] files a folder of
 * voices by asking each checkpoint's config which of them it can actually use.
 */
object ModelStorage {
    private const val CONFIG = "config.json"
    private const val TOKENIZER = "tokenizer.json"
    private const val DEFAULT_VOICE = "default-voice.safetensors"

    /** Every weight file the native loader accepts, in the order it tries them. */
    private val WEIGHTS = listOf("model.q8.gguf", "model.safetensors")

    fun modelDir(ctx: Context, kind: ModelKind): File = File(ctx.filesDir, kind.dirName)

    fun voicesDir(ctx: Context, kind: ModelKind): File = File(modelDir(ctx, kind), "voices")

    fun weights(dir: File): File? = WEIGHTS.map { File(dir, it) }.firstOrNull { it.isFile }

    /** The files `ptts_new` needs besides voices. */
    fun hasCoreFiles(dir: File): Boolean =
        File(dir, CONFIG).isFile && File(dir, TOKENIZER).isFile && weights(dir) != null

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

    /** Core files and at least one voice: enough to load and speak. */
    fun isReady(dir: File): Boolean = hasCoreFiles(dir) && voiceFiles(dir).isNotEmpty()

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

    /** Copies a checkpoint folder picked through the storage picker into the app's files. */
    fun importModel(ctx: Context, kind: ModelKind, treeUri: Uri, onStatus: (String) -> Unit): VoiceReport {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: throw IllegalStateException("دسترسی به پوشه امکان‌پذیر نیست")
        val configDoc = root.findFile(CONFIG)
            ?: throw IllegalStateException("فایل config.json در پوشه یافت نشد")
        val tokenizerDoc = root.findFile(TOKENIZER)
            ?: throw IllegalStateException("فایل tokenizer.json در پوشه یافت نشد")
        // Phonon ships a GGUF; Cilla only publishes safetensors.
        val weightName = WEIGHTS.firstOrNull { root.findFile(it) != null }
            ?: throw IllegalStateException("فایل وزن مدل (model.safetensors یا model.q8.gguf) در پوشه یافت نشد")
        val weightDoc = root.findFile(weightName)!!

        val target = modelDir(ctx, kind)
        val voices = File(target, "voices")
        target.mkdirs()
        voices.mkdirs()

        onStatus("در حال کپی config.json...")
        copyDoc(ctx, configDoc, File(target, CONFIG))
        onStatus("در حال کپی tokenizer.json...")
        copyDoc(ctx, tokenizerDoc, File(target, TOKENIZER))
        onStatus("در حال کپی $weightName...")
        copyDoc(ctx, weightDoc, File(target, weightName))

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

        val report = prepare(target)
        if (report.usable.isEmpty()) {
            throw IllegalStateException(
                "هیچ گوینده سازگاری با مدل ${kind.label} پیدا نشد" +
                    report.unsupported.firstOrNull()?.let { " (${it.first}: ${it.second})" }.orEmpty()
            )
        }
        return report
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
     * Copies every `.safetensors` in the picked folder into the checkpoint whose config can use
     * it. This is the safe way to add voices: a Phonon voice (512 channels) can no longer land in
     * `model/voices` and break the Persian model.
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
            throw IllegalStateException("ابتدا مدل فارسی یا انگلیسی را نصب کنید، بعد پوشه صدا را انتخاب کنید")
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
        return VoiceImportSummary(persian, english, skipped)
    }

    /**
     * A voice both checkpoints accept -- an `audio_prompt`/`emb` file is 512 wide, which is the
     * flow LM width of both -- goes where its metadata says, and otherwise to Cilla: the Persian
     * voices published for the demo carry no metadata at all, while Phonon voices name their
     * checkpoint. Latent files never reach here: their channel count picks the model outright.
     */
    private fun pickKind(
        info: VoiceFileInfo,
        usable: Set<ModelKind>,
        profiles: Map<ModelKind, ModelProfile>
    ): ModelKind {
        val modelExt = info.modelExt
        if (modelExt != null) {
            usable.firstOrNull { profiles[it]?.embeddingsExt?.contains(modelExt) == true }?.let { return it }
        }
        return if (ModelKind.PERSIAN in usable) ModelKind.PERSIAN else usable.first()
    }

    /** Every `.safetensors` under [root], one folder deep, which is how voice folders are shipped. */
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

    /** Removes every voice file, keeping the models. Returns how many files went. */
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

    /** Removes both checkpoints and their voices from the app's internal storage. */
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
