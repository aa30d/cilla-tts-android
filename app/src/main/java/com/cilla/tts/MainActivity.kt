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
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.cilla.tts.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two tabs: reading (synthesis + WAV export + active Persian model & voice pickers) and settings
 * (Cilla Mini, Cilla Lite 100M, on-device voice cloning, Phonon 31M + Phonon Voices browser).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engines: TtsEngines
    private val executor = Executors.newSingleThreadExecutor()
    private val stopRequested = AtomicBoolean(false)
    private var playing = false
    private var busy = false
    private var suppressUiEvents = false
    private var pendingWavText: String? = null
    private var pendingCloneTarget: ModelKind? = null

    private val pickPersianModel =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importModel(ModelKind.PERSIAN, uri)
        }

    private val pickLiteModel =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importModel(ModelKind.PERSIAN_LITE, uri)
        }

    private val pickEnglishModel =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importModel(ModelKind.ENGLISH, uri)
        }

    private val pickVoices =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) addVoices(uri)
        }

    private val pickCloneAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) promptCloneFromUri(uri)
        }

    private val pickDownloadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val text = pendingWavText
            pendingWavText = null
            if (granted && text != null) saveWav(text) else setStatus("اجازه نوشتن در حافظه داده نشد.")
        }

    private val pickRecordPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val target = pendingCloneTarget
            pendingCloneTarget = null
            if (granted && target != null) {
                showRecordCloneDialog(target)
            } else {
                setStatus("اجازه دسترسی به میکروفون داده نشد.")
            }
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
        val missingNative = PhononTTS.missingFfiFeatures
        if (missingNative.isNotEmpty()) {
            setStatus(
                "کتابخانه بومی این APK با این نسخه از برنامه هم‌خوان نیست " +
                    "(نمادهای ناموجود: ${missingNative.joinToString()}). " +
                    "APK را با native/build-ptts-ffi.sh یا از GitHub Actions بسازید."
            )
        } else if (!engines.hasAnyPersianModel() && !engines.hasEnglishModel()) {
            setStatus("مدلی نصب نیست. از تب «تنظیمات» سیلا مینی، سیلا لایت یا مدل انگلیسی را نصب کنید.")
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
        binding.persianModelGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressUiEvents) return@setOnCheckedChangeListener
            val chosen = if (checkedId == R.id.radioCillaLite) {
                ModelKind.PERSIAN_LITE
            } else {
                ModelKind.PERSIAN
            }
            TtsPrefs.setActivePersianKind(this, chosen)
            syncTemperatureForActiveModel()
            populateVoices()
            updateCloneSection()
            updatePlayButton()
        }

        syncTemperatureForActiveModel()
        binding.tempSlider.addOnChangeListener { _, value, _ ->
            if (suppressUiEvents) return@addOnChangeListener
            val activeKind = TtsPrefs.getActivePersianKind(this)
            showTemperature(value, activeKind)
            TtsPrefs.setTemperature(this, value, activeKind)
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

    private fun syncTemperatureForActiveModel() {
        val activeKind = TtsPrefs.getActivePersianKind(this)
        val saved = TtsPrefs.getTemperature(this, activeKind).coerceIn(0f, 1.5f)
        suppressUiEvents = true
        binding.tempSlider.value = saved
        suppressUiEvents = false
        showTemperature(saved, activeKind)
    }

    private fun showTemperature(value: Float, activeKind: ModelKind) {
        val hint = if (activeKind == ModelKind.PERSIAN_LITE) "پیشنهادی: 0.30" else "پیشنهادی: 0.70"
        binding.tempLabel.text = String.format(Locale.US, "دما (Temperature): %.2f (%s)", value, hint)
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
        val activeFa = engines.activePersianKind()

        stopRequested.set(false)
        playing = true
        binding.metricsText.text = ""
        updatePlayButton()
        setStatus(
            if (!withEnglish && segments.any { it.isEnglish }) {
                "در حال تولید صدا (${activeFa.label})... (مدل انگلیسی نصب نیست)"
            } else {
                "در حال تولید صدا (${activeFa.label})..."
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
                last = engines.speak(segments, temperature, seed, exactPostProcess = false, sink = sink)
            } catch (e: Exception) {
                failure = e.message
            } finally {
                try {
                    track.stop()
                } catch (_: IllegalStateException) {
                    // Stopping a track that never started is harmless.
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
                engines.speak(segments, temperature, seed, exactPostProcess = true) { pcm ->
                    if (stopRequested.get()) {
                        false
                    } else {
                        chunks += pcm.copyOf()
                        total += pcm.size
                        true
                    }
                }
                if (!stopRequested.get() && total > 0) {
                    val samples = FloatArray(total)
                    var offset = 0
                    for (chunk in chunks) {
                        chunk.copyInto(samples, offset)
                        offset += chunk.size
                    }
                    savedAs = writeWavToDownloads(samples)
                }
            } catch (e: Exception) {
                failure = e.message
            } finally {
                val name = savedAs
                val error = failure
                val stopped = stopRequested.get()
                runOnUiThread {
                    playing = false
                    updatePlayButton()
                    when {
                        error != null -> setStatus("خطا در ذخیره فایل: $error")
                        stopped -> setStatus("ذخیره فایل متوقف شد.")
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
        binding.selectFaFolderButton.setOnClickListener { pickPersianModel.launch(null) }

        binding.downloadLiteButton.setOnClickListener { promptDownloadCillaLite() }
        binding.selectLiteFolderButton.setOnClickListener { pickLiteModel.launch(null) }

        binding.cloneRecordButton.setOnClickListener { requestRecordCloneVoice() }
        binding.clonePickFileButton.setOnClickListener { requestPickCloneAudio() }

        binding.downloadEnButton.setOnClickListener { downloadModel(ModelKind.ENGLISH) }
        binding.downloadPhononVoicesButton.setOnClickListener { openPhononVoicesBrowser() }
        binding.selectEnFolderButton.setOnClickListener { pickEnglishModel.launch(null) }

        binding.importVoicesButton.setOnClickListener { pickVoices.launch(null) }
        binding.deleteVoicesButton.setOnClickListener { confirmDeleteVoices() }
        binding.deleteModelsButton.setOnClickListener { confirmDeleteModels() }
    }

    private fun downloadModel(kind: ModelKind, hfToken: String? = null) {
        if (busy) return
        stopSpeaking()
        runTask("در حال آماده‌سازی دانلود مدل ${kind.label}...") {
            engines.close(kind)
            val progress = ModelDownloader.Progress(
                onMessage = { message -> postStatus(message) },
                onFraction = { fraction -> runOnUiThread { showDownloadProgress(fraction) } }
            )
            val report = when (kind) {
                ModelKind.PERSIAN -> ModelDownloader.installPersian(this, progress)
                ModelKind.PERSIAN_LITE -> {
                    val r = ModelDownloader.installCillaLite(this, hfToken, progress)
                    TtsPrefs.setActivePersianKind(this, ModelKind.PERSIAN_LITE)
                    r
                }
                ModelKind.ENGLISH -> ModelDownloader.installEnglish(this, progress)
            }
            if (kind == ModelKind.PERSIAN_LITE && report.usable.isEmpty()) {
                "مدل سیلا لایت دانلود و تبدیل شد. برای استفاده، یک صدا کلون کنید یا فایل صوتی نمونه بدهید."
            } else {
                buildReport("مدل ${kind.label} نصب و آماده شد.", report)
            }
        }
    }

    private fun promptDownloadCillaLite() {
        if (busy) return
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12), pad, 0)
        }
        val info = TextView(this).apply {
            text = "مخزن mehdi-hf/cilla-lite روی Hugging Face دارای محدودیت دسترسی (Gated) است.\n" +
                "۱) در صفحه مدل روی Hugging Face دکمه تأیید دسترسی را بزنید.\n" +
                "۲) توکن خواندن (Read Token) خود را وارد کنید:"
            textSize = 13f
        }
        val tokenInput = EditText(this).apply {
            hint = "hf_xxxxxxxxxxxxxxxxxxxxxxxx"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(TtsPrefs.getHfToken(this@MainActivity))
            setSelection(text.length)
        }
        container.addView(info)
        container.addView(tokenInput)

        AlertDialog.Builder(this)
            .setTitle("دانلود سیلا لایت (Cilla Lite 100M)")
            .setView(container)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("شروع دانلود") { _, _ ->
                val token = tokenInput.text?.toString()?.trim().orEmpty()
                if (token.isNotEmpty()) TtsPrefs.setHfToken(this, token)
                downloadModel(ModelKind.PERSIAN_LITE, token)
            }
            .show()
    }

    private fun importModel(kind: ModelKind, uri: Uri) {
        if (busy) return
        stopSpeaking()
        runTask("در حال کپی مدل ${kind.label}...") {
            engines.close(kind)
            val report = ModelStorage.importModel(this, kind, uri) { message -> postStatus(message) }
            if (kind == ModelKind.PERSIAN_LITE) {
                TtsPrefs.setActivePersianKind(this, ModelKind.PERSIAN_LITE)
            }
            if (report.usable.isEmpty()) {
                "مدل ${kind.label} نصب شد. اکنون از بخش «کلون صدا» یک گوینده برای آن بسازید."
            } else {
                buildReport("مدل ${kind.label} از پوشه انتخاب‌شده نصب شد.", report)
            }
        }
    }

    // ---------------------------------------------------------------- Phonon Voices browser

    private fun openPhononVoicesBrowser() {
        if (busy) return
        busy = true
        updatePlayButton()
        setStatus("در حال دریافت فهرست صداهای انگلیسی از gradium/phonon-voices...")
        binding.progressBar.isIndeterminate = true
        binding.progressBar.visibility = View.VISIBLE

        executor.execute {
            val result = runCatching { PhononVoicesCatalog.fetchEnglishVoices() }
            runOnUiThread {
                busy = false
                binding.progressBar.visibility = View.GONE
                binding.progressBar.isIndeterminate = false
                updatePlayButton()
                result.fold(
                    onSuccess = { entries ->
                        setStatus("${entries.size} صدای انگلیسی دریافت شد.")
                        showPhononVoicesDialog(entries)
                    },
                    onFailure = { err ->
                        setStatus("خطا در دریافت فهرست صداها: ${err.message}")
                    }
                )
            }
        }
    }

    private fun showPhononVoicesDialog(entries: List<PhononVoiceEntry>) {
        val installed = PhononVoicesCatalog.installedFileNames(this)
        val selectedIds = LinkedHashSet<String>()
        var activeFilter = "all"

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, dp(8))
        }

        val filterRow = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val filters = listOf(
            "all" to "همه (${entries.size})",
            "en-us" to "US",
            "en-gb" to "UK",
            "characters" to "کاراکتر"
        )
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val selectionSummary = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        }

        fun visibleEntries(): List<PhononVoiceEntry> = when (activeFilter) {
            "all" -> entries
            else -> entries.filter { it.locale.equals(activeFilter, ignoreCase = true) }
        }

        fun updateSummary() {
            selectionSummary.text = "انتخاب‌شده برای دانلود: ${selectedIds.size} صدا"
        }

        fun rebuildList() {
            listContainer.removeAllViews()
            for (entry in visibleEntries()) {
                val alreadyInstalled = PhononVoicesCatalog.isInstalled(entry, installed)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(6))
                }
                val cb = CheckBox(this).apply {
                    val badge = if (alreadyInstalled) " (نصب شده)" else ""
                    text = "${entry.name}$badge — ${entry.shortSubtitle()}"
                    isChecked = entry.voiceId in selectedIds
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedIds.add(entry.voiceId) else selectedIds.remove(entry.voiceId)
                        updateSummary()
                    }
                }
                row.addView(cb)
                if (entry.description.isNotBlank()) {
                    val desc = TextView(this).apply {
                        text = entry.description.take(140)
                        textSize = 11f
                        setPadding(dp(32), 0, dp(8), 0)
                    }
                    row.addView(desc)
                }
                listContainer.addView(row)
            }
            updateSummary()
        }

        filters.forEachIndexed { idx, (key, label) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 12f
                isChecked = idx == 0
                tag = key
            }
            filterRow.addView(rb)
        }
        filterRow.setOnCheckedChangeListener { group, checkedId ->
            val rb = group.findViewById<RadioButton>(checkedId)
            activeFilter = (rb?.tag as? String) ?: "all"
            rebuildList()
        }

        val toggleAllBtn = Button(this).apply {
            text = "انتخاب / لغو انتخاب همه موارد این دسته"
            setOnClickListener {
                val visible = visibleEntries()
                val allSelected = visible.isNotEmpty() && visible.all { it.voiceId in selectedIds }
                if (allSelected) {
                    visible.forEach { selectedIds.remove(it.voiceId) }
                } else {
                    visible.forEach { selectedIds.add(it.voiceId) }
                }
                rebuildList()
            }
        }

        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(340)
            )
        }

        root.addView(filterRow)
        root.addView(toggleAllBtn)
        root.addView(selectionSummary)
        root.addView(scroll)
        rebuildList()

        AlertDialog.Builder(this)
            .setTitle("دانلود صداهای انگلیسی (Phonon Voices)")
            .setView(root)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("دانلود انتخاب‌شده‌ها") { _, _ ->
                val chosen = entries.filter { it.voiceId in selectedIds }
                if (chosen.isEmpty()) {
                    setStatus("هیچ صدایی انتخاب نشد.")
                    return@setPositiveButton
                }
                downloadPhononVoices(chosen)
            }
            .show()
    }

    private fun downloadPhononVoices(entries: List<PhononVoiceEntry>) {
        if (busy) return
        stopSpeaking()
        runTask("در حال دانلود ${entries.size} صدای انگلیسی...") {
            engines.close(ModelKind.ENGLISH)
            val count = PhononVoicesCatalog.downloadSelected(this, entries) { idx, total, entry ->
                postStatus("در حال دانلود ${entry.name} ($idx از $total)...")
                runOnUiThread {
                    showDownloadProgress((idx * 100) / total)
                }
            }
            val firstNew = entries.firstOrNull()?.safeStem
            if (firstNew != null && TtsPrefs.getEnglishVoice(this).isNullOrEmpty()) {
                TtsPrefs.setEnglishVoice(this, firstNew)
            }
            if (ModelStorage.hasCoreFiles(this, ModelKind.ENGLISH)) {
                "$count صدای انگلیسی با موفقیت دانلود و آماده شد."
            } else {
                "$count صدای انگلیسی ذخیره شد. برای پخش، مدل انگلیسی (Phonon) را هم دانلود کنید."
            }
        }
    }

    // ---------------------------------------------------------------- Voice Cloning

    private fun resolveCloneTarget(): ModelKind? {
        val active = TtsPrefs.getActivePersianKind(this)
        if (ModelStorage.canCloneVoices(this, active)) return active
        val other = if (active == ModelKind.PERSIAN_LITE) ModelKind.PERSIAN else ModelKind.PERSIAN_LITE
        if (ModelStorage.canCloneVoices(this, other)) return other
        return null
    }

    private fun requestRecordCloneVoice() {
        if (busy) return
        val target = resolveCloneTarget()
        if (target == null) {
            setStatus(cloneUnavailableStatus())
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingCloneTarget = target
            pickRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        showRecordCloneDialog(target)
    }

    private fun requestPickCloneAudio() {
        if (busy) return
        if (resolveCloneTarget() == null) {
            setStatus(cloneUnavailableStatus())
            return
        }
        pickCloneAudio.launch(arrayOf("audio/*"))
    }

    /** The most specific reason cloning cannot start, for the status line. */
    private fun cloneUnavailableStatus(): String {
        for (kind in ModelKind.entries.filter { it.isPersian }) {
            val reason = ModelStorage.cloneUnavailableReason(this, kind)
            if (reason != null && ModelStorage.hasCoreFiles(this, kind)) return reason
        }
        if (!PhononTTS.hasVoicePromptFfi) {
            return ModelStorage.cloneUnavailableReason(this, ModelKind.PERSIAN)
                ?: "کتابخانه بومی نسخه کلون صدا را ندارد."
        }
        return "ابتدا مدل سیلا لایت یا سیلا مینی را نصب کنید تا بتوانید صدا کلون کنید."
    }

    private fun showRecordCloneDialog(defaultTarget: ModelKind) {
        val recorder = VoiceRecorder()
        var recordedPcm: FloatArray? = null
        var chosenTarget = defaultTarget

        val pad = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12), pad, dp(4))
        }

        val targetPicker = createCloneTargetRadioGroup(defaultTarget) { chosenTarget = it }
        if (targetPicker != null) {
            container.addView(TextView(this).apply { text = "مدل هدف:"; textSize = 13f })
            container.addView(targetPicker)
        }

        val nameInput = EditText(this).apply {
            hint = "نام گوینده (مثلاً my_voice یا ali)"
            setText("voice_${System.currentTimeMillis() % 1000}")
            setSelection(text.length)
        }
        val timerText = TextView(this).apply {
            text = "۳ تا ۱۰ ثانیه در محیط آرام صحبت کنید و با مکث کوتاه پایان دهید."
            textSize = 13f
            setPadding(0, dp(10), 0, dp(8))
        }
        val recordBtn = Button(this).apply {
            text = "● شروع ضبط"
        }

        container.addView(TextView(this).apply { text = "نام گوینده:"; textSize = 13f })
        container.addView(nameInput)
        container.addView(timerText)
        container.addView(recordBtn)

        val dialog = AlertDialog.Builder(this)
            .setTitle("کلون صدا با میکروفون (${defaultTarget.label})")
            .setView(container)
            .setNegativeButton("انصراف") { _, _ -> recorder.cancel() }
            .setPositiveButton("ساخت گوینده", null)
            .create()

        dialog.setOnShowListener {
            val saveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveBtn.isEnabled = false

            fun finishRecording() {
                if (recordedPcm != null) return
                val pcm = recorder.stop()
                recordedPcm = pcm
                val secs = pcm.size.toFloat() / AudioDecoder.TARGET_SAMPLE_RATE.toFloat()
                recordBtn.text = "● ضبط مجدد"
                if (secs >= AudioDecoder.MIN_PROMPT_SECONDS) {
                    timerText.text = String.format(
                        Locale.US,
                        "ضبط آماده است (%.1f ثانیه). «ساخت گوینده» را بزنید.",
                        secs
                    )
                    saveBtn.isEnabled = true
                } else {
                    timerText.text = String.format(
                        Locale.US,
                        "ضبط خیلی کوتاه بود (%.1f ثانیه)؛ حداقل ۱ ثانیه و ترجیحاً ۳ تا ۱۰ ثانیه ضبط کنید.",
                        secs
                    )
                    saveBtn.isEnabled = false
                }
            }

            recordBtn.setOnClickListener {
                if (recorder.isRecording) {
                    finishRecording()
                } else {
                    recordedPcm = null
                    saveBtn.isEnabled = false
                    recordBtn.text = "■ پایان ضبط"
                    timerText.text = "در حال ضبط: 0.0 ثانیه..."
                    recorder.start(
                        maxSeconds = 15f,
                        onTick = { secs ->
                            runOnUiThread {
                                if (recorder.isRecording) {
                                    timerText.text = String.format(
                                        Locale.US,
                                        "در حال ضبط: %.1f / 15.0 ثانیه (پیشنهاد: ۳ تا ۱۰ ثانیه)",
                                        secs
                                    )
                                }
                            }
                        },
                        onMaxReached = {
                            runOnUiThread { finishRecording() }
                        },
                        onError = { err ->
                            runOnUiThread {
                                recordBtn.text = "● شروع ضبط"
                                timerText.text = "خطا: $err"
                            }
                        }
                    )
                }
            }

            saveBtn.setOnClickListener {
                val pcm = recordedPcm ?: return@setOnClickListener
                val name = nameInput.text?.toString().orEmpty()
                dialog.dismiss()
                runCloneFromPcm(chosenTarget, name, pcm)
            }
        }

        dialog.setOnDismissListener { recorder.cancel() }
        dialog.show()
    }

    private fun promptCloneFromUri(uri: Uri) {
        val defaultTarget = resolveCloneTarget() ?: return
        var chosenTarget = defaultTarget
        val guessedName = DocumentFile.fromSingleUri(this, uri)?.name
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "cloned_voice"

        val pad = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12), pad, dp(4))
        }
        val targetPicker = createCloneTargetRadioGroup(defaultTarget) { chosenTarget = it }
        if (targetPicker != null) {
            container.addView(TextView(this).apply { text = "مدل هدف:"; textSize = 13f })
            container.addView(targetPicker)
        }
        val nameInput = EditText(this).apply {
            hint = "نام گوینده"
            setText(ModelStorage.sanitizeVoiceName(guessedName))
            setSelection(text.length)
        }
        container.addView(TextView(this).apply {
            text = "نام گوینده جدید (فایل‌های ۳ تا ۱۰ ثانیه‌ای بهترین نتیجه را می‌دهند):"
            textSize = 13f
        })
        container.addView(nameInput)

        AlertDialog.Builder(this)
            .setTitle("کلون صدا از فایل صوتی")
            .setView(container)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ساخت گوینده") { _, _ ->
                val voiceName = nameInput.text?.toString().orEmpty()
                runCloneFromUri(chosenTarget, voiceName, uri)
            }
            .show()
    }

    private fun createCloneTargetRadioGroup(
        defaultTarget: ModelKind,
        onSelect: (ModelKind) -> Unit
    ): RadioGroup? {
        val canMini = ModelStorage.canCloneVoices(this, ModelKind.PERSIAN)
        val canLite = ModelStorage.canCloneVoices(this, ModelKind.PERSIAN_LITE)
        if (!(canMini && canLite)) return null

        val group = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rbLite = RadioButton(this).apply {
            id = View.generateViewId()
            text = "سیلا لایت"
            isChecked = defaultTarget == ModelKind.PERSIAN_LITE
        }
        val rbMini = RadioButton(this).apply {
            id = View.generateViewId()
            text = "سیلا مینی"
            isChecked = defaultTarget == ModelKind.PERSIAN
        }
        group.addView(rbLite)
        group.addView(rbMini)
        group.setOnCheckedChangeListener { _, checkedId ->
            onSelect(if (checkedId == rbLite.id) ModelKind.PERSIAN_LITE else ModelKind.PERSIAN)
        }
        return group
    }

    private fun runCloneFromPcm(target: ModelKind, rawName: String, pcm: FloatArray) {
        if (busy) return
        stopSpeaking()
        runTask("در حال ساخت گوینده کلون‌شده برای ${target.label}...") {
            val (voiceName, voiceFile) = ModelStorage.cloneVoiceFromPcm(this, target, rawName, pcm)
            finishClone(target, voiceName, voiceFile)
        }
    }

    private fun runCloneFromUri(target: ModelKind, rawName: String, uri: Uri) {
        if (busy) return
        stopSpeaking()
        runTask("در حال تبدیل فایل صوتی و ساخت گوینده برای ${target.label}...") {
            val (voiceName, voiceFile) = ModelStorage.cloneVoiceFromUri(this, target, rawName, uri)
            finishClone(target, voiceName, voiceFile)
        }
    }

    /**
     * Select the new voice and hand it to the live engine. Registration can legitimately fail on a
     * loaded checkpoint (baked-in voices refuse `add_voice_file`), in which case the engine has
     * been closed and the voice is picked up by the next load: say so instead of reporting a plain
     * success, because that reload is what the user will notice as a delay.
     */
    private fun finishClone(target: ModelKind, voiceName: String, voiceFile: File): String {
        engines.registerVoiceFile(target, voiceName, voiceFile)
        TtsPrefs.setVoice(this, target, voiceName)
        TtsPrefs.setActivePersianKind(this, target)
        val note = engines.lastRegistrationFailure
        val base = "گوینده «$voiceName» با موفقیت برای ${target.label} ساخته و فعال شد."
        return if (note.isNullOrBlank()) {
            base
        } else {
            "$base (مدل برای استفاده از آن دوباره بارگذاری می‌شود: $note)"
        }
    }

    // ---------------------------------------------------------------- Voice & model management

    private fun addVoices(uri: Uri) {
        if (busy) return
        stopSpeaking()
        runTask("در حال افزودن صداها...") {
            engines.close()
            val summary = ModelStorage.importVoices(this, uri) { message -> postStatus(message) }
            val builder = StringBuilder(
                "صداها اضافه شدند: ${summary.persian} برای سیلا مینی، ${summary.persianLite} برای سیلا لایت، ${summary.english} برای انگلیسی."
            )
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
        val voiceItems = mutableListOf<Pair<ModelKind, String>>()
        for (kind in ModelKind.entries) {
            for (name in ModelStorage.voiceNames(this, kind)) {
                voiceItems += kind to name
            }
        }
        if (voiceItems.isEmpty()) {
            setStatus("هیچ صدایی برای حذف وجود ندارد.")
            return
        }
        val labels = (voiceItems.map { (k, name) -> "$name (${k.label})" } + "حذف همه صداها (${voiceItems.size} فایل)").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("حذف صداها")
            .setItems(labels) { _, which ->
                if (which == voiceItems.size) {
                    AlertDialog.Builder(this)
                        .setTitle("حذف همه صداها")
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
                } else {
                    val (kind, voiceName) = voiceItems[which]
                    stopSpeaking()
                    runTask("در حال حذف گوینده «$voiceName»...") {
                        engines.close(kind)
                        val removed = ModelStorage.deleteVoice(this, kind, voiceName)
                        if (removed) {
                            "گوینده «$voiceName» از ${kind.label} حذف شد."
                        } else {
                            "گوینده «$voiceName» پیدا نشد."
                        }
                    }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmDeleteModels() {
        val installed = ModelKind.entries.filter { ModelStorage.modelDir(this, it).exists() }
        if (installed.isEmpty()) {
            setStatus("هیچ مدلی برای حذف وجود ندارد.")
            return
        }
        val labels = (installed.map { k ->
            val size = ModelStorage.formatSize(ModelStorage.sizeOf(ModelStorage.modelDir(this, k)))
            "حذف ${k.label} ($size)"
        } + "حذف همه مدل‌ها و صداها").toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("حذف از حافظه برنامه")
            .setItems(labels) { _, which ->
                val isAll = which == installed.size
                val target = if (isAll) null else installed[which]
                val title = if (isAll) "حذف همه مدل‌ها" else "حذف ${target!!.label}"
                val msg = if (isAll) {
                    "همه مدل‌ها و صداهای ذخیره‌شده در حافظه داخلی برنامه حذف شوند؟"
                } else {
                    "مدل ${target!!.label} و صداهای آن از حافظه برنامه حذف شوند؟"
                }
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("حذف") { _, _ ->
                        stopSpeaking()
                        runTask("در حال حذف...") {
                            if (target == null) {
                                engines.close()
                                ModelStorage.deleteModels(this)
                                "همه مدل‌ها و صداها از حافظه برنامه حذف شدند."
                            } else {
                                engines.close(target)
                                ModelStorage.deleteModel(this, target)
                                "مدل ${target.label} حذف شد."
                            }
                        }
                    }
                    .show()
            }
            .setNegativeButton("انصراف", null)
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
        binding.modelLiteStatus.text = describe(ModelKind.PERSIAN_LITE)
        binding.modelEnStatus.text = describe(ModelKind.ENGLISH)

        val used = ModelKind.entries.sumOf {
            ModelStorage.sizeOf(ModelStorage.modelDir(this, it))
        }
        binding.storageText.text = "حجم اشغال‌شده در حافظه برنامه: ${ModelStorage.formatSize(used)}"

        val activeFa = TtsPrefs.getActivePersianKind(this)
        suppressUiEvents = true
        binding.radioCillaMini.isChecked = activeFa == ModelKind.PERSIAN
        binding.radioCillaLite.isChecked = activeFa == ModelKind.PERSIAN_LITE
        binding.radioCillaMini.isEnabled = ModelStorage.hasCoreFiles(this, ModelKind.PERSIAN) || !ModelStorage.hasCoreFiles(this, ModelKind.PERSIAN_LITE)
        binding.radioCillaLite.isEnabled = ModelStorage.hasCoreFiles(this, ModelKind.PERSIAN_LITE) || !ModelStorage.hasCoreFiles(this, ModelKind.PERSIAN)
        suppressUiEvents = false

        syncTemperatureForActiveModel()
        updateCloneSection()
        populateVoices()
    }

    private fun updateCloneSection() {
        val target = resolveCloneTarget()
        binding.cloneStatusText.text = if (target != null) {
            val (minSeconds, maxSeconds) = ModelStorage.promptDurationBounds(this, target)
            val range = if (maxSeconds > 10f) "۳ تا ۱۰" else "۳ تا ${maxSeconds.toInt()}"
            "مدل هدف کلون صدا: ${target.label} — $range ثانیه صحبت کنید یا فایل صوتی بدهید " +
                "(حداقل ${minSeconds.toInt()} ثانیه)."
        } else {
            // Say which model is missing what, rather than a generic "install a model".
            cloneUnavailableStatus()
        }
    }

    private fun describe(kind: ModelKind): String {
        val dir = ModelStorage.modelDir(this, kind)
        if (!dir.exists()) return "نصب نشده"
        if (!ModelStorage.hasCoreFiles(dir)) return "فایل‌های مدل ناقص است"
        val voices = ModelStorage.voiceNames(dir)
        if (voices.isEmpty()) {
            return "وزن مدل آماده است (${ModelStorage.formatSize(ModelStorage.sizeOf(dir))}) — هنوز صدایی ندارد (از بخش کلون صدا بسازید)"
        }
        val preview = if (voices.size <= 6) {
            voices.joinToString("، ")
        } else {
            voices.take(5).joinToString("، ") + " و ${voices.size - 5} تای دیگر"
        }
        return "آماده — ${voices.size} گوینده ($preview) — ${ModelStorage.formatSize(ModelStorage.sizeOf(dir))}"
    }

    private fun populateVoices() {
        val activeFa = TtsPrefs.getActivePersianKind(this)
        binding.voiceFaLabel.text = "گوینده فارسی (${activeFa.label})"
        bindVoices(
            binding.voiceFaSpinner,
            activeFa,
            TtsPrefs.getVoice(this, activeFa)
        ) { TtsPrefs.setVoice(this, activeFa, it) }

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
        val hasModel = engines.hasAnyPersianModel() || engines.hasEnglishModel()
        val canClone = resolveCloneTarget() != null
        if (playing) {
            binding.playStopButton.text = "■ توقف"
            binding.playStopButton.isEnabled = true
        } else {
            binding.playStopButton.text = "▶ پخش صدا"
            binding.playStopButton.isEnabled = hasModel && !busy
        }
        binding.saveWavButton.isEnabled = hasModel && !busy && !playing
        binding.downloadFaButton.isEnabled = !busy
        binding.selectFaFolderButton.isEnabled = !busy
        binding.downloadLiteButton.isEnabled = !busy
        binding.selectLiteFolderButton.isEnabled = !busy
        binding.cloneRecordButton.isEnabled = canClone && !busy && !playing
        binding.clonePickFileButton.isEnabled = canClone && !busy && !playing
        binding.downloadEnButton.isEnabled = !busy
        binding.downloadPhononVoicesButton.isEnabled = !busy
        binding.selectEnFolderButton.isEnabled = !busy
        binding.importVoicesButton.isEnabled = !busy
        binding.deleteVoicesButton.isEnabled = !busy
        binding.deleteModelsButton.isEnabled = !busy
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        stopRequested.set(true)
        if (::engines.isInitialized) {
            executor.execute { engines.close() }
        }
        executor.shutdown()
    }
}
