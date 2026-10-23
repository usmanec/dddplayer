package top.rootu.dddplayer.player

import android.util.Log
import java.nio.ByteBuffer

/** JNI кодировщика AC3/E-AC3 (libdddaudioenc.so, исходники в audioenc/jni). */
object AudioEncoderJni {

    /** Библиотека загружена. Без нее (не собрана под ABI) перекодирование выключено. */
    val isAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("dddaudioenc") }
            .onFailure { Log.w("AudioEncoderJni", "libdddaudioenc not loaded: ${it.message}") }
            .isSuccess
    }

    /** @return дескриптор кодировщика или 0, если его не удалось создать */
    @JvmStatic external fun nCreate(eac3: Boolean, sampleRate: Int, channelMask: Long, bitRate: Int): Long

    /** Отсчетов на канал в одном кадре (1536 для AC3 и E-AC3). */
    @JvmStatic external fun nFrameSize(handle: Long): Int

    /**
     * Кодирует кадр: [input] - планарный float длиной каналы * frameSize, [output] - direct буфер.
     * @return размер кадра, 0 - кадра пока нет, < 0 - ошибка
     */
    @JvmStatic external fun nEncode(handle: Long, input: FloatArray, output: ByteBuffer): Int

    @JvmStatic external fun nRelease(handle: Long)
}
