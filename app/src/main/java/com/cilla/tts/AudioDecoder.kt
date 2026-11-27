package com.cilla.tts

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decodes WAV or any Android-supported audio file (MP3, M4A, OGG, Opus, FLAC, etc.) to
 * 24 kHz mono float PCM (`[-1, 1]`) for on-device voice cloning, and provides a hardware-safe
 * microphone recorder (`VoiceRecorder`) with automatic sample-rate fallback.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 24000
    const val MIN_PROMPT_SECONDS = 1.0f

    /**
     * A checkpoint's `audio_prompt_*_duration` held to what a person can record, matching
     * `prompt_bounds` in the native patch (`native/xn-ptts.patch`, `cpu.rs`). Cilla Mini's config
     * says 10 s for both, which is its training prompt length, not a recording requirement; the
     * native clone uses these clamped values, so the trim here must too.
     */
    fun clampPromptBounds(minSeconds: Double, maxSeconds: Double): Pair<Float, Float> {
        val min = minSeconds.toFloat().coerceIn(0.5f, 10f)
        val max = maxSeconds.toFloat().coerceIn(5f, 30f).coerceAtLeast(min)
        return min to max
    }
    const val MAX_PROMPT_SECONDS = 30.0f

    data class DecodedAudio(
        val samples: FloatArray,
        val sampleRate: Int
    )

    fun decodeUriToMono24k(context: Context, uri: Uri): FloatArray {
        val tmp = File(context.cacheDir, "clone_input_${System.currentTimeMillis()}.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("فایل صوتی قابل خواندن نیست")
            input.use { src ->
                FileOutputStream(tmp).use { dst -> src.copyTo(dst) }
            }
            return decodeFileToMono24k(tmp)
        } finally {
            tmp.delete()
        }
    }

    fun decodeFileToMono24k(file: File): FloatArray {
        if (!file.isFile || file.length() < 44) {
            throw IllegalArgumentException("فایل صوتی خالی یا نامعتبر است")
        }
        val decoded = tryDecodeWav(file) ?: decodeWithMediaCodec(file)
        val mono24k = resampleTo24k(decoded.samples, decoded.sampleRate)
        return validateAndTrimPrompt(mono24k, TARGET_SAMPLE_RATE)
    }

    /**
     * Check that [pcm] is long enough (and audible) and cut it to [maxSeconds].
     *
     * [minSeconds] / [maxSeconds] default to this file's own bounds; callers that know the
     * checkpoint's `audio_prompt_*_duration` pass those instead, so a prompt is trimmed exactly as
     * the native encoder would trim it and the two never disagree about what is too short.
     */
    fun validateAndTrimPrompt(
        pcm: FloatArray,
        sampleRate: Int = TARGET_SAMPLE_RATE,
        minSeconds: Float = MIN_PROMPT_SECONDS,
        maxSeconds: Float = MAX_PROMPT_SECONDS
    ): FloatArray {
        val (minSeconds, maxSeconds) = clampPromptBounds(minSeconds.toDouble(), maxSeconds.toDouble())
        val minSamples = (sampleRate * minSeconds).toInt()
        if (pcm.size < minSamples) {
            val secs = pcm.size.toFloat() / sampleRate
            throw IllegalArgumentException(
                String.format(
                    Locale.US,
                    "طول صدای نمونه خیلی کوتاه است (%.1f ثانیه)؛ حداقل %.1f ثانیه لازم است",
                    secs,
                    minSeconds
                )
            )
        }
        val maxSamples = (sampleRate * maxSeconds).toInt()
        val trimmed = if (pcm.size > maxSamples) pcm.copyOf(maxSamples) else pcm

        var sumSq = 0.0
        for (s in trimmed) sumSq += s.toDouble() * s.toDouble()
        val rms = sqrt(sumSq / trimmed.size.toDouble())
        if (rms < 1e-4) {
            throw IllegalArgumentException("فایل صوتی تقریباً بی‌صدا است")
        }
        return trimmed
    }

    /**
     * Pure-Kotlin RIFF/WAVE parser supporting PCM 8/16/24/32-bit int and 32-bit IEEE float.
     * Returns null if the file is not an uncompressed WAV so MediaCodec can handle it.
     */
    internal fun tryDecodeWav(file: File): DecodedAudio? {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte() ||
            bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
            bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()
        ) {
            return null
        }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var audioFormat = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(pos + 4)
            pos += 8
            if (chunkSize < 0 || pos + chunkSize > bytes.size) {
                if (id == "data" && pos < bytes.size) {
                    dataOffset = pos
                    dataSize = bytes.size - pos
                }
                break
            }
            when (id) {
                "fmt " -> {
                    if (chunkSize < 16) return null
                    audioFormat = bb.getShort(pos).toInt() and 0xFFFF
                    channels = bb.getShort(pos + 2).toInt() and 0xFFFF
                    sampleRate = bb.getInt(pos + 4)
                    bitsPerSample = bb.getShort(pos + 14).toInt() and 0xFFFF
                    // WAVE_FORMAT_EXTENSIBLE (0xFFFE): read SubFormat GUID's first 2 bytes.
                    if (audioFormat == 0xFFFE && chunkSize >= 40) {
                        audioFormat = bb.getShort(pos + 24).toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataOffset = pos
                    dataSize = chunkSize
                    break
                }
            }
            pos += chunkSize + (chunkSize and 1)
        }

        // Only handle PCM (1) and IEEE float (3) directly; delegate compressed WAV to MediaCodec.
        if (audioFormat != 1 && audioFormat != 3) return null
        if (channels <= 0 || sampleRate <= 0 || dataOffset < 0 || dataSize <= 0) return null

        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) return null
        val frameBytes = bytesPerSample * channels
        val numFrames = dataSize / frameBytes
        if (numFrames <= 0) return null

        val out = FloatArray(numFrames)
        val invChannels = 1f / channels.toFloat()
        var cursor = dataOffset
        for (f in 0 until numFrames) {
            var sum = 0f
            for (c in 0 until channels) {
                val sample = when {
                    audioFormat == 3 && bitsPerSample == 32 -> bb.getFloat(cursor)
                    audioFormat == 1 && bitsPerSample == 8 -> ((bytes[cursor].toInt() and 0xFF) - 128) / 128f
                    audioFormat == 1 && bitsPerSample == 16 -> bb.getShort(cursor) / 32768f
                    audioFormat == 1 && bitsPerSample == 24 -> {
                        val b0 = bytes[cursor].toInt() and 0xFF
                        val b1 = bytes[cursor + 1].toInt() and 0xFF
                        val b2 = bytes[cursor + 2].toInt() // signed
                        val v = (b2 shl 16) or (b1 shl 8) or b0
                        v / 8388608f
                    }
                    audioFormat == 1 && bitsPerSample == 32 -> (bb.getInt(cursor) / 2147483648.0).toFloat()
                    else -> return null
                }
                sum += sample
                cursor += bytesPerSample
            }
            out[f] = (sum * invChannels).coerceIn(-1f, 1f)
        }
        return DecodedAudio(out, sampleRate)
    }

    private fun decodeWithMediaCodec(file: File): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                throw IllegalArgumentException("هیچ مسیر صوتی در این فایل پیدا نشد")
            }
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("فرمت صوتی نامشخص است")

            extractor.selectTrack(trackIndex)
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = if (trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val maxSecondsToDecode = MAX_PROMPT_SECONDS + 2f
            val chunks = ArrayList<FloatArray>()
            var totalSamples = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val read = extractor.readSampleData(inBuf, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val mono = pcmBufferToMonoFloat(outBuf, channels, pcmEncoding)
                            if (mono.isNotEmpty()) {
                                chunks.add(mono)
                                totalSamples += mono.size
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ||
                            totalSamples >= (sampleRate * maxSecondsToDecode).toInt()
                        ) {
                            outputDone = true
                        }
                    }
                }
            }

            if (totalSamples == 0) {
                throw IllegalArgumentException("خروجی صوتی از فایل استخراج نشد")
            }
            val merged = FloatArray(totalSamples)
            var offset = 0
            for (c in chunks) {
                c.copyInto(merged, offset)
                offset += c.size
            }
            return DecodedAudio(merged, sampleRate)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun pcmBufferToMonoFloat(
        buffer: ByteBuffer,
        channels: Int,
        pcmEncoding: Int
    ): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val bb = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val invCh = 1f / ch.toFloat()
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = bb.asFloatBuffer()
                val frames = fb.remaining() / ch
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += fb.get()
                    out[i] = (sum * invCh).coerceIn(-1f, 1f)
                }
                out
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = bb.remaining() / ch
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += ((bb.get().toInt() and 0xFF) - 128) / 128f
                    out[i] = (sum * invCh).coerceIn(-1f, 1f)
                }
                out
            }
            else -> {
                // ENCODING_PCM_16BIT (standard Android decoder output)
                val sb = bb.asShortBuffer()
                val frames = sb.remaining() / ch
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += sb.get() / 32768f
                    out[i] = (sum * invCh).coerceIn(-1f, 1f)
                }
                out
            }
        }
    }

    /**
     * Hann-windowed sinc resampler to 24 kHz with automatic anti-aliasing low-pass cutoff when
     * downsampling (e.g. 48/44.1 kHz -> 24 kHz).
     */
    fun resampleTo24k(input: FloatArray, srcRate: Int): FloatArray {
        if (srcRate == TARGET_SAMPLE_RATE || input.isEmpty()) return input
        val ratio = TARGET_SAMPLE_RATE.toDouble() / srcRate.toDouble()
        val outLen = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)

        val cutoff = min(1.0, ratio)
        val halfTaps = 12
        val radius = if (cutoff < 1.0) (halfTaps / cutoff).toInt() else halfTaps

        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val center = floor(srcPos).toInt()
            val start = (center - radius).coerceAtLeast(0)
            val end = (center + radius).coerceAtMost(input.size - 1)
            var acc = 0.0
            var weightSum = 0.0
            for (j in start..end) {
                val dx = j - srcPos
                val u = dx / radius.toDouble()
                if (u <= -1.0 || u >= 1.0) continue
                val window = 0.5 * (1.0 + cos(PI * u))
                val x = dx * cutoff
                val sinc = if (kotlin.math.abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                val w = sinc * window
                acc += input[j] * w
                weightSum += w
            }
            out[i] = if (weightSum > 1e-9) (acc / weightSum).toFloat().coerceIn(-1f, 1f) else 0f
        }
        return out
    }
}

/**
 * Simple microphone voice-prompt recorder that tries 24 kHz first and automatically falls back to
 * standard hardware rates (48 / 44.1 / 16 kHz) before resampling to 24 kHz mono float PCM.
 */
class VoiceRecorder {

    private val recording = AtomicBoolean(false)
    private var worker: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    private var actualRate = AudioDecoder.TARGET_SAMPLE_RATE
    @Volatile
    private var recordedSamples = 0

    val isRecording: Boolean get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start(
        maxSeconds: Float = 15f,
        onTick: (Float) -> Unit,
        onMaxReached: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!recording.compareAndSet(false, true)) return
        chunks.clear()
        recordedSamples = 0

        val candidateRates = intArrayOf(24000, 48000, 44100, 16000)
        var record: AudioRecord? = null
        var chosenRate = 0
        var chosenBuf = 0
        for (rate in candidateRates) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) continue
            val bufBytes = (minBuf * 2).coerceAtLeast(rate / 5 * 2)
            val candidate = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes
                )
            } catch (_: Exception) {
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
                chosenRate = rate
                chosenBuf = bufBytes
                break
            } else {
                candidate?.release()
            }
        }

        if (record == null) {
            recording.set(false)
            onError("میکروفون دستگاه آماده نشد")
            return
        }

        actualRate = chosenRate
        val maxSamples = (chosenRate * maxSeconds).toInt()
        val ar = record
        worker = Thread({
            val buf = ShortArray((chosenBuf / 2).coerceAtLeast(1024))
            var hitMax = false
            try {
                ar.startRecording()
                while (recording.get()) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        synchronized(chunks) { chunks.add(copy) }
                        recordedSamples += n
                        onTick(recordedSamples.toFloat() / chosenRate.toFloat())
                        if (recordedSamples >= maxSamples) {
                            hitMax = true
                            recording.set(false)
                            break
                        }
                    } else if (n < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                recording.set(false)
                onError(e.message ?: "خطا هنگام ضبط صدا")
            } finally {
                try { ar.stop() } catch (_: Exception) {}
                try { ar.release() } catch (_: Exception) {}
                if (hitMax) onMaxReached()
            }
        }, "voice-recorder").apply { start() }
    }

    /** Stops recording and returns mono 24 kHz float PCM. */
    fun stop(): FloatArray {
        recording.set(false)
        try { worker?.join(2000) } catch (_: InterruptedException) {}
        worker = null

        val snap = synchronized(chunks) { chunks.toList() }
        val total = snap.sumOf { it.size }
        if (total == 0) return FloatArray(0)
        val raw = FloatArray(total)
        var offset = 0
        for (c in snap) {
            for (s in c) raw[offset++] = s / 32768f
        }
        return AudioDecoder.resampleTo24k(raw, actualRate)
    }

    fun cancel() {
        recording.set(false)
        try { worker?.join(1000) } catch (_: InterruptedException) {}
        worker = null
        synchronized(chunks) { chunks.clear() }
    }
}
