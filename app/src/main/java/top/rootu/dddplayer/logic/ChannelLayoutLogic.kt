package top.rootu.dddplayer.logic

import kotlin.math.abs
import kotlin.math.pow

/**
 * Раскладки каналов и матрицы микшера для перекодирования многоканального звука (AC3/E-AC3):
 * из N входных каналов в выходную раскладку, с усилением центра (голос, перевод).
 */
object ChannelLayoutLogic {

    /** Позиции каналов. Порядок совпадает с порядком битов маски в Android и FFmpeg. */
    enum class Position { FL, FR, FC, LFE, BL, BR, BC, SL, SR }

    /**
     * Выходная раскладка AC3. [ffmpegMask] - маска каналов FFmpeg (AV_CH_*):
     * порядок каналов в ней совпадает с порядком Android (FL FR FC LFE BL BR).
     */
    enum class Layout(val positions: List<Position>, val ffmpegMask: Long) {
        STEREO(listOf(Position.FL, Position.FR), 0x3L),
        QUAD(listOf(Position.FL, Position.FR, Position.BL, Position.BR), 0x33L),
        SURROUND_51(
            listOf(Position.FL, Position.FR, Position.FC, Position.LFE, Position.BL, Position.BR), 0x3FL
        );

        val channelCount: Int get() = positions.size
    }

    // Значения настройки "Каналы на выходе"
    const val LAYOUT_AUTO = 0
    const val LAYOUT_51 = 1
    const val LAYOUT_40 = 2
    const val LAYOUT_20 = 3

    private const val MINUS_3DB = 0.70710677f
    private const val MINUS_6DB = 0.5f

    /** Позиции входных каналов в порядке Media3/Android для числа каналов, null - раскладка неизвестна. */
    fun inputPositions(channelCount: Int): List<Position>? = when (channelCount) {
        1 -> listOf(Position.FC)
        2 -> listOf(Position.FL, Position.FR)
        3 -> listOf(Position.FL, Position.FR, Position.FC)
        4 -> listOf(Position.FL, Position.FR, Position.BL, Position.BR)
        5 -> listOf(Position.FL, Position.FR, Position.FC, Position.BL, Position.BR)
        6 -> listOf(Position.FL, Position.FR, Position.FC, Position.LFE, Position.BL, Position.BR)
        7 -> listOf(Position.FL, Position.FR, Position.FC, Position.LFE, Position.BL, Position.BR, Position.BC)
        8 -> listOf(
            Position.FL, Position.FR, Position.FC, Position.LFE,
            Position.BL, Position.BR, Position.SL, Position.SR
        )
        else -> null
    }

    /**
     * Выходная раскладка для входа и настройки. "Авто" сохраняет раскладку источника (не больше 5.1):
     * стерео остается стерео, квадро - квадро, все с центром или больше 4 каналов - 5.1.
     */
    fun targetLayout(inputChannels: Int, setting: Int): Layout = when (setting) {
        LAYOUT_51 -> Layout.SURROUND_51
        LAYOUT_40 -> Layout.QUAD
        LAYOUT_20 -> Layout.STEREO
        else -> when (inputChannels) {
            1, 2 -> Layout.STEREO
            4 -> Layout.QUAD
            else -> Layout.SURROUND_51
        }
    }

    /** Усиление центра в дБ -> множитель. */
    fun dbToGain(db: Int): Float = 10f.pow(db / 20f)

    /**
     * Матрица микшера [вход][выход] (индекс in * outCount + out).
     * Совпадающие каналы переходят как есть, недостающие сводятся по ITU-R BS.775 (-3 дБ),
     * при повышении центр собирается из фронтов. Сводимые в один выход вклады нормируются,
     * чтобы даунмикс не перегружал канал; усиление центра применяется после нормировки.
     */
    fun mixMatrix(input: List<Position>, output: Layout, centerGain: Float): FloatArray {
        val out = output.positions
        val outCount = out.size
        val matrix = FloatArray(input.size * outCount)
        fun add(inIndex: Int, pos: Position, weight: Float) {
            val o = out.indexOf(pos)
            if (o >= 0) matrix[inIndex * outCount + o] += weight
        }
        val hasBack = Position.BL in out
        val hasSide = Position.SL in input
        input.forEachIndexed { i, pos ->
            when {
                pos in out && !(pos == Position.BL || pos == Position.BR) -> add(i, pos, 1f)
                pos == Position.FC -> { add(i, Position.FL, MINUS_3DB); add(i, Position.FR, MINUS_3DB) }
                pos == Position.LFE -> { add(i, Position.FL, MINUS_6DB); add(i, Position.FR, MINUS_6DB) }
                pos == Position.BL || pos == Position.BR -> {
                    val right = pos == Position.BR
                    when {
                        // 7.1 -> 5.1: тыловые и боковые делят выходной тыл поровну
                        hasBack && hasSide -> add(i, if (right) Position.BR else Position.BL, MINUS_3DB)
                        hasBack -> add(i, pos, 1f)
                        else -> add(i, if (right) Position.FR else Position.FL, MINUS_3DB)
                    }
                }
                pos == Position.SL || pos == Position.SR -> {
                    val right = pos == Position.SR
                    if (hasBack) add(i, if (right) Position.BR else Position.BL, MINUS_3DB)
                    else add(i, if (right) Position.FR else Position.FL, MINUS_3DB)
                }
                pos == Position.BC -> if (hasBack) {
                    add(i, Position.BL, MINUS_3DB); add(i, Position.BR, MINUS_3DB)
                } else {
                    add(i, Position.FL, MINUS_6DB); add(i, Position.FR, MINUS_6DB)
                }
            }
        }
        // Повышение: центр из фронтов, если его нет во входе (LFE и тыл не выдумываем)
        if (Position.FC in out && Position.FC !in input) {
            input.forEachIndexed { i, pos ->
                if (pos == Position.FL || pos == Position.FR) add(i, Position.FC, MINUS_6DB)
            }
        }
        // Нормировка выходов, в которые сводится больше одного канала
        for (o in 0 until outCount) {
            var sum = 0f
            for (i in input.indices) sum += abs(matrix[i * outCount + o])
            if (sum > 1f) for (i in input.indices) matrix[i * outCount + o] /= sum
        }
        // Усиление голоса: вклад центрального канала во все выходы
        val fc = input.indexOf(Position.FC)
        if (fc >= 0 && centerGain != 1f) {
            for (o in 0 until outCount) matrix[fc * outCount + o] *= centerGain
        }
        // Центр, собранный из фронтов, тоже голос
        val outFc = out.indexOf(Position.FC)
        if (fc < 0 && outFc >= 0 && centerGain != 1f) {
            for (i in input.indices) matrix[i * outCount + outFc] *= centerGain
        }
        return matrix
    }
}
