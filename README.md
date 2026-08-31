# Melody Visualizer

An offline Android app that turns a completed humming, singing, or whistling recording into timed piano notes.

## Version 0.3

The app now has the two input choices intended for humming transcription:

1. **Record humming** — tap the microphone, hum the melody, then press **Done · Analyze**.
2. **Upload audio** — choose an existing audio recording from the phone.

There is no live pitch visualization while recording. Both paths wait for a completed recording and then run the same fully on-device analysis.

### Included

- in-app AAC/M4A microphone recording with timer and level feedback;
- Android audio-file picker for common formats supported by `MediaExtractor`/`MediaCodec`;
- stereo-to-mono conversion and resampling to 44.1 kHz;
- neural note/onset transcription using Spotify Basic Pitch's bundled TFLite model;
- a second YIN acoustic tracker that cross-checks uncertain notes and octave errors;
- confidence rejection, median smoothing, note-change hysteresis and event reconciliation;
- segmentation into timed note events with short-note filtering and gap merging;
- timeline piano roll, highlighted piano keyboard and tappable note sequence;
- selectable piano and harmonium playback that follows every detected note length;
- a longer piano decay/release and a steadily sustained harmonium envelope;
- progress, empty-result and unsupported-audio states;
- no account, server, internet permission, analytics or audio upload.

The current transcription path is intended for one clear voice, hum, or whistle without background music. Vocal separation for fully mixed commercial songs remains a separate on-device milestone.

## Processing pipeline

1. The user finishes an in-app recording or selects an audio file.
2. `MediaExtractor` and `MediaCodec` decode the audio to PCM in memory.
3. Channels are mixed to mono and resampled to 44.1 kHz when necessary.
4. The bundled Basic Pitch TFLite network analyzes 2-second overlapping windows at 22.05 kHz.
5. Its note energy and onset evidence are decoded into monophonic timed note candidates.
6. YIN independently analyzes overlapping 2,048-sample frames, rejecting silence and low-confidence pitch.
7. The tracks are reconciled: agreement raises confidence, strong acoustic evidence can repair an octave error, and reliable missed notes can be recovered.
8. Stable events are mapped to equal-tempered MIDI notes using A4 = 440 Hz.
9. Compose draws the timeline and keyboard; `AudioTrack` synthesizes piano or harmonium playback locally.

Record one note at a time in a quiet room for the clearest result. Recordings are limited to two minutes in this test release.

## Build

Requirements: JDK 17 and Android SDK 36.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions runs unit tests and publishes an installable debug APK for every version on `main`.

## Privacy

Microphone access is used only for the **Record humming** option. Audio-file upload means selecting a local file for local processing; the app does not transmit it. Temporary in-app recordings are stored in the application cache.

## Third-party model

The bundled Basic Pitch model is provided by Spotify under the Apache License 2.0. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
