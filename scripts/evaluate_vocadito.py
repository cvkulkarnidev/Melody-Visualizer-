#!/usr/bin/env python3
"""Evaluate SwiftF0 on the three real-voice examples bundled with the app."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from scipy.signal import resample_poly
import soundfile as sf
from swift_f0 import SwiftF0, segment_notes


ROOT = Path(__file__).resolve().parents[1]
AUDIO = ROOT / "app" / "src" / "main" / "res" / "raw"
ANNOTATIONS = ROOT / "evaluation" / "vocadito"
TRACKS = (10, 14, 20)


def midi(pitch_hz: float | np.ndarray) -> float | np.ndarray:
    return 69.0 + 12.0 * np.log2(np.asarray(pitch_hz) / 440.0)


def note_score(predicted, reference: np.ndarray) -> tuple[float, float, float]:
    candidates = []
    for predicted_index, note in enumerate(predicted):
        for reference_index, target in enumerate(reference):
            onset_error = abs(note.start - target[0])
            pitch_error = abs(midi(note.pitch_hz) - midi(target[1]))
            if onset_error <= 0.100 and pitch_error <= 0.5:
                candidates.append(
                    (onset_error / 0.100 + pitch_error / 0.5, predicted_index, reference_index)
                )

    used_predicted: set[int] = set()
    used_reference: set[int] = set()
    for _, predicted_index, reference_index in sorted(candidates):
        if predicted_index not in used_predicted and reference_index not in used_reference:
            used_predicted.add(predicted_index)
            used_reference.add(reference_index)

    precision = len(used_predicted) / len(predicted) if predicted else 0.0
    recall = len(used_reference) / len(reference) if len(reference) else 0.0
    f1 = 2.0 * precision * recall / (precision + recall) if precision + recall else 0.0
    return precision, recall, f1


def evaluate(track_id: int, detector: SwiftF0) -> tuple[float, float, float, float]:
    samples, sample_rate = sf.read(
        AUDIO / f"vocadito_{track_id}.flac",
        dtype="float32",
        always_2d=False,
    )
    model_audio = resample_poly(samples, 160, 441).astype(np.float32)
    contour = detector.detect(model_audio, 16_000, fmin=60.0, fmax=1_200.0)
    notes = segment_notes(contour, pitch_hold_ms=80.0)

    reference_f0 = np.loadtxt(ANNOTATIONS / f"vocadito_{track_id}_f0.csv", delimiter=",")
    indices = np.clip(
        np.rint(reference_f0[:, 0] / 0.016).astype(int),
        0,
        len(contour.pitch_hz) - 1,
    )
    target_pitch = reference_f0[:, 1]
    predicted_pitch = contour.pitch_hz[indices]
    target_voiced = target_pitch > 0.0
    predicted_voiced = contour.confidence[indices] >= 0.5
    both_voiced = target_voiced & predicted_voiced
    correct_pitch = np.zeros(len(target_pitch), dtype=bool)
    correct_pitch[both_voiced] = (
        np.abs(1200.0 * np.log2(predicted_pitch[both_voiced] / target_pitch[both_voiced]))
        <= 50.0
    )
    raw_pitch_accuracy = np.count_nonzero(correct_pitch) / np.count_nonzero(target_voiced)

    scores = []
    for annotator in (1, 2):
        reference_notes = np.loadtxt(
            ANNOTATIONS / f"vocadito_{track_id}_notesA{annotator}.csv",
            delimiter=",",
        )
        scores.append(note_score(notes, reference_notes)[2])

    return len(samples) / sample_rate, raw_pitch_accuracy, scores[0], scores[1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--minimum-pitch-accuracy", type=float, default=0.90)
    args = parser.parse_args()

    detector = SwiftF0(threads=2, spin=False)
    failed = False
    print("track,duration_seconds,raw_pitch_accuracy,note_f1_a1,note_f1_a2")
    for track_id in TRACKS:
        duration, accuracy, f1_a1, f1_a2 = evaluate(track_id, detector)
        print(f"{track_id},{duration:.2f},{accuracy:.3f},{f1_a1:.3f},{f1_a2:.3f}")
        failed = failed or accuracy < args.minimum_pitch_accuracy

    if failed:
        raise SystemExit("A real-voice sample fell below the pitch-accuracy threshold")


if __name__ == "__main__":
    main()
