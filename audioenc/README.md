# libdddaudioenc

JNI-обертка кодировщиков AC3 и E-AC3 из FFmpeg для перекодирования многоканального звука в битстрим
(настройка «Многоканальный звук на выходе», `TranscodingAudioSink`).

- `jni/ddd_audioenc.c` — JNI (`top.rootu.dddplayer.player.AudioEncoderJni`): на входе планарный float на один кадр
  (1536 отсчетов), на выходе кадр AC3/E-AC3. Раскладка — маска каналов FFmpeg (`AV_CH_*`), не больше 5.1.
- `build_audioenc.sh <NDK>` — собирает статический FFmpeg (`release/6.0`, только кодировщики `ac3` и `eac3`)
  и JNI под arm64-v8a, armeabi-v7a, x86_64, x86 в `app/src/main/jniLibs/<abi>/libdddaudioenc.so`.
  Символы FFmpeg не экспортируются (`--exclude-libs,ALL`): в приложении есть и `libffmpegJNI.so` со своей копией.
- В CI: workflow «Build audio encoder» запускается на push в `audioenc/` и коммитит библиотеки в ветку.

FFmpeg — LGPL-2.1-or-later (указан в «Приложение → Лицензии»).
