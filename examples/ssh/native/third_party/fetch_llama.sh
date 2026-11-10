#!/bin/sh
# Fetches llama.cpp's source (MIT, ggml-org/llama.cpp release v0.9.0), checks its SHA-256 and unpacks it into
# OUT/llama.cpp for the app's on-phone language and embedding models (native/llm_jni.cpp; Llm.kt): Liquid's LFM2.5
# and EmbeddingGemma as GGUF files, downloaded in the app.
# Usage: fetch_llama.sh OUT
set -eu
[ $# -eq 1 ] || { echo "usage: fetch_llama.sh OUT" >&2; exit 2; }
OUT=$1
VERSION=0.9.0
SHA256=5d0f1aaf55fd8629c29a55e81b7cb63b80239f83c33a580963071204896836a8
[ -f "$OUT/llama.cpp/include/llama.h" ] && { echo "llama.cpp $VERSION already in $OUT"; exit 0; }
mkdir -p "$OUT"
curl -fsSL --proto '=https' -o "$OUT/llama.tar.gz.part" \
	"https://github.com/ggml-org/llama.cpp/archive/refs/tags/v$VERSION.tar.gz"
echo "$SHA256  $OUT/llama.tar.gz.part" | sha256sum -c -
rm -rf "$OUT/llama.cpp" "$OUT/unpack"
mkdir -p "$OUT/unpack"
tar xzf "$OUT/llama.tar.gz.part" -C "$OUT/unpack"
mv "$OUT/unpack/llama.cpp-$VERSION" "$OUT/llama.cpp"
rm -rf "$OUT/unpack" "$OUT/llama.tar.gz.part"
echo "llama.cpp $VERSION: $OUT/llama.cpp"
