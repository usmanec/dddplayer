package top.rootu.dddplayer.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import top.rootu.dddplayer.logic.AudioPassthroughLogic

/** Что текущий аудиовыход принимает битстримом: для пунктов проброса и перекодирования в настройках. */
@OptIn(UnstableApi::class)
object AudioOutputCapabilities {

    /** Группы форматов (AudioPassthroughLogic.*), которые выход примет как есть; [iec] - с несущей IEC 61937. */
    fun passthroughFormats(context: Context, iec: Boolean): Set<String> {
        val caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
        return AudioPassthroughLogic.ALL_FORMATS.filter { key ->
            AudioPassthroughLogic.encodings(key).any { caps.supportsEncoding(it) } ||
                (iec && (key == AudioPassthroughLogic.DTS_HD || key == AudioPassthroughLogic.TRUEHD) &&
                    IecCarrierOutput.isRouteAvailable(context, key == AudioPassthroughLogic.DTS_HD))
        }.toSet()
    }

    /** Кодировщик AC3/E-AC3 собран под это устройство. */
    val isEncoderAvailable: Boolean get() = AudioEncoderJni.isAvailable
}
