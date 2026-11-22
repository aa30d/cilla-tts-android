package com.cilla.tts

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cilla.tts.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two tabs: reading, which is what the app is for, and settings, where the models and voices
 * live. The playback and save buttons sit right under the text they act on, and the two voice
 * pickers are separate because the checkpoints share no voices.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engines: TtsEngines
    private val executor = Executors.newSingleThreadExecutor()
    private val stopRequested = AtomicBoolean(false)
    private var playing = false
    private var busy = false
    private var pendingWavText: String? = null

    private val pickPersianModel =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importModel(ModelKind.PERSIAN, uri)
        }

    private val pickEnglishModel =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importModel(ModelKind.ENGLISH, uri)
        }

    private val pickVoices =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) addVoices(uri)
        }

    private val pickDownloadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val text = pendingWavText
            pendingWavText = null
            if (granted && text != null) saveWav(text) else setStatus("اجازه نوشتن در حافظه داده نشد.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Os.setenv("RAYON_NUM_THREADS", "2", true)
        engines = TtsEngines(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpTabs()
        setUpReadingControls()
        setUpSettingsControls()

        refreshModelState()
        updatePlayButton()
        if (!engines.hasPersianModel() && !engines.hasEnglishModel()) {
            setStatus("مدلی نصب نیست. از تب «تنظیمات» مدل فارسی یا انگلیسی را دانلود یا انتخاب کنید.")
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun setUpTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("خواندن"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("تنظیمات"))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        showTab(0)
    }

    private fun showTab(position: Int) {
        binding.readTab.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.settingsTab.visibility = if (position == 1) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- reading tab

    private fun setUpReadingControls() {
        val savedTemperature = TtsPrefs.getTemperature(this)
        binding.tempSlider.value = savedTemperature.coerceIn(0f, 1.5f)
        showTemperature(savedTemperature)
        binding.tempSlider.addOnChangeListener { _, value, _ ->
            showTemperature(value)
            TtsPrefs.setTemperature(this, value)
        }

        val randomSeed = TtsPrefs.isRandomSeed(this)
        binding.randomSeedCheckbox.isChecked = randomSeed
        binding.seedInput.isEnabled = !randomSeed
        binding.seedInput.setText(TtsPrefs.getSeed(this).toString())
        binding.randomSeedCheckbox.setOnCheckedChangeListener { _, isChecked ->
            TtsPrefs.setRandomSeed(this, isChecked)
            binding.seedInput.isEnabled = !isChecked
        }
        binding.seedInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.toLongOrNull()?.let { TtsPrefs.setSeed(this@MainActivity, it) }
            }
        })

        binding.playStopButton.setOnClickListener {
            if (playing) stopSpeaking() else speak()
        }
        binding.saveWavButton.setOnClickListener { requestSaveWav() }
    }

    private fun showTemperature(value: Float) {
        binding.tempLabel.text = String.format(Locale.US, "دما (Temperature): %.2f", value)
    }

    private fun speak() {
        if (busy) return
        val text = binding.inputText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            setStatus("متنی برای خواندن وارد کنید.")
            return
        }
        val withEnglish = engines.hasEnglishModel()
        val segments = TextCleaner.splitSegments(text, withEnglish)
        if (segments.isEmpty()) {
            setStatus("متنی برای خواندن پیدا نشد.")
            return
        }
        val temperature = binding.tempSlider.value
        val seed = TtsPrefs.resolveSeed(this)

        stopRequested.set(false)
        playing = true
        binding.metricsText.text = ""
        updatePlayButton()
        setStatus(
            if (!withEnglish && segments.any { it.isEnglish }) {
                "در حال تولید صدا... (مدل انگلیسی نصب نیست؛ متن انگلیسی با مدل فارسی خوانده می‌شود)"
            } else {
                "در حال تولید صدا..."
            }
        )

        executor.execute {
            val track = createAudioTrack()
            var failure: String? = null
            var last: PhononTTS.Result? = null
            try {
                track.play()
                val sink: (FloatArray) -> Boolean = { pcm ->
                    if (stopRequested.get()) {
                        false
                    } else {
                        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        !stopRequested.get()
                    }
                }
                last = engines.speak(segments, temperature, seed, sink)
            } catch (e: Exception) {
                failure = e.message
            } finally {
                try {
                    track.stop()
                } catch (_: IllegalStateException) {
                    // Stopping a track that never started is not worth a crash.
                }
                track.release()
                val stopped = stopRequested.get()
                val result = last
                runOnUiThread {
                    playing = false
                    updatePlayButton()
                    when {
                        failure != null -> setStatus("خطا: $failure")
                        stopped -> setStatus("پخش متوقف شد.")
                        else -> setStatus("انجام شد.")
                    }
                    if (result != null) {
                        binding.metricsText.text = String.format(
                            Locale.US,
                            "زمان تا اولین صدا: %.0f ms | زمان کل: %.0f ms | Seed: %d",
                            result.ttfaMs,
                            result.totalMs,
                            seed
                        )
                    }
                }
            }
        }
    }

    private fun stopSpeaking() {
        stopRequested.set(true)
        playing = false
        updatePlayButton()
    }

    private fun requestSaveWav() {
        val text = binding.inputText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            setStatus("متنی برای ذخیره وارد کنید.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingWavText = text
            pickDownloadPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveWav(text)
    }

    private fun saveWav(text: String) {
        if (busy) return
        val withEnglish = engines.hasEnglishModel()
        val segments = TextCleaner.splitSegments(text, withEnglish)
        if (segments.isEmpty()) {
            setStatus("متنی برای ذخیره پیدا نشد.")
            return
        }
        val temperature = binding.tempSlider.value
        val seed = TtsPrefs.resolveSeed(this)
        stopRequested.set(false)
        playing = true
        updatePlayButton()
        setStatus("در حال تولید و ذخیره فایل صوتی...")

        executor.execute {
            var failure: String? = null
            var savedAs: String? = null
            try {
                val chunks = mutableListOf<FloatArray>()
                var total = 0
                engines.speak(segments, temperature, seed) { pcm ->
                    if (stopRequested.get()) {
                        false
                    } else {
                        chunks += pcm.copyOf()
                        total += pcm.size
                        true
                    }
                }
                val samples = FloatArray(total)
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(samples, offset)
                    offset += chunk.size
                }
                savedAs = writeWavToDownloads(samples)
            } catch (e: Exception) {
                failure = e.message
            } finally {
                val name = savedAs
                val error = failure
                runOnUiThread {
                    playing = false
                    updatePlayButton()
                    when {
                        error != null -> setStatus("خطا در ذخیره فایل: $error")
                        name != null -> setStatus("فایل صوتی ذخیره شد: Downloads/$name")
                        else -> setStatus("ذخیره فایل صوتی انجام نشد.")
                    }
                }
            }
        }
    }

    private fun writeWavToDownloads(samples: FloatArray): String {
        val fileName = "cilla_tts_${System.currentTimeMillis()}.wav"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("ایجاد فایل در دانلودها ممکن نشد")
            contentResolver.openOutputStream(uri)?.use { output ->
                WavWriter.writePcmFloatAsWav(output, samples)
            } ?: throw IllegalStateException("نوشتن فایل در دانلودها ممکن نشد")
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            FileOutputStream(File(dir, fileName)).use { output ->
                WavWriter.writePcmFloatAsWav(output, samples)
            }
        }
        return fileName
    }

    private fun createAudioTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            24000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer.coerceAtLeast(8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ---------------------------------------------------------------- settings tab

    private fun setUpSettingsControls() {
        binding.downloadFaButton.setOnClickListener { downloadModel(ModelKind.PERSIAN) }
        binding.downloadEnButton.setOnClickListener { downloadModel(ModelKind.ENGLISH) }
        binding.selectFaFolderButton.setOnClickListener { pickPersianModel.launch(null) }
        binding.selectEnFolderButton.setOnClickListener { pickEnglishModel.launch(null) }
        binding.importVoicesButton.setOnClickListener { pickVoices.launch(null) }
        binding.deleteVoicesButton.setOnClickListener { confirmDeleteVoices() }
        binding.deleteModelsButton.setOnClickListener { confirmDeleteModels() }
    }

    private fun downloadModel(kind: ModelKind) {
        if (busy) return
        stopSpeaking()
        runTask("در حال آماده‌سازی دانلود مدل ${kind.label}...") {
            engines.close(kind)
            val progress = ModelDownloader.Progress(
                onMessage = { message -> postStatus(message) },
                onFraction = { fraction -> runOnUiThread { showDownloadProgress(fraction) } }
            )
            val report = if (kind == ModelKind.PERSIAN) {
                ModelDownloader.installPersian(this, progress)
            } else {
                ModelDownloader.installEnglish(this, progress)
            }
            buildReport("مدل ${kind.label} نصب و آماده شد.", report)
        }
    }

    private fun importModel(kind: ModelKind, uri: Uri) {
        if (busy) return
        stopSpeaking()
        runTask("در حال کپی مدل ${kind.label}...") {
            engines.close(kind)
            val report = ModelStorage.importModel(this, kind, uri) { message -> postStatus(message) }
            buildReport("مدل ${kind.label} از پوشه انتخاب‌شده نصب شد.", report)
        }
    }

    private fun addVoices(uri: Uri) {
        if (busy) return
        stopSpeaking()
        runTask("در حال افزودن صداها...") {
            engines.close()
            val summary = ModelStorage.importVoices(this, uri) { message -> postStatus(message) }
            val builder = StringBuilder("صداها اضافه شدند: ${summary.persian} برای فارسی، ${summary.english} برای انگلیسی.")
            if (summary.skipped.isNotEmpty()) {
                builder.append(" ${summary.skipped.size} فایل کنار گذاشته شد.")
                summary.skipped.take(2).forEach { (name, reason) ->
                    builder.append(" ($name: $reason)")
                }
            }
            builder.toString()
        }
    }

    private fun buildReport(prefix: String, report: VoiceReport): String {
        val builder = StringBuilder(prefix)
        builder.append(" ${report.usable.size} گوینده آماده است.")
        if (report.unsupported.isNotEmpty()) {
            builder.append(" ${report.unsupported.size} صدای ناسازگار کنار گذاشته شد: ")
            builder.append(
                report.unsupported.take(2).joinToString("، ") { "${it.first} (${it.second})" }
            )
        }
        return builder.toString()
    }

    private fun confirmDeleteVoices() {
        AlertDialog.Builder(this)
            .setTitle("حذف صداها")
            .setMessage("همه فایل‌های صدا از حافظه برنامه حذف شوند؟ مدل‌ها می‌مانند.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف") { _, _ ->
                stopSpeaking()
                runTask("در حال حذف صداها...") {
                    engines.close()
                    val removed = ModelStorage.deleteVoices(this)
                    "صداها حذف شدند ($removed فایل)."
                }
            }
            .show()
    }

    private fun confirmDeleteModels() {
        AlertDialog.Builder(this)
            .setTitle("حذف مدل‌ها")
            .setMessage(
                "مدل‌ها و صداهای ذخیره‌شده در حافظه داخلی برنامه حذف شوند؟ " +
                    "برای استفاده دوباره باید مدل را از نو دانلود یا انتخاب کنید."
            )
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف") { _, _ ->
                stopSpeaking()
                runTask("در حال حذف مدل‌ها...") {
                    engines.close()
                    ModelStorage.deleteModels(this)
                    "مدل‌ها و صداها از حافظه برنامه حذف شدند."
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- shared

    /** Runs [block] off the main thread and shows what it returns in the status line. */
    private fun runTask(message: String, block: () -> String) {
        busy = true
        updatePlayButton()
        setStatus(message)
        binding.progressBar.isIndeterminate = true
        binding.progressBar.visibility = View.VISIBLE
        executor.execute {
            val result = try {
                block()
            } catch (e: Exception) {
                "خطا: ${e.message}"
            }
            runOnUiThread {
                busy = false
                binding.progressBar.visibility = View.GONE
                binding.progressBar.isIndeterminate = false
                refreshModelState()
                updatePlayButton()
                setStatus(result)
            }
        }
    }

    private fun showDownloadProgress(fraction: Int?) {
        if (fraction == null) {
            binding.progressBar.isIndeterminate = true
        } else {
            binding.progressBar.isIndeterminate = false
            binding.progressBar.progress = fraction
        }
    }

    private fun postStatus(message: String) {
        runOnUiThread { setStatus(message) }
    }

    private fun setStatus(message: String) {
        binding.statusText.text = message
    }

    private fun refreshModelState() {
        binding.modelFaStatus.text = describe(ModelKind.PERSIAN)
        binding.modelEnStatus.text = describe(ModelKind.ENGLISH)
        val used = ModelStorage.sizeOf(ModelStorage.modelDir(this, ModelKind.PERSIAN)) +
            ModelStorage.sizeOf(ModelStorage.modelDir(this, ModelKind.ENGLISH))
        binding.storageText.text = "حجم اشغال‌شده در حافظه برنامه: ${ModelStorage.formatSize(used)}"
        populateVoices()
    }

    private fun describe(kind: ModelKind): String {
        val dir = ModelStorage.modelDir(this, kind)
        if (!dir.exists()) return "نصب نشده"
        if (!ModelStorage.hasCoreFiles(dir)) return "فایل‌های مدل ناقص است"
        val voices = ModelStorage.voiceNames(dir)
        if (voices.isEmpty()) return "وزن مدل هست ولی هیچ صدایی ندارد"
        return "آماده — ${voices.size} گوینده (${voices.joinToString("، ")}) — " +
            ModelStorage.formatSize(ModelStorage.sizeOf(dir))
    }

    private fun populateVoices() {
        bindVoices(
            binding.voiceFaSpinner,
            ModelKind.PERSIAN,
            TtsPrefs.getPersianVoice(this)
        ) { TtsPrefs.setPersianVoice(this, it) }
        bindVoices(
            binding.voiceEnSpinner,
            ModelKind.ENGLISH,
            TtsPrefs.getEnglishVoice(this)
        ) { TtsPrefs.setEnglishVoice(this, it) }
    }

    private fun bindVoices(spinner: Spinner, kind: ModelKind, selected: String?, onPick: (String) -> Unit) {
        val voices = engines.voices(kind)
        val items = if (voices.isEmpty()) listOf("—") else voices
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        val chosen = selected?.let { voices.indexOf(it) } ?: -1
        spinner.setSelection(chosen.takeIf { it >= 0 } ?: 0)
        spinner.isEnabled = voices.isNotEmpty() && !busy
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                voices.getOrNull(position)?.let(onPick)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun updatePlayButton() {
        val hasModel = engines.hasPersianModel() || engines.hasEnglishModel()
        if (playing) {
            binding.playStopButton.text = "■ توقف"
            binding.playStopButton.isEnabled = true
        } else {
            binding.playStopButton.text = "▶ پخش صدا"
            binding.playStopButton.isEnabled = hasModel && !busy
        }
        binding.saveWavButton.isEnabled = hasModel && !busy && !playing
        binding.downloadFaButton.isEnabled = !busy
        binding.downloadEnButton.isEnabled = !busy
        binding.selectFaFolderButton.isEnabled = !busy
        binding.selectEnFolderButton.isEnabled = !busy
        binding.importVoicesButton.isEnabled = !busy
        binding.deleteVoicesButton.isEnabled = !busy
        binding.deleteModelsButton.isEnabled = !busy
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRequested.set(true)
        if (::engines.isInitialized) {
            executor.execute { engines.close() }
        }
        executor.shutdown()
    }
}
