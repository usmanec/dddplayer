# libffmpegJNI (lib-decoder-ffmpeg)

`libffmpegJNI.so` в `app/libs/lib-decoder-ffmpeg-release.aar` собирается из `jni/`: JNI-обертка Media3 1.9.0
(`ffmpeg_jni.cc`) с одним изменением — декодированный звук отдается в порядке каналов Android
(`getOutputLayout`). Android понимает каналы только по их числу, а FFmpeg отдает свой порядок:

| Источник | Было (порядок FFmpeg) | Стало |
|---|---|---|
| 6.1 | FL FR FC LFE **BC SL SR** — тыловые перепутаны | FL FR FC LFE BL BR BC |
| 2.1 | FL FR LFE — LFE играл как центр | 5.1 (FL FR FC LFE BL BR), LFE на месте |
| 4.0 / 6.0 / 3.0(back) | центр/задний центр путались с тыловыми | 6.1 с правильными местами |

Раскладки, уже совпадающие с Android (стерео, 5.1, 5.1(side), квадро, 7.1), и нестандартные (wide,
верхние каналы, неизвестный порядок) идут как есть. Перестановку делает swr, который в JNI и так работает.
Сигнатуры JNI не менялись, Java-классы AAR остаются от Media3 1.9.0.

- `build_ffmpeg.sh <NDK>` — FFmpeg `release/6.0` с декодерами из `app/libs/README.md` и JNI под 4 ABI, замена .so в AAR.
- CI: workflow «Build FFmpeg decoder» запускается на push в `ffmpeg/` и коммитит AAR в ветку.
