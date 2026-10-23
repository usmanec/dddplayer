# DDD Video Player: libdddaudioenc.so - JNI-обертка кодировщика AC3/E-AC3 над статическим FFmpeg
# (только libavcodec/libavutil с кодировщиками ac3 и eac3). Собирается audioenc/build_audioenc.sh.
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)
project(dddaudioenc C)

add_library(dddaudioenc SHARED ddd_audioenc.c)
target_include_directories(dddaudioenc PRIVATE "${FFMPEG_DIR}/include")
target_link_libraries(dddaudioenc
                      PRIVATE "${FFMPEG_DIR}/lib/libavcodec.a"
                      PRIVATE "${FFMPEG_DIR}/lib/libavutil.a"
                      PRIVATE log
                      PRIVATE m)
# Символы FFmpeg не экспортируем: в приложении есть и libffmpegJNI.so со своей копией FFmpeg
target_link_options(dddaudioenc PRIVATE
                    "-Wl,--exclude-libs,ALL"
                    "-Wl,-Bsymbolic"
                    "-Wl,-z,max-page-size=16384")
