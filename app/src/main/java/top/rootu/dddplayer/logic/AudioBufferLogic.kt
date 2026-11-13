package top.rootu.dddplayer.logic

/** Размер буфера AudioTrack. */
object AudioBufferLogic {

    const val MAX_PCM_BUFFER_BYTES = 256 * 1024

    /**
     * Ограничивает [size] сверху числом [maxBytes], кратным размеру кадра [pcmFrameSize]: AudioTrack отклоняет
     * буфер, размер которого не делится на кадр. Кадр 5.1 (6 каналов, 16 бит) - 12 байт, а 262144 на 12
     * не делится (остаток 4), и звук 5.1 пропадал с ошибкой инициализации AudioTrack.
     * Размер, который не превышает потолок, плеер уже округлил до целых кадров.
     */
    fun capToWholeFrames(size: Int, pcmFrameSize: Int, maxBytes: Int = MAX_PCM_BUFFER_BYTES): Int {
        if (size <= maxBytes) return size
        return if (pcmFrameSize > 0) maxBytes / pcmFrameSize * pcmFrameSize else maxBytes
    }
}
