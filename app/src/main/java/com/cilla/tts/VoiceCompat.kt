package com.cilla.tts

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * What a voice file holds, read from its safetensors header.
 *
 * `libptts_ffi.so` reads a voice the same way (`ptts/src/loader.rs`): a file holding
 * `speaker_wavs` holds speaker-Mimi latents that the checkpoint's speaker projection has to map
 * to the flow LM's width, so its channel count must be the projection's input; any other single
 * tensor is a precomputed embedding, already as wide as the flow LM.
 */
class VoiceFileInfo(
    val name: String,
    val tensor: String?,
    val shape: List<Int>,
    val modelExt: String?,
    /** Why the file could not be read at all, or null. */
    val error: String?
) {
    /** `speaker_wavs` latents, which need the checkpoint's speaker projection. */
    val isLatents: Boolean get() = tensor == TENSOR_SPEAKER_WAVS

    /**
     * The number the library compares: channels for `speaker_wavs` (`[C, T]` or `[1, C, T]`),
     * embedding width otherwise (`[T, dim]` or `[1, T, dim]`). Null when the shape makes no sense.
     */
    val width: Int?
        get() = when {
            tensor == null -> null
            isLatents -> when (shape.size) {
                2 -> shape[0]
                3 -> shape[1]
                else -> null
            }
            else -> when (shape.size) {
                2 -> shape[1]
                3 -> shape[2]
                else -> null
            }
        }

    companion object {
        const val TENSOR_EMB = "emb"
        const val TENSOR_SPEAKER_WAVS = "speaker_wavs"
    }
}

/** The parts of a checkpoint's `config.json` that decide which voices it can use. */
class ModelProfile(val config: JSONObject) {

    /**
     * Channels the checkpoint's speaker projection takes: `speaker_mimi.mimi.inner_dim` when the
     * checkpoint has one, otherwise that Mimi's `dimension` (`encoder_latent_dim` in xn-ptts).
     * Null when the config has no `speaker_mimi`, in which case nothing is checked.
     */
    val latentChannels: Int? = run {
        val mimi = config.optJSONObject("speaker_mimi")?.optJSONObject("mimi") ?: return@run null
        val inner = mimi.optInt("inner_dim", 0)
        if (inner > 0) inner else mimi.optInt("dimension", 0).takeIf { it > 0 }
    }

    /** Width of a precomputed voice embedding: the flow LM's `d_model`. */
    val embWidth: Int? = config.optJSONObject("flow_lm")
        ?.optInt("d_model", 0)
        ?.takeIf { it > 0 }

    /** Checkpoint signatures a voice file may name in its `model_ext` metadata. */
    val embeddingsExt: List<String> = run {
        val array = config.optJSONArray("embeddings_ext") ?: return@run emptyList()
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotEmpty() } }
    }

    companion object {
        fun load(dir: File): ModelProfile? {
            val file = File(dir, "config.json")
            if (!file.isFile) return null
            return try {
                ModelProfile(JSONObject(file.readText()))
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Reads voice files and says which checkpoint can use them. */
object VoiceCompat {

    /** The header of a safetensors file is small; a larger one means the file is not one. */
    private const val MAX_HEADER_BYTES = 8 * 1024 * 1024

    /** Where an incompatible voice file is parked so the native loader never sees it. */
    const val UNSUPPORTED_DIR = "voices_unsupported"

    fun inspect(file: File): VoiceFileInfo {
        val header = try {
            readHeader(file)
        } catch (e: Exception) {
            return VoiceFileInfo(file.name, null, emptyList(), null, "فایل safetensors خوانده نشد: ${e.message}")
        }
        var tensor: String? = null
        var shape: List<Int> = emptyList()
        for (name in header.keys()) {
            if (name == "__metadata__") continue
            val entry = header.optJSONObject(name) ?: continue
            val dims = entry.optJSONArray("shape") ?: continue
            val values = (0 until dims.length()).map { dims.optInt(it) }
            if (name == VoiceFileInfo.TENSOR_EMB || name == VoiceFileInfo.TENSOR_SPEAKER_WAVS) {
                tensor = name
                shape = values
                break
            }
            if (tensor == null) {
                tensor = name
                shape = values
            }
        }
        val modelExt = header.optJSONObject("__metadata__")?.optString("model_ext")?.takeIf { it.isNotEmpty() }
        if (tensor == null) {
            return VoiceFileInfo(file.name, null, emptyList(), modelExt, "هیچ تنسوری در فایل نیست")
        }
        return VoiceFileInfo(file.name, tensor, shape, modelExt, null)
    }

    /**
     * Null when [info] fits [profile], otherwise a Persian reason no one has to guess at: this is
     * exactly the check that makes `ptts_new` fail with "has 512 channels but the speaker
     * projection take 32" when an English voice sits in the Persian model's `voices/`.
     */
    fun incompatibility(info: VoiceFileInfo, profile: ModelProfile): String? {
        info.error?.let { return it }
        val modelExt = info.modelExt
        if (modelExt != null && profile.embeddingsExt.isNotEmpty() &&
            profile.embeddingsExt.none { it == modelExt }
        ) {
            return "این صدا برای مدل دیگری ساخته شده است (model_ext=$modelExt)"
        }
        val width = info.width ?: return "شکل تنسور نامعتبر است: ${info.shape}"
        if (info.isLatents) {
            val expected = profile.latentChannels ?: return null
            if (width != expected) {
                return "این صدا $width کانال دارد ولی مدل $expected کانال می‌خواهد"
            }
        } else {
            val expected = profile.embWidth ?: return null
            if (width != expected) {
                return "عرض بردار صدا $width است ولی مدل $expected می‌خواهد"
            }
        }
        return null
    }

    /** The JSON header of a safetensors file: an 8-byte little-endian length, then that many bytes. */
    private fun readHeader(file: File): JSONObject {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 16) throw IllegalStateException("فایل خیلی کوچک است")
            val sizeBytes = ByteArray(8)
            raf.readFully(sizeBytes)
            val headerLength = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).long
            if (headerLength <= 8 || headerLength > MAX_HEADER_BYTES || headerLength > length - 8) {
                throw IllegalStateException("هدر safetensors معتبر نیست")
            }
            val header = ByteArray(headerLength.toInt())
            raf.readFully(header)
            return JSONObject(String(header, Charsets.UTF_8))
        }
    }

    /** A short, user-facing summary of a voice file: kind and width. */
    fun describe(info: VoiceFileInfo): String {
        val width = info.width ?: return "نامشخص"
        val kind = if (info.tensor == VoiceFileInfo.TENSOR_EMB) "بردار" else "latents"
        return String.format(Locale.US, "%s، %d", kind, width)
    }
}
