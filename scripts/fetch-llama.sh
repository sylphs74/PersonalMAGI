#!/usr/bin/env bash
# Fetch the pinned llama.cpp release next to the JNI sources.
set -euo pipefail
LLAMA_TAG="${LLAMA_TAG:-b11982}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/cpp/llama.cpp"
if [ -f "$DEST/CMakeLists.txt" ]; then
  echo "llama.cpp already present at $DEST"; exit 0
fi
git clone --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp "$DEST"
