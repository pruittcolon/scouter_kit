#!/bin/sh
# Fetches sherpa-onnx 1.13.8's Android JNI library (Apache-2.0; ONNX Runtime linked in statically, MIT) for
# arm64, checks the release's SHA-256 and puts libsherpa-onnx-jni.so into OUT/jniLibs/arm64-v8a/ for the
# app's voice commands (the Kotlin side is scouter_kit/third_party/sherpa_onnx_kotlin).
# Usage: fetch_sherpa.sh OUT
set -eu
[ $# -eq 1 ] || { echo "usage: fetch_sherpa.sh OUT" >&2; exit 2; }
OUT=$1
VERSION=1.13.8
NAME=sherpa-onnx-v$VERSION-android-static-link-onnxruntime
SHA256=7583ca385ae7d981e65468455c2ea2c9f2da383921dccfc5658d0dc19d309e6f
LIB=$OUT/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
[ -f "$LIB" ] && { echo "sherpa-onnx $VERSION already in $OUT"; exit 0; }
mkdir -p "$OUT"
curl -fsSL --proto '=https' -o "$OUT/$NAME.tar.bz2.part" \
	"https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$NAME.tar.bz2"
echo "$SHA256  $OUT/$NAME.tar.bz2.part" | sha256sum -c -
mkdir -p "$OUT/unpack"
tar xjf "$OUT/$NAME.tar.bz2.part" -C "$OUT/unpack" ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so
mkdir -p "$OUT/jniLibs/arm64-v8a"
mv "$OUT/unpack/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$LIB"
rm -rf "$OUT/unpack" "$OUT/$NAME.tar.bz2.part"
echo "sherpa-onnx $VERSION: $LIB"
