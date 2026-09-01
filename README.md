# Melody Visualizer

An offline Android app that turns humming, singing, or a mixed song into timed melody notes shown on a piano.

## Version 0.4

The app has two completed-audio workflows:

1. **Record humming** — tap the microphone, hum or sing, then press **Done · Analyze**. A gentle neural noise reducer cleans the recording before transcription.
2. **Upload audio** — choose a song or voice recording. A Spleeter vocal model isolates the vocal stem, a gentle noise reducer cleans it, and the pitch detector finds the melody.

Everything runs on the phone after installation. The app has no account, server, analytics, Internet permission, or audio upload.

### Included

- in-app AAC/M4A microphone recording with timer and level feedback;
- Android audio picker for formats supported by `MediaExtractor`/`MediaCodec`;
- stereo-preserving 44.1 kHz decoding for vocal separation;
- Spleeter 2-stem vocal-mask inference through ONNX Runtime;
- DeepFilterNet-based background-noise reduction;
- hybrid Spotify Basic Pitch and YIN note detection;
- confidence filtering, onset evidence, median smoothing, and octave reconciliation;
- timed piano roll, highlighted keyboard, and tappable note sequence;
- sustained local piano and harmonium playback;
- progress and fallback messages for every processing stage.

The current test APK targets 64-bit ARM Android phones (`arm64-v8a`) and limits recordings to two minutes. Vocal splitting improves mixed songs, but dense arrangements, heavy reverb, doubled vocals, and very quiet singers can still reduce note accuracy.

## Processing pipeline

### In-app recording

1. Decode the completed recording to mono PCM.
2. Resample to 48 kHz and apply gentle DeepFilterNet cleanup.
3. Resample to 44.1 kHz and run Basic Pitch plus YIN.
4. Reconcile, smooth, and segment the pitch evidence into note events.

### Uploaded audio

1. Decode and preserve the left and right channels at 44.1 kHz.
2. Compute a 4,096-point stereo STFT and run the Spleeter vocal model in 512-frame chunks.
3. Apply the learned vocal mask, invert the STFT, and mix the vocal stem to mono.
4. Apply gentle DeepFilterNet cleanup and run the hybrid note detector.
5. Draw the result and play it with the chosen local instrument.

If either cleanup model is unavailable on a device, analysis continues with the best available audio and the result screen reports the fallback.

## Build

Requirements: JDK 17, Android SDK 36, and an Android 64-bit ARM target.

Download and verify the Spleeter model before building:

```bash
bash scripts/download_spleeter_model.sh
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions downloads the same checksum-pinned model, runs unit tests, builds the APK, and publishes it on every `main` push.

## Privacy

Microphone access is used only for **Record humming**. Choosing an audio file grants local read access to that file. Audio and intermediate vocal data stay in memory or app-private storage and are never transmitted.

See `THIRD_PARTY_NOTICES.md` for model and library attribution.
