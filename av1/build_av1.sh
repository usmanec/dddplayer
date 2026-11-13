#!/usr/bin/env bash
# Сборка libdav1dJNI.so (dav1d + JNI из av1/jni) под все ABI и замена .so в app/libs/lib-decoder-av1-release.aar.
# Java-классы AAR (Media3 1.9.0) не трогаются: сигнатуры JNI те же.
# Нужно: Android NDK, cmake, meson, ninja, nasm (для x86), git, zip/unzip.
# Запуск: ./av1/build_av1.sh <путь к NDK>   (в CI - workflow "Build AV1 decoder")
set -euo pipefail

NDK="${1:?usage: build_av1.sh <ndk path>}"
DAV1D_TAG="${DAV1D_TAG:-1.5.4}"
CPU_FEATURES_TAG="${CPU_FEATURES_TAG:-v0.10.1}"
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
BUILD="$HERE/build"
AAR="$ROOT/app/libs/lib-decoder-av1-release.aar"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found: $TOOLCHAIN" >&2; exit 1; }

mkdir -p "$BUILD"
[ -d "$BUILD/dav1d" ] || git clone --depth 1 --branch "$DAV1D_TAG" https://code.videolan.org/videolan/dav1d.git "$BUILD/dav1d"
[ -d "$BUILD/cpu_features" ] || git clone --depth 1 --branch "$CPU_FEATURES_TAG" https://github.com/google/cpu_features.git "$BUILD/cpu_features"

rm -rf "$BUILD/nativelib" "$BUILD/aar"
for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android21; CROSS=aarch64-android ;;
    armeabi-v7a) TARGET=armv7a-linux-androideabi21; CROSS=arm-android ;;
    x86_64) TARGET=x86_64-linux-android21; CROSS=x86_64-android ;;
    x86) TARGET=i686-linux-android21; CROSS=x86-android ;;
  esac

  # dav1d (8 и 10 бит) - как build_dav1d.sh из androidx/media
  CROSS_FILE="$BUILD/$ABI.meson"
  sed -e "s|c = .*|c = '$TOOLCHAIN/$TARGET-clang'|" \
      -e "s|cpp = .*|cpp = '$TOOLCHAIN/$TARGET-clang++'|" \
      -e "s|ar = .*|ar = '$TOOLCHAIN/llvm-ar'|" \
      -e "s|strip = .*|strip = '$TOOLCHAIN/llvm-strip'|" \
      "$BUILD/dav1d/package/crossfiles/$CROSS.meson" > "$CROSS_FILE"
  meson setup "$BUILD/dav1d-$ABI" "$BUILD/dav1d" --cross-file="$CROSS_FILE" \
    --default-library=static --buildtype=release -Denable_tools=false -Denable_tests=false --wipe
  ninja -C "$BUILD/dav1d-$ABI"
  mkdir -p "$BUILD/nativelib/$ABI"
  cp "$BUILD/dav1d-$ABI/src/libdav1d.a" "$BUILD/nativelib/$ABI/"

  # JNI
  cmake -S "$HERE/jni" -B "$BUILD/jni-$ABI" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-21 -DCMAKE_BUILD_TYPE=Release \
    -DDAV1D_DIR="$BUILD/dav1d" -DDAV1D_LIB_DIR="$BUILD/nativelib" -DCPU_FEATURES_DIR="$BUILD/cpu_features"
  ninja -C "$BUILD/jni-$ABI" dav1dJNI
  mkdir -p "$BUILD/aar/jni/$ABI"
  "$TOOLCHAIN/llvm-strip" --strip-unneeded -o "$BUILD/aar/jni/$ABI/libdav1dJNI.so" "$BUILD/jni-$ABI/libdav1dJNI.so"
done

# Подменяем .so в AAR, остальное (classes.jar, манифест, proguard) остается от Media3
(cd "$BUILD/aar" && zip -q -r "$AAR" jni)
unzip -l "$AAR" | grep libdav1dJNI
echo "dav1d $DAV1D_TAG -> $AAR"
