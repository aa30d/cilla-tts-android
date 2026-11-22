# Cilla Mini TTS (سیلا مینی) — Android

On-device Persian **and English** text-to-speech app, plus a system TTS engine
(`TextToSpeechService`), powered by [gradium-ai/xn-ptts](https://github.com/gradium-ai/xn-ptts)
with two checkpoints:

| Language | Model | Licence |
| --- | --- | --- |
| Persian | [Cilla mini](https://huggingface.co/spaces/mehdi-hf/cilla-mini-demo) by [Oncilla AI](https://oncillaai.com) | Non-commercial (CC BY-NC 4.0) |
| English | [Phonon 31M EN](https://huggingface.co/gradium/phonon-31m-en) by [Gradium](https://gradium.ai) | Apache 2.0 |

## What is new in 1.1

- **English is read by the English model.** Text is split by script and each piece goes to the
  checkpoint that speaks it: Persian (and numbers, punctuation) to Cilla, Latin runs to Phonon.
  Phonon is loaded lazily, the first time a Latin run needs it.
- **Voices can no longer be mixed between models.** The native loader reads every file in
  `voices/` when it opens a checkpoint, so one voice from the other model used to kill the whole
  load with `has 512 channels but the speaker projection take 32`. Each voice file's safetensors
  header is now checked against the checkpoint's `config.json` before loading, and a mismatched
  file is moved to `voices_unsupported/` with a message instead of breaking the model. The new
  **انتخاب پوشه صدا** setting files a folder of voices automatically into the right model.
- **Two tabs.** **خواندن** (reading, the default) and **تنظیمات** (settings).
  - Reading: text box, one play/stop toggle directly under it, save button under that, then
    Persian voice, English voice, temperature and seed.
  - Settings: download / pick a folder for the Persian model, the same for the English model,
    the voice folder picker, deleting voices, deleting models and voices from app storage, and
    the licence text.
- **Separate voice selection per model** (`voice_fa` and `voice_en`); the system TTS engine and
  the app share it.
- **Version 1.1.0** (versionCode 2).

## Features

- **System TTS engine:** registers as `سیلا مینی (Cilla Mini TTS)` in Android's preferred
  text-to-speech engine settings for TalkBack and e-book readers. Persian and mixed pages are
  read with the right checkpoint per stretch.
- **Persistent settings:** voice per model, temperature, and seed (fixed or random) are applied
  in both the app and the system engine.
- **Model management:** downloads Cilla from Hugging Face or imports a local model folder through
  the Storage Access Framework; the same for Phonon (`model.safetensors` or `model.q8.gguf`).
- **Save to Downloads:** exports the synthesized speech as a 24 kHz WAV file.

## Build

The native libraries for `arm64-v8a` and `x86_64` are included under `app/src/main/jniLibs`.

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. A release APK can be built with
`./gradlew assembleRelease`; it is unsigned unless you configure your own signing setup.

## GitHub Actions

- **Android CI** builds debug and release APKs on pushes to `main`/`master`, on pull requests,
  and on manual runs. APKs are available as workflow artifacts.
- **Release Android APK** publishes a GitHub Release when a version tag such as `v1.1.0` is
  pushed, with an unsigned APK and its SHA-256 checksum.

## Models are not in the APK

Cilla alone is 112 MB, so both checkpoints are downloaded on first run into the app's private
storage (`files/model`, `files/model_en`). Neither `huggingface.co` nor the models are
redistributed here; the model hosting terms apply, and Cilla is non-commercial only.
