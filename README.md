# Melody Visualizer

An offline Android app that turns a completed humming, singing, or whistling recording into timed piano notes.

## Version 0.2

The app now has the two input choices intended for humming transcription:

1. **Record humming** — tap the microphone, hum the melody, then press **Done · Analyze**.
2. **Upload audio** — choose an existing audio recording from the phone.

There is no live pitch visualization while recording. Both paths wait for a completed recording and then run the same fully on-device analysis.

### Included

- in-app AAC/M4A microphone recording with timer and level feedback;
- Android audio-file picker for common formats supported by `MediaExtractor`/`MediaCodec`;
- stereo-to-mono conversion and resampling to 44.1 kHz;
- full-file pitch detection using YIN;
- confidence rejection, median smoothing and note-change hysteresis;
- segmentation into timed note events with short-note filtering and gap merging;
- timeline piano roll, highlighted piano keyboard and tappable note sequence;
- playback of the complete detected melody using locally synthesized piano tones;
- progress, empty-result and unsupported-audio states;
- no account, server, internet permission, analytics or audio upload.

The current transcription path is intended for one clear voice, hum, or whistle without background music. Vocal separation for fully mixed commercial songs remains a separate on-device milestone.

## Processing pipeline

1. The user finishes an in-app recording or selects an audio file.
2. `MediaExtractor` and `MediaCodec` decode the audio to PCM in memory.
3. Channels are mixed to mono and resampled to 44.1 kHz when necessary.
4. YIN analyzes overlapping 2,048-sample frames.
5. Low-confidence and silent frames are rejected.
6. A rolling median and semitone hysteresis stabilize the pitch track.
7. Stable frames are grouped into timed, equal-tempered MIDI note events using A4 = 440 Hz.
8. Compose draws the timeline and keyboard; `AudioTrack` plays the detected melody locally.

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
