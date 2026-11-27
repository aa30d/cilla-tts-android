# AGENTS.md

## Project Overview
- **Package**: `com.cilla.tts`
- **Native library**: `libptts_ffi.so`, built from `gradium-ai/xn-ptts` **plus `native/xn-ptts.patch`** (loaded via JNA in `PhononTTS.kt`). ABIs: `arm64-v8a`, `x86_64`.
- **The library is not committed.** `native/build-ptts-ffi.sh` builds it (locally or in CI) into
  `app/src/main/jniLibs/`, together with the `ptts-ffi.symbols` manifest that
  `native/check-ptts-ffi.py` checks it against — `gradlew` and `build.sh` both run that check, so a
  stale library fails the build instead of throwing `FunctionNotFoundException` on a device.
  `native/check-patch.sh` verifies the patch still applies to the pinned upstream commit.
- **Three checkpoints, three folders**: Cilla Mini in `filesDir/model`, Cilla Lite in
  `filesDir/model_lite`, Phonon (English) in `filesDir/model_en`. Each keeps its own `voices/`
  (and Cilla Lite a `prompts/` of reference audio). `ModelKind` is the list; `ModelKind.isPersian`
  covers the two Cilla ones, and only one Persian checkpoint is resident at a time.
- **Language codes**: both Cilla checkpoints are Persian-only and **must** be initialized and called
  with `lang = "none"` in `PhononTTS`. Passing `"fa"` throws `unsupported language code: fa` (the
  runtime normalizer has no Persian); `PersianFrontend` does the normalization instead. Phonon runs
  with `lang = "en"`.
- **System TTS**: `CillaTtsService` extends `TextToSpeechService` and outputs 24 kHz mono `ENCODING_PCM_16BIT` audio. It accepts `fas`/`fa`/`per` and `eng`/`en`, and reads mixed text segment by segment.
- **Shared Settings**: `TtsPrefs` (`SharedPreferences` `"cilla_tts_prefs"`) stores `voice_fa`,
  `voice_lite`, `voice_en`, `temperature` / `temperature_lite`, `active_persian_model`,
  `random_seed`, `seed`, `hf_token` so `MainActivity` and `CillaTtsService` stay in sync. The pre-1.1
  single `voice` key is still read as the Persian voice. The HF token is sent **only** to
  `huggingface.co` (`ModelDownloader.URL.isHuggingFaceHost`), never to the CDN a download redirects
  to.

## Voices are model-specific — never mix them
The native loader takes **every** `*.safetensors` in `voices/` (and a root `default-voice.safetensors`) when the model is opened, so one incompatible file fails the whole load:

```
Error in Loading Model ... /voices/Easton.safetensors has 512 channels but the speaker projection take 32
```

- A voice holding `speaker_wavs` latents (`[C, T]` or `[1, C, T]`) must have `C ==` the
  projection's input: `speaker_mimi.mimi.inner_dim ?: dimension` when the config has a separate
  speaker Mimi, otherwise `mimi.inner_dim ?: mimi.dimension`. Cilla's `mimi.dimension` is 512 (the
  SEANet width) but its latents are **32** wide, so reading `dimension` without `inner_dim` is what
  lets a 512-channel Phonon voice into Cilla's `voices/` and breaks the load.
- Any other single tensor (`emb`, `audio_prompt`) is a precomputed embedding and must be as wide as `flow_lm.d_model` (512 for both).
- `VoiceCompat` mirrors those rules; `ModelStorage.prepare()` moves mismatched files to `voices_unsupported/` before load, and `ModelStorage.importVoices()` files a picked folder of voices by asking each installed config which of them it can use.

## Voice cloning (Cilla only)
- `ptts_clone_voice_to_file` encodes 24 kHz mono float PCM with the checkpoint's **speaker encoder**
  and writes an `emb` safetensors file (`+ null_emb` when `cfg_null_audio_empty` is false, which
  `Synth::prime` needs for guided generation). It reads `model.safetensors`, never `model.q8.gguf`:
  `quantize --no-mimi-encoder` drops the encoder.
- `ModelStorage.canCloneVoices` / `cloneUnavailableReason` are the single answer to "can this model
  clone now": Persian, f32 weights present, `VoiceCompat.ModelProfile.hasSpeakerEncoder`, and
  `PhononTTS.hasVoicePromptFfi`. Keep them in step with what the native code actually needs.
- Prompt length bounds come from the checkpoint (`audio_prompt_min_duration` /
  `audio_prompt_max_duration`, surfaced through `ModelProfile.promptMinSeconds/MaxSeconds`);
  `CillaLiteConverter` copies them into `config.json` and `AudioDecoder.validateAndTrimPrompt`
  trims with the same numbers. Do not hardcode 1..30 s.
- A cloned file carries `model_ext` (`sig@epoch`) from the config's `model_id`, which
  `CillaLiteConverter` now writes; without it the loader cannot tell which checkpoint made the
  voice and `embeddings_ext` checks silently pass.
- Registering a **new** voice on an engine that is already loaded can fail (`add_voice_file`
  refuses checkpoints with `voices[].conditions`, i.e. baked-in voices, once guidance is on). The
  engine is then closed so the next load picks the file up;
  `TtsEngines.lastRegistrationFailure` carries the reason and the UI shows it.

## Persian frontend: Mini and Lite are not interchangeable
Cilla Mini keeps the text path it shipped with: `TextCleaner.chunkPersian` (200-char, sentence-end
chunks) and `PhononTTS.speak` (`ptts_speak`, whose `prepare_text_prompt` picks three frames after
EOS for short prompts). Cilla Lite uses `PersianFrontend` (`normalize_fa.py` port), token-counted
chunking (`8..80` tokens), `ptts_speak_raw` with `frames_after_eos = 0`, and the buffered runaway
recovery for WAV export. Changing one path must not change the other's audio.

## UI
`MainActivity` is two tabs (Material `TabLayout` + two `ScrollView`s): **خواندن** (default) and **تنظیمات**. The reading tab order is text, play/stop toggle, save, active Persian model (Mini/Lite), Persian voice, English voice, temperature, seed. Settings holds model download/folder picks for all three checkpoints, **کلون صدا** (mic + audio file, Cilla only), the Phonon Voices browser, the voice folder picker, deleting voices/models from internal storage, and the licence text. Everything the settings tab does goes through `runTask`, which runs it on the single background executor.

## Building
There is no signed build in the repo. CI (`.github/workflows/build.yml`) checks the native patch,
builds the patched library (cached by patch+script hash), checks its symbols, then builds debug and
release APKs with the Gradle wrapper. Locally: `./build.sh` (builds the native library if it is
missing, then the APK) or `./gradlew assembleDebug` (requires the library to be built already).
