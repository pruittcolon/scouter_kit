#!/bin/sh
# Fetches Khronos' OpenCL headers (Apache-2.0, OpenCL-Headers v2026.05.29), checks their SHA-256 and puts them into
# OUT/include for llama.cpp's OpenCL backend (the phone's Adreno GPU; native/CMakeLists.txt, VOX_LLM_GPU). It also
# makes OUT/lib/libOpenCL.so, a link-time stub with every function of CL/cl.h (empty bodies): libvox_llm links
# against it and, on the phone, gets the vendor's libOpenCL.so (public, asked for by the manifest's
# uses-native-library). The stub is never packaged. Needs ANDROID_NDK_ROOT (its clang) and python3.
# Usage: fetch_opencl.sh OUT
set -eu
[ $# -eq 1 ] || { echo "usage: fetch_opencl.sh OUT" >&2; exit 2; }
OUT=$1
VERSION=2026.05.29
SHA256=d9e6c48357de5002da11ce45de600e0c3ffe6ab4f628a3b9fe2b38603161658a
[ -f "$OUT/lib/libOpenCL.so" ] && [ -f "$OUT/include/CL/cl.h" ] && { echo "OpenCL headers $VERSION already in $OUT"; exit 0; }
mkdir -p "$OUT"
curl -fsSL --proto '=https' -o "$OUT/headers.tar.gz.part" \
	"https://github.com/KhronosGroup/OpenCL-Headers/archive/refs/tags/v$VERSION.tar.gz"
echo "$SHA256  $OUT/headers.tar.gz.part" | sha256sum -c -
rm -rf "$OUT/unpack" "$OUT/include"
mkdir -p "$OUT/unpack" "$OUT/include" "$OUT/lib"
tar xzf "$OUT/headers.tar.gz.part" -C "$OUT/unpack"
mv "$OUT/unpack/OpenCL-Headers-$VERSION/CL" "$OUT/include/CL"
cp "$OUT/unpack/OpenCL-Headers-$VERSION/LICENSE" "$OUT/include/LICENSE"
rm -rf "$OUT/unpack" "$OUT/headers.tar.gz.part"
python3 -I - "$OUT/include/CL/cl.h" "$OUT/lib/stub.c" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
names = sorted(set(re.findall(r'CL_API_CALL\s+(cl[A-Z]\w*)\s*\(', src)))
with open(sys.argv[2], 'w') as f:
    f.write('/* Link-time stub: the phone provides the real libOpenCL.so. Used by mistake, every call fails\n'
            ' * (-1, CL_DEVICE_NOT_FOUND), so llama.cpp finds no GPU and stays on the CPU. */\n')
    for n in names:
        f.write('long %s(void) { return -1; }\n' % n)
print('stub: %d functions' % len(names))
PY
CC=${STUB_CC:-$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang}
"$CC" -shared -fPIC -Wl,-soname,libOpenCL.so -o "$OUT/lib/libOpenCL.so" "$OUT/lib/stub.c"
rm "$OUT/lib/stub.c"
echo "OpenCL headers $VERSION and the link stub: $OUT"
