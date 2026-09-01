#!/usr/bin/env bash
set -euo pipefail

EXPECTED_SHA="24cef84aedcd1fe87c0b743ef3370ad34dc1fabf6c9014d6128a75a538c7b668"
TARGET="app/src/main/assets/models/spleeter_vocals_fp16.onnx"
ARCHIVE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/sherpa-onnx-spleeter-2stems-fp16.tar.bz2"

if [[ -f "$TARGET" ]] && [[ "$(sha256sum "$TARGET" | cut -d ' ' -f 1)" == "$EXPECTED_SHA" ]]; then
  echo "Spleeter vocal model is already present and verified."
  exit 0
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

curl --fail --location --retry 3 --output "$TEMP_DIR/model.tar.bz2" "$ARCHIVE_URL"
tar --no-same-owner -xjf "$TEMP_DIR/model.tar.bz2" -C "$TEMP_DIR"
SOURCE="$TEMP_DIR/sherpa-onnx-spleeter-2stems-fp16/vocals.fp16.onnx"

echo "$EXPECTED_SHA  $SOURCE" | sha256sum --check
mkdir -p "$(dirname "$TARGET")"
install -m 0644 "$SOURCE" "$TARGET"
echo "Installed verified Spleeter vocal model."
