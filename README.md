# Cilla TTS (سیلا) — Android

On-device Persian **and English** text-to-speech app, plus a system TTS engine
(`TextToSpeechService`), powered by a patched build of
[gradium-ai/xn-ptts](https://github.com/gradium-ai/xn-ptts) with three supported checkpoints:

| Language | Model | Size | Licence |
| --- | --- | --- | --- |
| Persian (fast) | [Cilla Mini](https://huggingface.co/spaces/mehdi-hf/cilla-mini-demo) (18M) by [Oncilla AI](https://oncillaai.com) | ~112 MB | Non-commercial (CC BY-NC 4.0) |
| Persian (high quality) | [Cilla Lite](https://huggingface.co/mehdi-hf/cilla-lite) (100M) by [Oncilla AI](https://oncillaai.com) | ~440 MB | Non-commercial (CC BY-NC 4.0) |
| English | [Phonon 31M EN](https://huggingface.co/gradium/phonon-31m-en) + [Phonon Voices](https://huggingface.co/gradium/phonon-voices) by [Gradium](https://gradium.ai) | ~130 MB | Apache 2.0 |

## What is new in 1.2

- **Cilla Lite (100M)** as a second Persian checkpoint, switchable from the reading tab, with
  on-device conversion of its `config.yaml`, `tokenizer.model` and `prompts/*.wav`.
- **On-device voice cloning** for both Cilla checkpoints, from the microphone or an audio file.
- **Phonon Voices browser** for the English model's published voices.
- **Native library built, not committed**: `native/build-ptts-ffi.sh` plus a symbol check that
  fails the build when the library and the Kotlin disagree.
- **Version 1.2.0** (versionCode 4).

## Features

- **Cilla Lite (100M) & Cilla Mini (18M) switcher:** install either or both Persian checkpoints
  and switch between them directly from the reading tab. Each model keeps its own voice selection
  and default temperature (`0.30` for Cilla Lite, `0.70` for Cilla Mini / Phonon).
- **Automatic on-device Cilla Lite conversion:** download `mehdi-hf/cilla-lite` directly inside the
  app (with your Hugging Face Read Token after accepting the repo's gated access) or import a local
  folder via the Storage Access Framework. The app automatically converts `pocket-tts` `config.yaml`
  → `config.json`, SentencePiece `tokenizer.model` → Hugging Face `tokenizer.json`, and encodes
  `prompts/*.wav` into speaker `.safetensors` embeddings on-device.
- **Full Persian frontend (`normalize_fa` + `speak_fa`):** Unicode/character normalization,
  diacritic & invisible-character cleanup, ZWNJ hygiene, Persian number & decimal/percentage
  spelling, exact model-token chunking (`8–80` tokens per sentence), silence trimming, and runaway
  recovery during WAV export.
- **On-device Voice Cloning (Cilla Lite & Cilla Mini):** create custom speaker embeddings directly
  on your phone either by recording 3–10 seconds with the microphone or importing any audio file
  (WAV, MP3, M4A, OGG, FLAC — automatically decoded and resampled to 24 kHz mono before Mimi
  encoding). Cloning needs the f32 `model.safetensors` (a GGUF built without the Mimi encoder cannot
  encode a prompt) and the patched native library; when either is missing the app says which.
  Cilla Mini's text path is unchanged from 1.1 — the new frontend is Cilla Lite's.
- **Phonon English Voice Browser (`gradium/phonon-voices`):** browse and download any of the 37
  English voices (US, UK, and character voices) from `gradium/phonon-voices` directly into the
  English model.
- **Mixed-script reading & System TTS engine:** registers as `سیلا (Cilla TTS)` in Android's
  text-to-speech settings for TalkBack and e-book readers. Latin runs are routed to Phonon while
  Persian text goes to the active Cilla checkpoint.
- **Save to Downloads:** exports synthesized speech as a 24 kHz mono WAV file.

## Native Engine Patch (`native/`)

Upstream `xn-ptts` is extended via `native/xn-ptts.patch` to support:

1. Loading `pocket-tts` / `cilla-lite` checkpoints: `flow_lm.bos_before_voice` is remapped onto
   `speaker_wavs.sink`, the one learnt token the runtime prepends to the voice prompt.
   `flow_lm.fuser.padding_value` is a scalar, not a sink, and is skipped.
2. `ptts_speak_raw` (raw `frames_after_eos` control without internal `xn-ptts` text splitting),
   `ptts_count_tokens` (exact SentencePiece token counting for chunking),
   `ptts_supports_voice_cloning`, `ptts_add_voice_file`, `ptts_clone_voice_to_file`, and
   `ptts_clone_voices_to_dir` (on-device Mimi voice cloning into `.safetensors` files, one weight
   load per batch).

`libptts_ffi.so` is **not** checked in: it is built into `app/src/main/jniLibs/{arm64-v8a,x86_64}`
by `native/build-ptts-ffi.sh` (locally, and in GitHub Actions on every run). The script also writes
`app/src/main/jniLibs/ptts-ffi.symbols`, and `native/check-ptts-ffi.py` — run by `gradlew` and
`build.sh` — fails the build when the library in place is missing a symbol the app calls, so an APK
can no longer ship a `libptts_ffi.so` from an older build that silently lacks `ptts_speak_raw`.
`native/check-patch.sh` confirms the patch still applies to the pinned upstream commit.

To build the patched native libraries locally (requires Rust + `cargo-ndk` + Android NDK):

```bash
./native/build-ptts-ffi.sh
```

Or build the native libraries and the debug APK in one go (`./build.sh` does this automatically
when the libraries are missing):

```bash
./build.sh --native
```

## Build

```bash
./native/build-ptts-ffi.sh   # once, and after any change to native/xn-ptts.patch
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. A release APK can be built with
`./gradlew assembleRelease`; it is unsigned unless you configure your own signing setup.

## GitHub Actions

- **Android CI** compiles the patched `libptts_ffi.so` (cached by patch + script hash) and builds
  debug and release APKs on pushes to `main`/`master`, on pull requests, and on manual runs.
- **Release Android APK** compiles the patched native library and publishes a GitHub Release when a
  version tag such as `v1.2.0` is pushed, with an unsigned APK and its SHA-256 checksum.

## Models are not in the APK

Checkpoints are downloaded into the app's private storage (`files/model`, `files/model_lite`,
`files/model_en`). Neither Hugging Face nor the models are redistributed here; model hosting terms
and licences apply (Cilla Mini and Cilla Lite are non-commercial CC BY-NC 4.0).
