package top.rootu.dddplayer.logic

/** Открытые компоненты в составе приложения ("Приложение → Лицензии"). */
object OpenSourceLicenses {

    /**
     * @param parts что именно используется, если компонент взят не целиком
     *   (названия модулей и декодеров, не переводятся)
     */
    data class Component(val name: String, val license: String, val url: String, val parts: String? = null)

    val components = listOf(
        Component("DDD Video Player", "GPL-3.0", "https://github.com/usmanec/dddplayer"),
        Component(
            "AndroidX Media3", "Apache-2.0", "https://github.com/androidx/media",
            "ExoPlayer, HLS, DASH, SmoothStreaming, RTSP, OkHttp DataSource, UI, Session, decoder_ffmpeg, decoder_av1"
        ),
        Component(
            "AndroidX", "Apache-2.0", "https://developer.android.com/jetpack/androidx",
            "Core, AppCompat, Fragment, Leanback, ConstraintLayout, RecyclerView, Room"
        ),
        Component("Material Components for Android", "Apache-2.0", "https://github.com/material-components/material-components-android"),
        Component("Kotlin", "Apache-2.0", "https://kotlinlang.org", "stdlib, kotlinx.coroutines"),
        Component("OkHttp, Okio", "Apache-2.0", "https://square.github.io/okhttp/"),
        Component("Coil", "Apache-2.0", "https://github.com/coil-kt/coil"),
        Component("SMBJ", "Apache-2.0", "https://github.com/hierynomus/smbj", "SMB client, ASN-ONE"),
        Component("Bouncy Castle", "MIT", "https://www.bouncycastle.org", "bcprov (SMBJ dependency)"),
        Component("SLF4J", "MIT", "https://www.slf4j.org", "slf4j-api (SMBJ dependency)"),
        Component("QR Code generator (Nayuki)", "MIT", "https://www.nayuki.io/page/qr-code-generator-library", "Java library, QR for log links"),
        // Нативные декодеры из app/libs
        Component(
            "FFmpeg", "LGPL-2.1-or-later", "https://ffmpeg.org",
            "libavcodec audio decoders: AAC, AC3, E-AC3, DTS, TrueHD/MLP, FLAC, ALAC, Opus, Vorbis, MP3, AMR, PCM A-law/µ-law; " +
                "AC3 and E-AC3 encoders (multichannel audio output); " +
                "channel mixing rules from libswresample swr_build_matrix2 (AudioMixerLogic)"
        ),
        Component("dav1d", "BSD-2-Clause", "https://code.videolan.org/videolan/dav1d", "AV1 decoder (8/10-bit)"),
        Component("cpu_features", "Apache-2.0", "https://github.com/google/cpu_features", "CPU detection in the AV1 decoder"),
        Component("libdovi (dovi_tool)", "MIT", "https://github.com/quietvoid/dovi_tool", "Dolby Vision RPU conversion (Profile 7 → 8.1)"),
        // Перенесенный код
        Component(
            "Plezy", "GPL-3.0", "https://github.com/edde746/plezy",
            "IEC 61937 packers (DTS-HD, TrueHD/MAT), Dolby Vision P7 conversion track output"
        )
    )
}
