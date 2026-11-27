package com.cilla.tts

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a `pocket-tts` YAML checkpoint config (such as `mehdi-hf/cilla-lite`'s `config.yaml`)
 * into the `config.json` format consumed by `libptts_ffi.so` (`gradium-ai/xn-ptts`).
 *
 * When a sibling `model.safetensors` is present alongside `config.yaml`, its lightweight JSON
 * tensor header is also inspected to auto-verify `mimi.inner_dim`, `downsample_channel_wise`,
 * `flow_lm.n_bins`, and whether `flow_lm.bos_before_voice` / `flow_lm.fuser.padding_value`
 * exists in the weights — eliminating any risk of shape or missing-key mismatch at load time.
 */
object CillaLiteConverter {

    private const val MAX_HEADER_BYTES = 16 * 1024 * 1024

    fun convertFile(yamlFile: File, outputJsonFile: File) {
        val weightsFile = File(yamlFile.parentFile, "model.safetensors")
        val header = if (weightsFile.isFile) readSafetensorsHeaderOrNull(weightsFile) else null
        val json = convertYamlToConfigJson(yamlFile.readText(Charsets.UTF_8), header)
        val tmp = File(outputJsonFile.parentFile, "${outputJsonFile.name}.tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        if (!tmp.renameTo(outputJsonFile)) {
            outputJsonFile.writeText(json.toString(2), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun convertYamlToConfigJson(
        yamlText: String,
        safetensorsHeader: JSONObject? = null
    ): JSONObject {
        val values = parseSimpleYaml(yamlText)

        fun getStr(key: String, default: String): String = values[key] ?: default
        fun getInt(key: String, default: Int): Int =
            values[key]?.toDoubleOrNull()?.toInt() ?: default
        fun getOptInt(key: String): Int? {
            val raw = values[key]?.trim() ?: return null
            if (raw.isEmpty() || raw.equals("null", ignoreCase = true) ||
                raw.equals("none", ignoreCase = true) || raw == "~"
            ) return null
            return raw.toDoubleOrNull()?.toInt()
        }
        fun getDouble(key: String, default: Double): Double =
            values[key]?.toDoubleOrNull() ?: default
        fun getBool(key: String, default: Boolean): Boolean =
            when (values[key]?.lowercase()) {
                "true", "yes", "1" -> true
                "false", "no", "0" -> false
                else -> default
            }
        fun getIntList(key: String, default: List<Int>): List<Int> {
            val raw = values[key] ?: return default
            val stripped = raw.trim().removePrefix("[").removeSuffix("]")
            val parsed = stripped.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
            return if (parsed.isNotEmpty()) parsed else default
        }

        val dModel = getInt("flow_lm.transformer.d_model", 1024)
        val hiddenScale = getInt("flow_lm.transformer.hidden_scale", 4)
        val dimFeedforward = getInt("flow_lm.transformer.dim_feedforward", dModel * hiddenScale)
        val numHeads = getInt("flow_lm.transformer.num_heads", 16)
        val numLayers = getInt("flow_lm.transformer.num_layers", 6)
        val maxPeriod = getDouble("flow_lm.transformer.max_period", 10000.0)
        var nBins = getInt("flow_lm.lookup_table.n_bins", 4005)
        val lutDim = getInt("flow_lm.lookup_table.dim", dModel)
        val flowDim = getInt("flow_lm.flow.dim", 512)
        val flowDepth = getInt("flow_lm.flow.depth", 6)
        val flowType = getStr("flow_lm.flow.type", "lsd")
        var insertBosBeforeVoice = getBool("flow_lm.insert_bos_before_voice", false)

        val mimiChannels = getInt("mimi.channels", 1)
        val sampleRate = getInt("mimi.sample_rate", 24000)
        val frameRate = getDouble("mimi.frame_rate", 12.5)
        val seanetDim = getInt("mimi.seanet.dimension", 512)
        val quantDim = getInt("mimi.quantizer.dimension", 32)
        val quantOutDim = getInt("mimi.quantizer.output_dimension", seanetDim)
        // FlowLM latent dimension is ALWAYS mimi.quantizer.dimension (32 in pocket-tts).
        val ldim = quantDim
        // mimi.inner_dim is optional in pocket-tts (null => encoder output stays at seanetDim).
        var innerDim: Int? = getOptInt("mimi.inner_dim")
        var downsampleChannelWise = false

        val nFilters = getInt("mimi.seanet.n_filters", 64)
        val nResLayers = getInt("mimi.seanet.n_residual_layers", 1)
        val ratios = getIntList("mimi.seanet.ratios", listOf(6, 5, 4))
        val kernelSize = getInt("mimi.seanet.kernel_size", 7)
        val lastKernelSize = getInt("mimi.seanet.last_kernel_size", 3)
        val resKernelSize = getInt("mimi.seanet.residual_kernel_size", 3)
        val dilationBase = getInt("mimi.seanet.dilation_base", 2)
        val compress = getInt("mimi.seanet.compress", 2)
        val mimiTrDModel = getInt("mimi.transformer.d_model", 512)
        val mimiTrHeads = getInt("mimi.transformer.num_heads", 8)
        val mimiTrLayers = getInt("mimi.transformer.num_layers", 2)
        val mimiTrLayerScale = getDouble("mimi.transformer.layer_scale", 0.01)
        val mimiTrContext = getInt("mimi.transformer.context", 250)
        val mimiTrMaxPeriod = getDouble("mimi.transformer.max_period", 10000.0)
        val mimiTrFfn = getInt("mimi.transformer.dim_feedforward", 2048)

        // Auto-verify subtle weight-dependent parameters against the safetensors header if present.
        if (safetensorsHeader != null) {
            val hasSinkTensor =
                safetensorsHeader.has("flow_lm.bos_before_voice") ||
                    safetensorsHeader.has("flow_lm.fuser.padding_value") ||
                    safetensorsHeader.has("flow_lm.condition_provider.conditioners.speaker_wavs.sink")
            insertBosBeforeVoice = hasSinkTensor

            (tensorShape(safetensorsHeader, "flow_lm.speaker_proj_weight")
                ?: tensorShape(safetensorsHeader, "flow_lm.condition_provider.conditioners.speaker_wavs.output_proj.weight"))
                ?.let { shape ->
                    if (shape.size == 2 && shape[1] > 0) {
                        innerDim = if (shape[1] != seanetDim) shape[1] else null
                    }
                }

            (tensorShape(safetensorsHeader, "mimi.downsample.conv.conv.weight")
                ?: tensorShape(safetensorsHeader, "mimi.model.downsample.conv.conv.weight"))
                ?.let { shape ->
                    if (shape.size == 3) {
                        downsampleChannelWise = (shape[1] == 1 && shape[0] > 1)
                        if (shape[0] > 0) {
                            innerDim = if (shape[0] != seanetDim) shape[0] else null
                        }
                    }
                }

            (tensorShape(safetensorsHeader, "flow_lm.conditioner.embed.weight")
                ?: tensorShape(safetensorsHeader, "flow_lm.condition_provider.conditioners.transcript_in_segment.embed.weight"))
                ?.let { shape ->
                    if (shape.size == 2 && shape[0] > 1) {
                        nBins = shape[0] - 1
                    }
                }
        }

        val defaultTemp = getDouble("default_temperature", 0.3)
        val eosThreshold = getDouble("eos_threshold", -2.0)

        val flowLmJson = JSONObject()
            .put("d_model", dModel)
            .put("num_heads", numHeads)
            .put("num_layers", numLayers)
            .put("dim_feedforward", dimFeedforward)
            .put("max_period", maxPeriod)
            .put("n_bins", nBins)
            .put("lut_dim", lutDim)
            .put("flow_dim", flowDim)
            .put("flow_depth", flowDepth)
            .put("ldim", ldim)
            .put("flow_type", flowType)
            .put("gelu", "tanh")

        val ratiosArray = JSONArray()
        for (r in ratios) ratiosArray.put(r)

        val mimiJson = JSONObject()
            .put("channels", mimiChannels)
            .put("sample_rate", sampleRate)
            .put("frame_rate", frameRate)
            .put("dimension", seanetDim)
            .put("quantizer_dimension", quantDim)
            .put("quantizer_output_dimension", quantOutDim)
            .put("n_filters", nFilters)
            .put("n_residual_layers", nResLayers)
            .put("ratios", ratiosArray)
            .put("kernel_size", kernelSize)
            .put("last_kernel_size", lastKernelSize)
            .put("residual_kernel_size", resKernelSize)
            .put("dilation_base", dilationBase)
            .put("compress", compress)
            .put("transformer_d_model", mimiTrDModel)
            .put("transformer_num_heads", mimiTrHeads)
            .put("transformer_num_layers", mimiTrLayers)
            .put("transformer_layer_scale", mimiTrLayerScale)
            .put("transformer_context", mimiTrContext)
            .put("transformer_max_period", mimiTrMaxPeriod)
            .put("transformer_dim_feedforward", mimiTrFfn)
            .put("downsample_channel_wise", downsampleChannelWise)
            .put("gelu", "tanh")
        if (innerDim != null) {
            mimiJson.put("inner_dim", innerDim)
        }

        val root = JSONObject()
            .put("flow_lm", flowLmJson)
            .put("mimi", mimiJson)
            .put("lsd_decode_steps", 1)
            .put("eos_threshold", eosThreshold)
            .put("model_id", JSONObject.NULL)
            .put("audio_prompt_min_duration", 1.0)
            .put("audio_prompt_max_duration", 30.0)
            .put("cfg_null_audio_empty", true)
            .put("speaker_mimi", JSONObject.NULL)
            .put("conditioners", JSONArray())
            .put("fuser", JSONObject.NULL)
            .put("voices", JSONArray())
            .put("languages", JSONArray().put("fa"))
            .put("temp", defaultTemp)

        if (insertBosBeforeVoice) {
            root.put(
                "prefix_sinks",
                JSONObject().put(
                    "speaker_wavs",
                    JSONObject().put("dropped_token", false)
                )
            )
        }

        return root
    }

    private fun tensorShape(header: JSONObject, name: String): List<Int>? {
        val arr = header.optJSONObject(name)?.optJSONArray("shape") ?: return null
        return (0 until arr.length()).map { arr.optInt(it) }
    }

    private fun readSafetensorsHeaderOrNull(file: File): JSONObject? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len < 16) return null
                val sizeBytes = ByteArray(8)
                raf.readFully(sizeBytes)
                val headerLen = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).long
                if (headerLen <= 8 || headerLen > MAX_HEADER_BYTES || headerLen > len - 8) {
                    return null
                }
                val bytes = ByteArray(headerLen.toInt())
                raf.readFully(bytes)
                JSONObject(String(bytes, Charsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Minimal indentation-based YAML flattener for `pocket-tts` configs (`a.b.c -> scalar`).
     * Supports comments (`# ...`), quoted scalars, inline lists (`[6, 5, 4]`), and block lists.
     */
    internal fun parseSimpleYaml(yamlText: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val stack = ArrayList<Pair<Int, String>>()
        val listAccum = LinkedHashMap<String, MutableList<String>>()
        var activeListKey: String? = null
        var activeListIndent = -1

        for (rawLine in yamlText.lines()) {
            val cleaned = stripYamlComment(rawLine)
            if (cleaned.isBlank()) continue
            val indent = cleaned.indexOfFirst { it != ' ' && it != '\t' }
            val trimmed = cleaned.trim()

            if (trimmed.startsWith("- ")) {
                val key = activeListKey
                if (key != null && indent >= activeListIndent) {
                    val item = unquote(trimmed.removePrefix("- ").trim())
                    listAccum.getOrPut(key) { mutableListOf() }.add(item)
                }
                continue
            }

            activeListKey = null
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) continue

            val key = trimmed.substring(0, colonIdx).trim()
            val valuePart = trimmed.substring(colonIdx + 1).trim()

            while (stack.isNotEmpty() && stack.last().first >= indent) {
                stack.removeAt(stack.lastIndex)
            }
            val fullKey = if (stack.isEmpty()) key else "${stack.joinToString(".") { it.second }}.$key"

            if (valuePart.isEmpty()) {
                stack.add(indent to key)
                activeListKey = fullKey
                activeListIndent = indent
            } else {
                result[fullKey] = unquote(valuePart)
            }
        }

        for ((k, items) in listAccum) {
            if (items.isNotEmpty() && k !in result) {
                result[k] = "[${items.joinToString(", ")}]"
            }
        }
        return result
    }

    private fun stripYamlComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val ch = line[i]
            when {
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch == '#' && !inSingle && !inDouble -> {
                    if (i == 0 || line[i - 1].isWhitespace()) {
                        return line.substring(0, i)
                    }
                }
            }
        }
        return line
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            if ((value.startsWith('"') && value.endsWith('"')) ||
                (value.startsWith('\'') && value.endsWith('\''))
            ) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }
}
