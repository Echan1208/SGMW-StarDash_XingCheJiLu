#!/bin/bash
# Builds StarDash.apk without Gradle: zig (native) + javac -> d8 -> aapt2 -> zipalign -> apksigner.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# Requirements: JDK 17, Android SDK Build-Tools 34.0.0 + Platform 33, Zig 0.13.0 (C cross-compiler).
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
fi
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-33/android.jar"
ZIG="${ZIG:-zig}"
KS_PASS="${STARDASH_KEYSTORE_PASS:-stardashpass}"
for tool in "$ZIG" javac keytool zip unzip curl; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Missing tool: $tool (set ZIG / JAVA_HOME, see README)" >&2; exit 1; }
done
[ -x "$BT/d8" ] && [ -f "$ANDROID_JAR" ] || { echo "Android SDK not found under $ANDROID_HOME (need build-tools 34.0.0 and platform 33)" >&2; exit 1; }
OUT="$HERE/build"
KEYSTORE="$HERE/release.keystore"

rm -rf "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$OUT/apk"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$OUT/apk/lib/arm64-v8a"

ZIG_GLOBAL_CACHE_DIR="$OUT/zig-global" ZIG_LOCAL_CACHE_DIR="$OUT/zig-local" \
    "$ZIG" cc -target aarch64-linux-android -O3 -fPIC -shared -nostdlib -ffreestanding -fno-stack-protector -g0 -s -ffile-prefix-map="$HERE"=. \
    -Wl,-soname,libstardash.so -Wl,-z,max-page-size=16384 \
    "$HERE/native/dash.c" -o "$OUT/apk/lib/arm64-v8a/libstardash.so"

# Side-rear detection: NanoDet through ncnn's C API. The prebuilt ncnn Android library (BSD-3-Clause,
# https://github.com/Tencent/ncnn) is downloaded once into third_party/ and checked against its SHA-256.
NCNN_VER=20261020
NCNN_SHA256=c2f866782cb6b8337bcd062f12b0da1b75e703f4e90fc7905d00c7c74d669a87
NCNN="$HERE/third_party/ncnn-$NCNN_VER-android-shared/arm64-v8a"
if [ ! -f "$NCNN/lib/libncnn.so" ]; then
    mkdir -p "$HERE/third_party"
    ZIPFILE="$HERE/third_party/ncnn-$NCNN_VER-android-shared.zip"
    curl -fL -o "$ZIPFILE" "https://github.com/Tencent/ncnn/releases/download/$NCNN_VER/ncnn-$NCNN_VER-android-shared.zip"
    if command -v shasum >/dev/null 2>&1; then SUM="$(shasum -a 256 "$ZIPFILE")"; else SUM="$(sha256sum "$ZIPFILE")"; fi
    [ "${SUM%% *}" = "$NCNN_SHA256" ] || { echo "ncnn download checksum mismatch" >&2; rm -f "$ZIPFILE"; exit 1; }
    unzip -q -o "$ZIPFILE" "ncnn-$NCNN_VER-android-shared/arm64-v8a/*" -d "$HERE/third_party"
    rm -f "$ZIPFILE"
fi
cp "$NCNN/lib/libncnn.so" "$OUT/apk/lib/arm64-v8a/libncnn.so"
ZIG_GLOBAL_CACHE_DIR="$OUT/zig-global" ZIG_LOCAL_CACHE_DIR="$OUT/zig-local" \
    "$ZIG" cc -target aarch64-linux-android -O2 -fPIC -shared -nostdlib -ffreestanding -fno-stack-protector -g0 -s -ffile-prefix-map="$HERE"=. \
    -Wl,-soname,libbsd.so -Wl,-z,max-page-size=16384 \
    "$HERE/native/bsd.c" -L "$NCNN/lib" -lncnn -o "$OUT/apk/lib/arm64-v8a/libbsd.so"

# android.car lives on the head unit's boot classpath; compile against stubs and leave them out of the dex.
javac -source 11 -target 11 -encoding UTF-8 -Xlint:-options -nowarn \
    -classpath "$ANDROID_JAR" -d "$OUT/stubs" $(find "$HERE/stubs" -name '*.java')
javac -source 11 -target 11 -encoding UTF-8 -Xlint:-options -nowarn \
    -classpath "$ANDROID_JAR:$OUT/stubs" -d "$OUT/classes" $(find "$HERE/src" -name '*.java')

"$BT/d8" --min-api 25 --lib "$ANDROID_JAR" --classpath "$OUT/stubs" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"
# assets/: the NanoDet-Plus-m 320 model in ncnn format (Apache-2.0, https://github.com/RangiLyu/nanodet).
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" -A "$HERE/assets" "$OUT/res.zip"
cp "$OUT/dex/classes.dex" "$OUT/apk/classes.dex"
(cd "$OUT/apk" && zip -q -r "$OUT/unsigned.apk" classes.dex lib)

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -alias stardash -keyalg RSA -keysize 2048 \
        -validity 36500 -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=StarDash"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias stardash \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$HERE/StarDash.apk" "$OUT/aligned.apk"

echo "Built $HERE/StarDash.apk"
