# AGENTS.md

## Project Overview
- **Package**: `com.cilla.tts`
- **Native library**: `libptts_ffi.so` (from `gradium-ai/xn-ptts`, loaded via JNA in `PhononTTS.kt`). ABIs: `arm64-v8a`, `x86_64`.
- **Two checkpoints, two folders**: Cilla (Persian) in `filesDir/model`, Phonon (English) in `filesDir/model_en`. Each keeps its own `voices/`.
- **Language codes**: `cilla-mini` is Persian-only and **must** be initialized and called with `lang = "none"` in `PhononTTS`. Passing `"fa"` throws `unsupported language code: fa` (the runtime normalizer has no Persian). Phonon runs with `lang = "en"`.
- **System TTS**: `CillaTtsService` extends `TextToSpeechService` and outputs 24 kHz mono `ENCODING_PCM_16BIT` audio. It accepts `fas`/`fa`/`per` and `eng`/`en`, and reads mixed text segment by segment.
- **Shared Settings**: `TtsPrefs` (`SharedPreferences` `"cilla_tts_prefs"`) stores `voice_fa`, `voice_en`, `temperature`, `random_seed`, `seed` so `MainActivity` and `CillaTtsService` stay in sync. The pre-1.1 single `voice` key is still read as the Persian voice.

## Voices are model-specific — never mix them
The native loader takes **every** `*.safetensors` in `voices/` (and a root `default-voice.safetensors`) when the model is opened, so one incompatible file fails the whole load:

```
Error in Loading Model ... /voices/Easton.safetensors has 512 channels but the speaker projection take 32
```

- A voice holding `speaker_wavs` latents (`[C, T]` or `[1, C, T]`) must have `C == speaker_mimi.mimi.inner_dim ?: dimension` (Cilla: 32, Phonon: 512).
- Any other single tensor (`emb`, `audio_prompt`) is a precomputed embedding and must be as wide as `flow_lm.d_model` (512 for both).
- `VoiceCompat` mirrors those rules; `ModelStorage.prepare()` moves mismatched files to `voices_unsupported/` before load, and `ModelStorage.importVoices()` files a picked folder of voices by asking each installed config which of them it can use.

## UI
`MainActivity` is two tabs (Material `TabLayout` + two `ScrollView`s): **خواندن** (default) and **تنظیمات**. The reading tab order is text, play/stop toggle, save, Persian voice, English voice, temperature, seed. Settings holds model download/folder picks for both models, the voice folder picker, deleting voices/models from internal storage, and the licence text. Everything the settings tab does goes through `runTask`, which runs it on the single background executor.

## Building
There is no signed build in the repo. CI (`.github/workflows/build.yml`) builds debug and release APKs with the Gradle wrapper; `build.sh` does the same locally.
