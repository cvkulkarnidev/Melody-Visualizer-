# Melody Visualizer

An offline Android app that turns humming, singing, or a mixed song into timed melody notes shown on a piano.

## Version 0.5

The app has two completed-audio workflows:

1. **Record humming** — tap the microphone, hum or sing, then press **Done · Analyze**. A gentle neural noise reducer cleans the recording before transcription.
2. **Upload audio** — choose a song or voice recording. A Spleeter vocal model isolates the vocal stem, a gentle noise reducer cleans it, and the pitch detector finds the melody.

The home screen also includes three offline solo-voice examples from the Vocadito dataset. They can be analyzed immediately without microphone or file access.

Everything runs on the phone after installation. The app has no account, server, analytics, Internet permission, or audio upload.

### Included

- in-app AAC/M4A microphone recording with timer and level feedback;
- Android audio picker for formats supported by `MediaExtractor`/`MediaCodec`;
- stereo-preserving 44.1 kHz decoding for vocal separation;
- Spleeter 2-stem vocal-mask inference through ONNX Runtime;
- DeepFilterNet-based background-noise reduction;
- SwiftF0 monophonic neural pitch tracking at 16 ms resolution;
- band-limited resampling to prevent high harmonics from aliasing into false notes;
- whole-recording dynamic-programming note segmentation for vibrato, glides, brief dropouts, and octave glitches;
- source-tuning estimation followed by concert-pitch normalization to A4 = 440 Hz;
- click-free single-stream piano/harmonium playback with overlapping release tails and short-gap legato;
- automatic comparison of denoised and pre-denoised pitch contours;
- timed piano roll, highlighted keyboard, and tappable note sequence;
- sustained local piano and harmonium playback;
- progress and fallback messages for every processing stage.

The current test APK targets 64-bit ARM Android phones (`arm64-v8a`) and limits recordings to two minutes. Vocal splitting improves mixed songs, but dense arrangements, heavy reverb, doubled vocals, and very quiet singers can still reduce note accuracy.

## Real-voice evaluation

SwiftF0 and the note segmenter were evaluated on the three bundled Vocadito clips. Each clip has frame-level F0 ground truth and two independent note transcriptions made by trained musicians.

| Clip | Duration | Raw pitch accuracy (50 cents) | Note F1 vs annotator 1 | Note F1 vs annotator 2 |
|---|---:|---:|---:|---:|
| `vocadito_10` | 9.1 s | 94.7% | 0.76 | 0.69 |
| `vocadito_14` | 12.2 s | 93.5% | 0.83 | 0.69 |
| `vocadito_20` | 8.7 s | 94.3% | 0.81 | 0.79 |

Note matching uses a 100 ms onset tolerance and a 50-cent pitch tolerance. The two human annotations score 0.74, 0.73, and 0.89 F1 against each other on these clips, so note-boundary accuracy has genuine annotator ambiguity. These measurements test clean monophonic singing, not mixed commercial songs or every Android device.

The evaluation is reproducible with `python scripts/evaluate_vocadito.py` after installing NumPy, SciPy, SoundFile, ONNX Runtime, and SwiftF0. The checked-in annotations are the unmodified Vocadito ground truth.

## Processing pipeline

### In-app recording

1. Decode the completed recording to mono PCM.
2. Resample to 48 kHz and apply gentle DeepFilterNet cleanup.
3. Analyze both the cleaned and pre-cleanup signals with SwiftF0 at 16 kHz.
4. Retain the more confident, temporally coherent pitch contour.
5. Estimate the source's tuning grid, segment the complete contour globally, and normalize the resulting notes to A4 = 440 Hz.

### Uploaded audio

1. Decode and preserve the left and right channels at 44.1 kHz.
2. Compute a 4,096-point stereo STFT and run the Spleeter vocal model in 512-frame chunks.
3. Apply the learned vocal mask, invert the STFT, and mix the vocal stem to mono.
4. Apply gentle DeepFilterNet cleanup and compare it with the unfiltered vocal stem.
5. Track the predominant monophonic F0, estimate the source tuning, globally segment it, and normalize notes to A4 = 440 Hz.
6. Draw the result and play it with the chosen local instrument.

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
