#
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# DDD Video Player: CMakeLists.txt из androidx/media 1.9.0 (libraries/decoder_ffmpeg/src/main/jni).
# Статический FFmpeg берется из FFMPEG_DIR (install-префикс, его собирает ffmpeg/build_ffmpeg.sh).

cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

set(CMAKE_CXX_STANDARD 11)

project(libffmpegJNI C CXX)

foreach(ffmpeg_lib avutil swresample avcodec)
    add_library(${ffmpeg_lib} STATIC IMPORTED)
    set_target_properties(${ffmpeg_lib} PROPERTIES
                          IMPORTED_LOCATION "${FFMPEG_DIR}/lib/lib${ffmpeg_lib}.a")
endforeach()

include_directories("${FFMPEG_DIR}/include")
find_library(android_log_lib log)

add_library(ffmpegJNI
            SHARED
            ffmpeg_jni.cc)

target_link_libraries(ffmpegJNI
                      PRIVATE android
                      PRIVATE swresample
                      PRIVATE avcodec
                      PRIVATE avutil
                      PRIVATE ${android_log_lib})

# Additional flags needed for "arm64-v8a" from NDK 23.1.7779620 and above.
# See https://github.com/google/ExoPlayer/issues/9933#issuecomment-1029775358.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()

# Enable 16 KB ELF alignment.
target_link_options(ffmpegJNI
                    PRIVATE "-Wl,-z,max-page-size=16384")
