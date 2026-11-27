package com.cilla.tts

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Pure-Kotlin converter from SentencePiece binary protobuf (`tokenizer.model`) to Hugging Face
 * `tokenizer.json` readable by `tokenizers` inside `libptts_ffi.so`.
 *
 * Supports both SentencePiece **BPE** (`model_type = 2`, used by Cilla models) and **Unigram**
 * (`model_type = 1`, used by Kyutai Pocket TTS), preserving `normalizer_spec.precompiled_charsmap`
 * and control/user-defined `added_tokens`.
 */
object SentencePieceConverter {

    private const val MODEL_TYPE_UNIGRAM = 1
    private const val MODEL_TYPE_BPE = 2

    private const val PIECE_NORMAL = 1
    private const val PIECE_UNKNOWN = 2
    private const val PIECE_CONTROL = 3
    private const val PIECE_USER_DEFINED = 4
    private const val PIECE_UNUSED = 5

    data class Piece(
        val text: String,
        val score: Float,
        val type: Int
    )

    data class TrainerSpec(
        val modelType: Int = MODEL_TYPE_UNIGRAM,
        val byteFallback: Boolean = false,
        val unkId: Int = 0,
        val unkPiece: String = "<unk>"
    )

    data class NormalizerSpec(
        val precompiledCharsmap: ByteArray = ByteArray(0),
        val addDummyPrefix: Boolean = true,
        val removeExtraWhitespaces: Boolean = true,
        val escapeWhitespaces: Boolean = true
    )

    data class ModelProto(
        val pieces: List<Piece>,
        val trainer: TrainerSpec,
        val normalizer: NormalizerSpec
    )

    fun convertFile(modelFile: File, outputJsonFile: File) {
        val proto = parseModelProto(modelFile.readBytes())
        val json = toTokenizerJson(proto)
        val tmp = File(outputJsonFile.parentFile, "${outputJsonFile.name}.tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        if (!tmp.renameTo(outputJsonFile)) {
            outputJsonFile.writeText(json.toString(2), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun parseModelProto(bytes: ByteArray): ModelProto {
        val reader = ProtoReader(bytes, 0, bytes.size)
        val pieces = ArrayList<Piece>(4096)
        var trainer = TrainerSpec()
        var normalizer = NormalizerSpec()

        while (reader.hasRemaining()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when {
                fieldNumber == 1 && wireType == 2 -> {
                    val sub = reader.readSubReader()
                    pieces.add(parsePiece(sub))
                }
                fieldNumber == 2 && wireType == 2 -> {
                    val sub = reader.readSubReader()
                    trainer = parseTrainerSpec(sub)
                }
                fieldNumber == 3 && wireType == 2 -> {
                    val sub = reader.readSubReader()
                    normalizer = parseNormalizerSpec(sub)
                }
                else -> reader.skipField(wireType)
            }
        }
        if (pieces.isEmpty()) {
            throw IllegalArgumentException("فایل tokenizer.model هیچ توکنی ندارد")
        }
        return ModelProto(pieces, trainer, normalizer)
    }

    private fun parsePiece(reader: ProtoReader): Piece {
        var text = ""
        var score = 0f
        var type = PIECE_NORMAL
        while (reader.hasRemaining()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when {
                fieldNumber == 1 && wireType == 2 -> text = reader.readString()
                fieldNumber == 2 && wireType == 5 -> score = reader.readFloat()
                fieldNumber == 3 && wireType == 0 -> type = reader.readVarint().toInt()
                else -> reader.skipField(wireType)
            }
        }
        return Piece(text, score, type)
    }

    private fun parseTrainerSpec(reader: ProtoReader): TrainerSpec {
        var modelType = MODEL_TYPE_UNIGRAM
        var byteFallback = false
        var unkId = 0
        var unkPiece = "<unk>"
        while (reader.hasRemaining()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when {
                fieldNumber == 3 && wireType == 0 -> modelType = reader.readVarint().toInt()
                fieldNumber == 35 && wireType == 0 -> byteFallback = reader.readVarint() != 0L
                fieldNumber == 40 && wireType == 0 -> unkId = reader.readVarint().toInt()
                fieldNumber == 45 && wireType == 2 -> unkPiece = reader.readString()
                else -> reader.skipField(wireType)
            }
        }
        return TrainerSpec(modelType, byteFallback, unkId, unkPiece)
    }

    private fun parseNormalizerSpec(reader: ProtoReader): NormalizerSpec {
        var charsmap = ByteArray(0)
        var addDummyPrefix = true
        var removeExtraWhitespaces = true
        var escapeWhitespaces = true
        while (reader.hasRemaining()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when {
                fieldNumber == 2 && wireType == 2 -> charsmap = reader.readBytes()
                fieldNumber == 3 && wireType == 0 -> addDummyPrefix = reader.readVarint() != 0L
                fieldNumber == 4 && wireType == 0 -> removeExtraWhitespaces = reader.readVarint() != 0L
                fieldNumber == 5 && wireType == 0 -> escapeWhitespaces = reader.readVarint() != 0L
                else -> reader.skipField(wireType)
            }
        }
        return NormalizerSpec(charsmap, addDummyPrefix, removeExtraWhitespaces, escapeWhitespaces)
    }

    fun toTokenizerJson(proto: ModelProto): JSONObject {
        return when (proto.trainer.modelType) {
            MODEL_TYPE_BPE -> buildBpeTokenizerJson(proto)
            MODEL_TYPE_UNIGRAM -> buildUnigramTokenizerJson(proto)
            else -> throw IllegalArgumentException(
                "نوع توکنایزر پشتیبانی نمی‌شود: model_type=${proto.trainer.modelType}"
            )
        }
    }

    private fun buildBpeTokenizerJson(proto: ModelProto): JSONObject {
        val addedTokens = JSONArray()
        val vocabMap = LinkedHashMap<String, Int>(proto.pieces.size * 2)
        val skipForMerges = HashSet<String>()

        proto.pieces.forEachIndexed { id, piece ->
            vocabMap[piece.text] = id
            if (piece.type == PIECE_UNKNOWN || piece.type == PIECE_CONTROL || piece.type == PIECE_UNUSED) {
                skipForMerges.add(piece.text)
            }
            if (piece.type == PIECE_UNKNOWN || piece.type == PIECE_CONTROL || piece.type == PIECE_USER_DEFINED) {
                val isSpecial = piece.type != PIECE_USER_DEFINED
                addedTokens.put(
                    JSONObject()
                        .put("id", id)
                        .put("content", piece.text)
                        .put("single_word", false)
                        .put("lstrip", false)
                        .put("rstrip", false)
                        .put("normalized", false)
                        .put("special", isSpecial)
                )
            }
        }

        val normalizers = JSONArray()
        if (proto.normalizer.precompiledCharsmap.isNotEmpty()) {
            val encoded = Base64.getEncoder().encodeToString(proto.normalizer.precompiledCharsmap)
            normalizers.put(
                JSONObject()
                    .put("type", "Precompiled")
                    .put("precompiled_charsmap", encoded)
            )
        }
        if (proto.normalizer.removeExtraWhitespaces) {
            normalizers.put(
                JSONObject()
                    .put("type", "Strip")
                    .put("strip_left", true)
                    .put("strip_right", true)
            )
            normalizers.put(
                JSONObject()
                    .put("type", "Replace")
                    .put("pattern", JSONObject().put("Regex", " {2,}"))
                    .put("content", " ")
            )
        }
        if (proto.normalizer.addDummyPrefix) {
            normalizers.put(
                JSONObject()
                    .put("type", "Prepend")
                    .put("prepend", "▁")
            )
        }
        if (proto.normalizer.escapeWhitespaces) {
            normalizers.put(
                JSONObject()
                    .put("type", "Replace")
                    .put("pattern", JSONObject().put("String", " "))
                    .put("content", "▁")
            )
        }

        val decoder = JSONObject()
            .put("type", "Sequence")
            .put(
                "decoders",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "Replace")
                            .put("pattern", JSONObject().put("String", "▁"))
                            .put("content", " ")
                    )
                    .put(JSONObject().put("type", "ByteFallback"))
                    .put(JSONObject().put("type", "Fuse"))
                    .put(
                        JSONObject()
                            .put("type", "Strip")
                            .put("content", " ")
                            .put("start", if (proto.normalizer.addDummyPrefix) 1 else 0)
                            .put("stop", 0)
                    )
            )

        val vocabJson = JSONObject()
        for ((piece, id) in vocabMap) {
            vocabJson.put(piece, id)
        }

        val merges = generateBpeMerges(vocabMap, skipForMerges)
        val mergesJson = JSONArray()
        for ((left, right) in merges) {
            mergesJson.put(JSONArray().put(left).put(right))
        }

        val unkPiece = proto.pieces.getOrNull(proto.trainer.unkId)?.text ?: proto.trainer.unkPiece

        val modelJson = JSONObject()
            .put("type", "BPE")
            .put("dropout", JSONObject.NULL)
            .put("unk_token", unkPiece)
            .put("continuing_subword_prefix", JSONObject.NULL)
            .put("end_of_word_suffix", JSONObject.NULL)
            .put("fuse_unk", true)
            .put("byte_fallback", proto.trainer.byteFallback)
            .put("ignore_merges", false)
            .put("vocab", vocabJson)
            .put("merges", mergesJson)

        return JSONObject()
            .put("version", "1.0")
            .put("truncation", JSONObject.NULL)
            .put("padding", JSONObject.NULL)
            .put("added_tokens", addedTokens)
            .put(
                "normalizer",
                JSONObject()
                    .put("type", "Sequence")
                    .put("normalizers", normalizers)
            )
            .put("pre_tokenizer", JSONObject.NULL)
            .put("post_processor", JSONObject.NULL)
            .put("decoder", decoder)
            .put("model", modelJson)
    }

    /**
     * Exact match for Hugging Face `transformers.tokenization_utils_base.generate_merges`:
     * for every multi-character piece in vocab order, emit every valid `(left, right)` split
     * ordered by `(vocab[left], vocab[right])`, then globally sort by `(pieceRank, len(left), len(right))`.
     */
    private fun generateBpeMerges(
        vocab: Map<String, Int>,
        skipTokens: Set<String>
    ): List<Pair<String, String>> {
        data class Candidate(val left: String, val right: String, val rank: Int, val leftRank: Int, val rightRank: Int)

        val all = ArrayList<Candidate>(vocab.size * 2)
        for ((merge, rank) in vocab) {
            if (merge.length < 2 || merge in skipTokens) continue
            val local = ArrayList<Candidate>(merge.length)
            for (i in 1 until merge.length) {
                if (Character.isLowSurrogate(merge[i])) continue
                val left = merge.substring(0, i)
                val right = merge.substring(i)
                if (left in skipTokens || right in skipTokens) continue
                val lr = vocab[left] ?: continue
                val rr = vocab[right] ?: continue
                local.add(Candidate(left, right, rank, lr, rr))
            }
            if (local.size > 1) {
                local.sortWith(compareBy<Candidate> { it.leftRank }.thenBy { it.rightRank })
            }
            all.addAll(local)
        }
        all.sortWith(
            compareBy<Candidate> { it.rank }
                .thenBy { it.left.codePointCount(0, it.left.length) }
                .thenBy { it.right.codePointCount(0, it.right.length) }
        )
        return all.map { it.left to it.right }
    }

    private fun buildUnigramTokenizerJson(proto: ModelProto): JSONObject {
        val addedTokens = JSONArray()
        val vocabArray = JSONArray()
        proto.pieces.forEachIndexed { id, piece ->
            val score = if (piece.score.isFinite()) piece.score.toDouble() else 0.0
            vocabArray.put(JSONArray().put(piece.text).put(score))
            if (piece.type == PIECE_CONTROL) {
                addedTokens.put(
                    JSONObject()
                        .put("id", id)
                        .put("content", piece.text)
                        .put("single_word", false)
                        .put("lstrip", false)
                        .put("rstrip", false)
                        .put("normalized", false)
                        .put("special", true)
                )
            }
        }

        val normalizers = JSONArray()
        if (proto.normalizer.precompiledCharsmap.isNotEmpty()) {
            val encoded = Base64.getEncoder().encodeToString(proto.normalizer.precompiledCharsmap)
            normalizers.put(
                JSONObject()
                    .put("type", "Precompiled")
                    .put("precompiled_charsmap", encoded)
            )
        } else {
            normalizers.put(JSONObject().put("type", "NFKC"))
        }
        normalizers.put(
            JSONObject()
                .put("type", "Replace")
                .put("pattern", JSONObject().put("Regex", "[\n\r\t]"))
                .put("content", " ")
        )
        if (proto.normalizer.removeExtraWhitespaces) {
            normalizers.put(
                JSONObject()
                    .put("type", "Replace")
                    .put("pattern", JSONObject().put("Regex", " {2,}"))
                    .put("content", " ")
            )
            normalizers.put(
                JSONObject()
                    .put("type", "Strip")
                    .put("strip_left", true)
                    .put("strip_right", true)
            )
        }

        val prependScheme = if (proto.normalizer.addDummyPrefix) "always" else "never"
        val metaspace = JSONObject()
            .put("type", "Metaspace")
            .put("replacement", "▁")
            .put("prepend_scheme", prependScheme)
            .put("split", true)

        val modelJson = JSONObject()
            .put("type", "Unigram")
            .put("unk_id", proto.trainer.unkId)
            .put("byte_fallback", proto.trainer.byteFallback)
            .put("vocab", vocabArray)

        return JSONObject()
            .put("version", "1.0")
            .put("truncation", JSONObject.NULL)
            .put("padding", JSONObject.NULL)
            .put("added_tokens", addedTokens)
            .put(
                "normalizer",
                JSONObject()
                    .put("type", "Sequence")
                    .put("normalizers", normalizers)
            )
            .put("pre_tokenizer", metaspace)
            .put("post_processor", JSONObject.NULL)
            .put("decoder", metaspace)
            .put("model", modelJson)
    }

    private class ProtoReader(
        private val data: ByteArray,
        start: Int,
        private val end: Int
    ) {
        private var pos = start

        fun hasRemaining(): Boolean = pos < end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < end && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("ساختار protobuf در tokenizer.model نامعتبر است (varint)")
        }

        fun readFloat(): Float {
            if (pos + 4 > end) {
                throw IllegalArgumentException("ساختار protobuf در tokenizer.model ناقص است (float)")
            }
            val bits = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
            pos += 4
            return Float.fromBits(bits)
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > end) {
                throw IllegalArgumentException("ساختار protobuf در tokenizer.model ناقص است (bytes)")
            }
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun readString(): String = String(readBytes(), Charsets.UTF_8)

        fun readSubReader(): ProtoReader {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > end) {
                throw IllegalArgumentException("ساختار protobuf در tokenizer.model ناقص است (message)")
            }
            val sub = ProtoReader(data, pos, pos + len)
            pos += len
            return sub
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> {
                    if (pos + 8 > end) throw IllegalArgumentException("انتهای غیرمنتظره فایل protobuf")
                    pos += 8
                }
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || pos + len > end) throw IllegalArgumentException("انتهای غیرمنتظره فایل protobuf")
                    pos += len
                }
                5 -> {
                    if (pos + 4 > end) throw IllegalArgumentException("انتهای غیرمنتظره فایل protobuf")
                    pos += 4
                }
                else -> throw IllegalArgumentException("wireType ناشناخته در protobuf: $wireType")
            }
        }
    }
}
