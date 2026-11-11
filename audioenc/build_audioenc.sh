#!/usr/bin/env bash
# Сборка libdddaudioenc.so (кодировщик AC3/E-AC3 из FFmpeg + JNI из audioenc/jni) под все ABI
# в app/src/main/jniLibs/<abi>/. FFmpeg собирается статически и только с кодировщиками ac3 и eac3.
# Нужно: Android NDK, cmake, ninja, make, git.
# Запуск: ./audioenc/build_audioenc.sh <путь к NDK>   (в CI - workflow "Build audio encoder")
set -euo pipefail

NDK="${1:?usage: build_audioenc.sh <ndk path>}"
FFMPEG_BRANCH="${FFMPEG_BRANCH:-release/6.0}"
API=21
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
BUILD="$HERE/build"
JNILIBS="$ROOT/app/src/main/jniLibs"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found: $TOOLCHAIN" >&2; exit 1; }
JOBS="$(nproc 2>/dev/null || echo 4)"

mkdir -p "$BUILD"
[ -d "$BUILD/ffmpeg" ] || git clone --depth 1 --branch "$FFMPEG_BRANCH" https://github.com/FFmpeg/FFmpeg.git "$BUILD/ffmpeg"

for ABI in "${ABIS[@]}"; do
  EXTRA=()
  case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android$API; ARCH=aarch64; CPU=armv8-a ;;
    armeabi-v7a) TARGET=armv7a-linux-androideabi$API; ARCH=arm; CPU=armv7-a
      EXTRA=(--extra-cflags="-march=armv7-a -mfloat-abi=softfp -mfpu=neon") ;;
    x86_64) TARGET=x86_64-linux-android$API; ARCH=x86_64; CPU=x86-64; EXTRA=(--disable-asm) ;;
    x86) TARGET=i686-linux-android$API; ARCH=x86; CPU=i686; EXTRA=(--disable-asm) ;;
  esac
  OUT="$BUILD/ffmpeg-$ABI"
  rm -rf "$OUT"
  # Опции как в build_ffmpeg.sh из androidx/media, но только кодировщики AC3/E-AC3
  (cd "$BUILD/ffmpeg" && make distclean >/dev/null 2>&1 || true
   ./configure \
    --prefix="$OUT" \
    --target-os=android --arch="$ARCH" --cpu="$CPU" \
    --enable-cross-compile \
    --cc="$TOOLCHAIN/$TARGET-clang" \
    --nm="$TOOLCHAIN/llvm-nm" --ar="$TOOLCHAIN/llvm-ar" \
    --ranlib="$TOOLCHAIN/llvm-ranlib" --strip="$TOOLCHAIN/llvm-strip" \
    --enable-static --disable-shared --enable-pic \
    --disable-doc --disable-programs --disable-everything \
    --disable-avdevice --disable-avformat --disable-swscale --disable-postproc \
    --disable-avfilter --disable-swresample --disable-network --disable-symver \
    --disable-v4l2-m2m --disable-vulkan \
    --enable-encoder=ac3,eac3 \
    "${EXTRA[@]}"
   make -j"$JOBS"
   make install-libs install-headers)

  cmake -S "$HERE/jni" -B "$BUILD/jni-$ABI" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
    -DFFMPEG_DIR="$OUT"
  ninja -C "$BUILD/jni-$ABI"
  mkdir -p "$JNILIBS/$ABI"
  "$TOOLCHAIN/llvm-strip" --strip-unneeded -o "$JNILIBS/$ABI/libdddaudioenc.so" "$BUILD/jni-$ABI/libdddaudioenc.so"
done

ls -l "$JNILIBS"/*/libdddaudioenc.so
