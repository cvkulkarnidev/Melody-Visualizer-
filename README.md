# Melody Visualizer

An offline-first Android app that turns a sung or hummed melody into piano notes in real time.

## Version 0.1

The first testable version includes:

- live microphone pitch detection using an on-device YIN implementation;
- confidence and silence rejection;
- median smoothing and note-change hysteresis to reduce flicker;
- detected note, frequency, cents and input-level feedback;
- a six-second piano-roll trail and highlighted two-octave keyboard;
- recent-note history;
- an offline, synthesized piano tone with automatic and tap-to-play modes;
- no account, internet permission, analytics, recording upload or audio storage.

The home screen contains the intended two product modes. **Sing or hum** is functional in this release. **Listen to a song** is presented as the next milestone because it requires integrating and benchmarking a compact on-device vocal-separation model; the app does not pretend mixed-song transcription is ready before that work is complete.

## How it works

1. `AudioRecord` captures 44.1 kHz mono PCM frames.
2. YIN estimates the fundamental frequency for each 2,048-sample frame.
3. Low-confidence and silent frames are rejected.
4. A rolling median and semitone hysteresis stabilize note changes.
5. Frequency is mapped to equal-tempered MIDI using A4 = 440 Hz.
6. Compose draws the piano roll and keyboard; `AudioTrack` generates the optional piano-like sound locally.

For best results, hum one note at a time about 15–30 cm from the microphone. Headphones are recommended when automatic piano sound is enabled, otherwise the microphone may hear the phone's speaker.

## Build

Requirements: JDK 17 and Android SDK 36.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions runs tests and publishes an installable debug APK as an artifact for every push. Tags beginning with `v` also create a GitHub Release containing the APK.

## Roadmap

- benchmark compact two-stem vocal separators on representative Android devices;
- integrate chunked STFT → vocal mask → ISTFT processing;
- run isolated vocals through an on-device melody transcription model;
- add editable note segmentation, tempo controls and saved transcriptions.

## Privacy

The app requests only microphone access. Version 0.1 processes microphone frames in memory and does not save or transmit audio.
