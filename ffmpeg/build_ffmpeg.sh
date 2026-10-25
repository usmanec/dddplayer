#!/usr/bin/env bash
# Сборка libffmpegJNI.so (FFmpeg-декодеры звука + JNI из ffmpeg/jni) под все ABI и замена .so в
# app/libs/lib-decoder-ffmpeg-release.aar. Java-классы AAR (Media3 1.9.0) не трогаются: сигнатуры JNI те же.
# Опции FFmpeg - как в build_ffmpeg.sh из androidx/media, набор декодеров - как в app/libs/README.md.
# Нужно: Android NDK, cmake, ninja, make, nasm не нужен (x86 без asm), git, zip/unzip.
# Запуск: ./ffmpeg/build_ffmpeg.sh <путь к NDK>   (в CI - workflow "Build FFmpeg decoder")
set -euo pipefail

NDK="${1:?usage: build_ffmpeg.sh <ndk path>}"
FFMPEG_BRANCH="${FFMPEG_BRANCH:-release/6.0}"
API=21
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
BUILD="$HERE/build"
AAR="$ROOT/app/libs/lib-decoder-ffmpeg-release.aar"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found: $TOOLCHAIN" >&2; exit 1; }
JOBS="$(nproc 2>/dev/null || echo 4)"

mkdir -p "$BUILD"
[ -d "$BUILD/ffmpeg" ] || git clone --depth 1 --branch "$FFMPEG_BRANCH" https://github.com/FFmpeg/FFmpeg.git "$BUILD/ffmpeg"

DECODER_OPTIONS=()
for decoder in "${ENABLED_DECODERS[@]}"; do DECODER_OPTIONS+=(--enable-decoder="$decoder"); done

rm -rf "$BUILD/aar"
for ABI in "${ABIS[@]}"; do
  EXTRA=()
  case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android$API; ARCH=aarch64; CPU=armv8-a ;;
    armeabi-v7a) TARGET=armv7a-linux-androideabi$API; ARCH=arm; CPU=armv7-a
      EXTRA=(--extra-cflags="-march=armv7-a -mfloat-abi=softfp" --extra-ldflags="-Wl,--fix-cortex-a8") ;;
    x86_64) TARGET=x86_64-linux-android$API; ARCH=x86_64; CPU=x86-64; EXTRA=(--disable-asm) ;;
    x86) TARGET=i686-linux-android$API; ARCH=x86; CPU=i686; EXTRA=(--disable-asm) ;;
  esac
  OUT="$BUILD/ffmpeg-$ABI"
  rm -rf "$OUT"
  (cd "$BUILD/ffmpeg" && { make distclean >/dev/null 2>&1 || true; }
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
    --disable-avfilter --disable-symver --enable-swresample \
    --extra-ldexeflags=-pie --disable-v4l2-m2m --disable-vulkan \
    "${DECODER_OPTIONS[@]}" \
    "${EXTRA[@]}"
   make -j"$JOBS"
   make install-libs install-headers)

  cmake -S "$HERE/jni" -B "$BUILD/jni-$ABI" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
    -DFFMPEG_DIR="$OUT"
  ninja -C "$BUILD/jni-$ABI"
  mkdir -p "$BUILD/aar/jni/$ABI"
  "$TOOLCHAIN/llvm-strip" --strip-unneeded -o "$BUILD/aar/jni/$ABI/libffmpegJNI.so" "$BUILD/jni-$ABI/libffmpegJNI.so"
done

# Подменяем .so в AAR, остальное (classes.jar, манифест, proguard) остается от Media3
(cd "$BUILD/aar" && zip -q -r "$AAR" jni)
unzip -l "$AAR" | grep libffmpegJNI
echo "FFmpeg $FFMPEG_BRANCH -> $AAR"
