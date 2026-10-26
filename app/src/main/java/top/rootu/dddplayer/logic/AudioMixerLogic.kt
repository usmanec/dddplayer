package top.rootu.dddplayer.logic

import androidx.media3.common.audio.ChannelMixingMatrix
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Микшер каналов: единый источник матриц для даунмикса в PCM и для перекодирования в AC3/E-AC3.
 *
 * Стерео сводится по RFC 7845, § 5.1.1.5 (Figures 4–9). Для многоканальных выходов RFC матриц не задает,
 * там каналы маршрутизируются по позициям динамиков. Часть правил взята из swr_build_matrix2() FFmpeg
 * (libswresample/rematrix.c, LGPL 2.1+, https://github.com/FFmpeg/FFmpeg/blob/master/libswresample/rematrix.c):
 * тылы и задний центр во фронт и в пару тылов с -3 дБ, общая нормировка матрицы по самой нагруженной колонке.
 *
 * Пресет микшера (усиления групп каналов) применяется после нормировки и может поднять уровень выше 0 дБ:
 * запаса по уровню он не учитывает.
 */
object AudioMixerLogic {

    enum class MixPreset(val id: Int, val titleResId: Int) {
        STANDARD(0, R.string.mix_preset_standard),
        BOOST_CENTER(1, R.string.mix_preset_boost_center),
        NIGHT_MODE(2, R.string.mix_preset_night_mode),
        CUSTOM(3, R.string.mix_preset_custom)
    }

    data class MixParams(
        val front: Float,
        val center: Float,
        val rear: Float,
        val middle: Float,
        val lfe: Float
    )

    /** Позиция канала. Порядок в [ChannelLayout.speakers] - фактический порядок каналов в PCM. */
    enum class Speaker {
        MONO,
        FL, FR,
        FC,
        LFE,
        BL, BR,
        BC,
        SL, SR,
        TFL, TFR,
        TBL, TBR,
        TC
    }

    /** Раскладка каналов. Некорректная раскладка не роняет плеер: проверки делаются при создании констант. */
    data class ChannelLayout(val id: String, val speakers: List<Speaker>) {
        val channelCount: Int get() = speakers.size
    }

    object Layouts {
        val MONO = ChannelLayout("mono", listOf(Speaker.MONO))
        val STEREO = ChannelLayout("stereo", listOf(Speaker.FL, Speaker.FR))
        val THREE_ZERO = ChannelLayout("3.0", listOf(Speaker.FL, Speaker.FR, Speaker.FC))
        val QUAD = ChannelLayout("4.0", listOf(Speaker.FL, Speaker.FR, Speaker.BL, Speaker.BR))
        val FIVE_ZERO = ChannelLayout("5.0", listOf(Speaker.FL, Speaker.FR, Speaker.FC, Speaker.BL, Speaker.BR))
        val FIVE_ONE = ChannelLayout(
            "5.1", listOf(Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.BL, Speaker.BR)
        )

        /** Порядок Android: FL, FR, FC, LFE, BL, BR, BC (FFmpeg-декодер приводит вывод к нему же). */
        val SIX_ONE = ChannelLayout(
            "6.1", listOf(Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.BL, Speaker.BR, Speaker.BC)
        )

        /** Порядок Android: FL, FR, FC, LFE, BL, BR, SL, SR. */
        val SEVEN_ONE = ChannelLayout(
            "7.1",
            listOf(Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.BL, Speaker.BR, Speaker.SL, Speaker.SR)
        )
    }

    // Значения настройки "Каналы на выходе"
    const val CHANNELS_SOURCE = 0
    const val CHANNELS_71 = 1
    const val CHANNELS_51 = 2
    const val CHANNELS_40 = 3
    const val CHANNELS_20 = 4

    /** AC3 и E-AC3 (кодировщик FFmpeg) не больше 5.1. */
    const val MAX_ENCODED_CHANNELS = 6

    private val INV_SQRT2 = (1.0 / sqrt(2.0)).toFloat()
    private val SQRT3_HALF = (sqrt(3.0) / 2.0).toFloat()
    /** Сумма коэффициентов стороны при сведении 5–8 каналов в стерео (RFC: sqrt(2), прежний уровень плеера: 2). */
    private const val STEREO_SUM_5_8 = 2f

    /** Предел усиления канала в пресете: ×4 = +12 дБ. */
    const val MAX_CHANNEL_GAIN = 4f

    // Ползунок канала в дБ: 0 - канал выключен, дальше от MIN_SLIDER_DB до +12 дБ шагом 1 дБ
    const val MIN_SLIDER_DB = -24
    const val MAX_SLIDER_DB = 12
    const val SLIDER_MAX = MAX_SLIDER_DB - MIN_SLIDER_DB + 1

    /** Позиция ползунка для множителя (ближайший целый дБ). */
    fun gainToSlider(gain: Float): Int {
        if (gain <= 0f || !gain.isFinite()) return 0
        val db = kotlin.math.round(gainToDb(gain)).toInt().coerceIn(MIN_SLIDER_DB, MAX_SLIDER_DB)
        return db - MIN_SLIDER_DB + 1
    }

    /** Множитель для позиции ползунка; 0 - канал выключен. */
    fun sliderToGain(position: Int): Float =
        if (position <= 0) 0f else dbToGain((position.coerceAtMost(SLIDER_MAX) - 1 + MIN_SLIDER_DB).toFloat())

    /** дБ позиции ползунка, null - канал выключен. */
    fun sliderToDb(position: Int): Int? = if (position <= 0) null else position.coerceAtMost(SLIDER_MAX) - 1 + MIN_SLIDER_DB

    /** дБ -> множитель громкости канала. */
    fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

    /** Множитель -> дБ (для ползунков микшера); 0 - минус бесконечность. */
    fun gainToDb(gain: Float): Float = if (gain <= 0f) Float.NEGATIVE_INFINITY else 20f * log10(gain)

    /** Суммарный уровень созданного LFE: -10 дБ (компенсация усиления LFE в ресивере). */
    private const val GENERATED_LFE_LEVEL = 0.31622776f

    /** Индекс канала LFE в выходной раскладке, куда LFE собирается из других каналов; -1 - не собирается. */
    fun generatedLfeIndex(input: ChannelLayout, output: ChannelLayout, upmix: Boolean, generateLfe: Boolean): Int =
        if (upmix && generateLfe && Speaker.LFE !in input.speakers && Speaker.MONO !in input.speakers) {
            output.speakers.indexOf(Speaker.LFE)
        } else -1

    /** BC в RFC 7845 Figure 8: (sqrt(3) / 2) * (1 / sqrt(2)) - именно умножение. */
    private val BACK_CENTER_BASE = SQRT3_HALF * INV_SQRT2

    val STANDARD_PARAMS = MixParams(front = 1f, center = 1f, rear = 1f, middle = 1f, lfe = 1f)

    fun getParamsForPreset(preset: MixPreset, repo: SettingsRepository): MixParams = when (preset) {
        MixPreset.STANDARD -> STANDARD_PARAMS
        // Голос: центр +6 дБ (как средняя ступень прежнего усиления центра), остальное тише
        MixPreset.BOOST_CENTER -> MixParams(front = 0.8f, center = 2f, rear = 0.6f, middle = 0.6f, lfe = 0.5f)
        MixPreset.NIGHT_MODE -> MixParams(front = 1f, center = 1.2f, rear = 0.3f, middle = 0.3f, lfe = 0f)
        MixPreset.CUSTOM -> MixParams(
            front = repo.getMixFront(),
            center = repo.getMixCenter(),
            rear = repo.getMixRear(),
            middle = repo.getMixMiddle(),
            lfe = repo.getMixLfe()
        )
    }

    /** Параметры выбранного в настройках пресета. */
    fun currentParams(repo: SettingsRepository): MixParams {
        val preset = MixPreset.entries.find { it.id == repo.getMixPreset() } ?: MixPreset.STANDARD
        return getParamsForPreset(preset, repo).sanitized()
    }

    /** Раскладка входного PCM по числу каналов (порядок Android/Media3), null - неизвестна (9+ каналов). */
    fun inputLayoutFor(channelCount: Int): ChannelLayout? = when (channelCount) {
        1 -> Layouts.MONO
        2 -> Layouts.STEREO
        3 -> Layouts.THREE_ZERO
        4 -> Layouts.QUAD
        5 -> Layouts.FIVE_ZERO
        6 -> Layouts.FIVE_ONE
        7 -> Layouts.SIX_ONE
        8 -> Layouts.SEVEN_ONE
        else -> null
    }

    /**
     * Выходная раскладка для входа по настройке "Каналы на выходе": выбранная раскладка - это предел,
     * каналов не добавляем (стерео остается стерео). Вход шире предела (или шире [maxChannels]) сводится
     * в ближайшую меньшую стандартную раскладку: 7.1 -> 5.1 -> 4.0 -> 2.0.
     * [upmix] - вход уже предела расширяется до выбранной раскладки ("как в источнике" не расширяется).
     */
    fun outputLayoutFor(input: ChannelLayout, setting: Int, maxChannels: Int = 8, upmix: Boolean = false): ChannelLayout {
        val limit = minOf(maxChannels, channelLimit(setting))
        val standard = listOf(Layouts.SEVEN_ONE, Layouts.FIVE_ONE, Layouts.QUAD, Layouts.STEREO)
            .firstOrNull { it.channelCount <= limit } ?: Layouts.STEREO
        if (input.channelCount > limit) return standard
        return if (upmix && setting != CHANNELS_SOURCE && input.channelCount < standard.channelCount) standard else input
    }

    /** Предел каналов для значения настройки. */
    fun channelLimit(setting: Int): Int = when (setting) {
        CHANNELS_51 -> 6
        CHANNELS_40 -> 4
        CHANNELS_20 -> 2
        else -> 8
    }

    /**
     * Маска каналов FFmpeg (AV_CH_*) для раскладки. Каналы стандартных раскладок идут в порядке битов маски,
     * поэтому планарный вход кодировщика совпадает с порядком раскладки. 0 - верхние каналы (нет в Android).
     */
    fun ffmpegMask(layout: ChannelLayout): Long {
        var mask = 0L
        for (speaker in layout.speakers) {
            mask = mask or when (speaker) {
                Speaker.MONO, Speaker.FC -> 0x4L
                Speaker.FL -> 0x1L
                Speaker.FR -> 0x2L
                Speaker.LFE -> 0x8L
                Speaker.BL -> 0x10L
                Speaker.BR -> 0x20L
                Speaker.BC -> 0x100L
                Speaker.SL -> 0x200L
                Speaker.SR -> 0x400L
                else -> return 0L
            }
        }
        return mask
    }

    /** Короткое имя раскладки для бейджа: 2.0, 5.1, 7.1... */
    fun layoutLabel(layout: ChannelLayout): String = when (layout.speakers) {
        Layouts.MONO.speakers -> "1.0"
        Layouts.STEREO.speakers -> "2.0"
        Layouts.QUAD.speakers -> "4.0"
        else -> layout.id
    }

    /** Матрицы стандартных входов 1–8 каналов в [outputLayout] для ChannelMixingAudioProcessor. */
    fun createMatrices(
        repo: SettingsRepository,
        outputLayout: ChannelLayout = Layouts.STEREO
    ): List<ChannelMixingMatrix> = createMatrices(currentParams(repo), outputLayout)

    /** Матрицы входов 1–8 каналов по настройке "Каналы на выходе" (у каждого входа своя выходная раскладка). */
    fun createMatrices(
        params: MixParams,
        channelsSetting: Int,
        maxChannels: Int,
        upmix: Boolean = false,
        generateLfe: Boolean = false
    ): List<ChannelMixingMatrix> =
        (1..8).mapNotNull { count ->
            inputLayoutFor(count)?.let {
                createMatrix(it, outputLayoutFor(it, channelsSetting, maxChannels, upmix), params, upmix, generateLfe)
            }
        }

    fun createMatrices(params: MixParams, outputLayout: ChannelLayout): List<ChannelMixingMatrix> =
        (1..8).mapNotNull { count ->
            inputLayoutFor(count)?.let { createMatrix(it, outputLayout, params) }
        }

    fun createMatrix(
        inputLayout: ChannelLayout,
        outputLayout: ChannelLayout,
        params: MixParams,
        upmix: Boolean = false,
        generateLfe: Boolean = false
    ): ChannelMixingMatrix {
        val inCount = inputLayout.channelCount
        val outCount = outputLayout.channelCount
        return ChannelMixingMatrix(
            inCount, outCount, mixCoefficients(inputLayout, outputLayout, params, upmix, generateLfe)
        )
    }

    /**
     * Коэффициенты в порядке Media3 (row-major): индекс in * outCount + out,
     * строка - входной канал, столбец - выходной. [upmix] - заполнять выходы, которых нет во входе,
     * [generateLfe] - при апмиксе собирать и LFE (его надо пропустить через [LowPassFilter]).
     */
    fun mixCoefficients(
        inputLayout: ChannelLayout,
        outputLayout: ChannelLayout,
        params: MixParams,
        upmix: Boolean = false,
        generateLfe: Boolean = false
    ): FloatArray {
        val p = params.sanitized()
        val outCount = outputLayout.channelCount
        val m = Array(inputLayout.channelCount) { FloatArray(outCount) }
        when (outputLayout.speakers) {
            Layouts.STEREO.speakers -> fillStereoMatrix(inputLayout, p, m)
            Layouts.MONO.speakers -> fillMonoOutput(inputLayout, p, m)
            else -> fillMultichannelMatrix(inputLayout, outputLayout, p, m, upmix, generateLfe)
        }
        val flat = FloatArray(inputLayout.channelCount * outCount)
        for (i in m.indices) for (o in 0 until outCount) flat[i * outCount + o] = m[i][o]
        return flat
    }

    /**
     * Стерео по пропорциям RFC 7845 (Figures 4–9). Для 3–4 каналов коэффициенты каждой стороны нормируются
     * до суммы 1, как в RFC. Для 5–8 RFC нормирует до sqrt(2), но это на 3 дБ тише прежнего даунмикса, и голос
     * терялся: нормируем до 2 (прежний уровень), а от перегрузки защищает [SoftLimiter]. Пресет применяется после.
     */
    private fun fillStereoMatrix(inputLayout: ChannelLayout, params: MixParams, matrix: Array<FloatArray>) {
        val speakers = inputLayout.speakers
        if (speakers == Layouts.MONO.speakers) {
            matrix[0][0] = INV_SQRT2 * params.center
            matrix[0][1] = INV_SQRT2 * params.center
            return
        }
        val base = speakers.map { stereoBase(it) }
        val sumLeft = base.sumOf { it.first.toDouble() }.toFloat()
        val sumRight = base.sumOf { it.second.toDouble() }.toFloat()
        val targetSum = if (speakers.size in 3..4) 1f else STEREO_SUM_5_8
        val scaleLeft = if (sumLeft > targetSum) targetSum / sumLeft else 1f
        val scaleRight = if (sumRight > targetSum) targetSum / sumRight else 1f
        for (i in speakers.indices) {
            val gain = gainFor(speakers[i], params)
            matrix[i][0] = base[i].first * scaleLeft * gain
            matrix[i][1] = base[i].second * scaleRight * gain
        }
    }

    private fun stereoBase(speaker: Speaker): Pair<Float, Float> = when (speaker) {
        Speaker.MONO, Speaker.FC, Speaker.LFE -> INV_SQRT2 to INV_SQRT2
        Speaker.FL -> 1f to 0f
        Speaker.FR -> 0f to 1f
        Speaker.BL, Speaker.SL -> SQRT3_HALF to 0.5f
        Speaker.BR, Speaker.SR -> 0.5f to SQRT3_HALF
        Speaker.BC -> BACK_CENTER_BASE to BACK_CENTER_BASE
        // Верхних каналов в RFC 7845 нет, это своя политика
        Speaker.TFL, Speaker.TBL -> 0.5f to 0f
        Speaker.TFR, Speaker.TBR -> 0f to 0.5f
        Speaker.TC -> 0.5f to 0.5f
    }

    /**
     * Многоканальный выход. Совпадающие позиции переходят как есть, недостающие сводятся в соседние,
     * несуществующих в источнике каналов не выдумываем (центр из фронтов не собираем, LFE без выхода LFE
     * отбрасывается, как lfe_mix_level = 0 в FFmpeg). Затем матрица целиком нормируется по самой нагруженной
     * колонке (как swr_build_matrix2), чтобы сведение не перегружало канал и баланс каналов не менялся,
     * и только после этого применяется пресет.
     */
    private fun fillMultichannelMatrix(
        inputLayout: ChannelLayout,
        outputLayout: ChannelLayout,
        params: MixParams,
        matrix: Array<FloatArray>,
        upmix: Boolean,
        generateLfe: Boolean
    ) {
        val input = inputLayout.speakers
        val output = outputLayout.speakers

        if (input == Layouts.MONO.speakers) {
            // Моно в центр, а без центра - поровну во фронты
            if (Speaker.FC in output) {
                addOutput(matrix, 0, output, Speaker.FC, params.center)
            } else {
                addOutput(matrix, 0, output, Speaker.FL, INV_SQRT2 * params.center)
                addOutput(matrix, 0, output, Speaker.FR, INV_SQRT2 * params.center)
            }
            return
        }

        val handled = mutableSetOf<Int>()
        val inputHasBack = Speaker.BL in input && Speaker.BR in input
        val inputHasSide = Speaker.SL in input && Speaker.SR in input
        val outputHasBack = Speaker.BL in output && Speaker.BR in output
        val outputHasSide = Speaker.SL in output && Speaker.SR in output

        // 7.1 -> 5.1 / 4.0: тыл и бок одной стороны делят выходную пару поровну
        if (inputHasBack && inputHasSide && outputHasBack != outputHasSide) {
            val left = if (outputHasBack) Speaker.BL else Speaker.SL
            val right = if (outputHasBack) Speaker.BR else Speaker.SR
            for ((source, target) in listOf(
                Speaker.BL to left, Speaker.SL to left, Speaker.BR to right, Speaker.SR to right
            )) {
                val i = input.indexOf(source)
                addOutput(matrix, i, output, target, 0.5f)
                handled.add(i)
            }
        }

        for (i in input.indices) {
            if (i in handled) continue
            val speaker = input[i]
            if (speaker in output) {
                addOutput(matrix, i, output, speaker, 1f)
                continue
            }
            when (speaker) {
                Speaker.FC -> {
                    addOutput(matrix, i, output, Speaker.FL, INV_SQRT2)
                    addOutput(matrix, i, output, Speaker.FR, INV_SQRT2)
                }
                Speaker.BC -> {
                    val left = firstPresent(output, Speaker.BL, Speaker.SL)
                    val right = firstPresent(output, Speaker.BR, Speaker.SR)
                    if (left != null && right != null) {
                        addOutput(matrix, i, output, left, INV_SQRT2)
                        addOutput(matrix, i, output, right, INV_SQRT2)
                    } else {
                        // Без тыловой пары задний центр уходит во фронты (swr: surround_mix_level)
                        addOutput(matrix, i, output, Speaker.FL, 0.5f)
                        addOutput(matrix, i, output, Speaker.FR, 0.5f)
                    }
                }
                Speaker.BL, Speaker.SL, Speaker.BR, Speaker.SR -> {
                    val isLeft = speaker == Speaker.BL || speaker == Speaker.SL
                    // Тыл и бок взаимозаменяемы (swr: коэффициент 1), иначе во фронт с -3 дБ
                    val substitute = when (speaker) {
                        Speaker.BL -> Speaker.SL
                        Speaker.BR -> Speaker.SR
                        Speaker.SL -> Speaker.BL
                        else -> Speaker.BR
                    }
                    if (substitute in output) {
                        addOutput(matrix, i, output, substitute, 1f)
                    } else {
                        addOutput(matrix, i, output, if (isLeft) Speaker.FL else Speaker.FR, INV_SQRT2)
                    }
                }
                Speaker.TFL -> addOutput(matrix, i, output, Speaker.FL, 0.5f)
                Speaker.TFR -> addOutput(matrix, i, output, Speaker.FR, 0.5f)
                Speaker.TBL -> firstPresent(output, Speaker.BL, Speaker.SL)?.let { addOutput(matrix, i, output, it, 0.5f) }
                Speaker.TBR -> firstPresent(output, Speaker.BR, Speaker.SR)?.let { addOutput(matrix, i, output, it, 0.5f) }
                Speaker.TC -> if (Speaker.FC in output) {
                    addOutput(matrix, i, output, Speaker.FC, 0.5f)
                } else {
                    addOutput(matrix, i, output, Speaker.FL, 0.25f)
                    addOutput(matrix, i, output, Speaker.FR, 0.25f)
                }
                Speaker.MONO -> {
                    addOutput(matrix, i, output, Speaker.FL, INV_SQRT2)
                    addOutput(matrix, i, output, Speaker.FR, INV_SQRT2)
                }
                Speaker.LFE, Speaker.FL, Speaker.FR -> Unit
            }
        }

        if (upmix) fillMissingOutputs(input, output, matrix)

        // Общая нормировка до пресета: самая нагруженная колонка не больше 1
        var maxColumn = 0f
        for (o in output.indices) {
            var sum = 0f
            for (i in input.indices) sum += abs(matrix[i][o])
            if (sum > maxColumn) maxColumn = sum
        }
        val scale = if (maxColumn > 1f) 1f / maxColumn else 1f
        for (i in input.indices) {
            val gain = gainFor(input[i], params) * scale
            for (o in output.indices) matrix[i][o] *= gain
        }

        if (upmix && generateLfe) fillGeneratedLfe(input, output, params, matrix)
    }

    /**
     * LFE из всех полноценных каналов поровну, суммарно -10 дБ: ресивер воспроизводит LFE на +10 дБ громче
     * основных каналов, а бас в основных каналах остается. Срез выше 120 Гц делает [LowPassFilter]
     * (ресиверы и AC3 тоже ограничивают LFE, но не все ТВ и саундбары). Уровень - ползунок LFE пресета.
     */
    private fun fillGeneratedLfe(input: List<Speaker>, output: List<Speaker>, params: MixParams, matrix: Array<FloatArray>) {
        val lfe = output.indexOf(Speaker.LFE)
        if (lfe < 0 || Speaker.LFE in input) return
        val k = GENERATED_LFE_LEVEL / input.size * params.lfe
        for (i in input.indices) matrix[i][lfe] = k
    }

    /**
     * Простой матричный апмикс (не Pro Logic): пустой центр собирается из фронтов (-6 дБ каждый),
     * пустые тыловые и боковые - из соседнего окружения той же стороны (-3 дБ), а без него - из фронта (-6 дБ).
     * LFE не выдумываем: без бас-менеджмента это только гул.
     */
    private fun fillMissingOutputs(input: List<Speaker>, output: List<Speaker>, matrix: Array<FloatArray>) {
        fun isEmpty(target: Speaker): Boolean {
            val o = output.indexOf(target)
            return o >= 0 && input.indices.all { matrix[it][o] == 0f }
        }
        if (isEmpty(Speaker.FC)) {
            addOutput(matrix, input.indexOf(Speaker.FL), output, Speaker.FC, 0.5f)
            addOutput(matrix, input.indexOf(Speaker.FR), output, Speaker.FC, 0.5f)
        }
        for ((target, neighbour, front) in listOf(
            Triple(Speaker.BL, Speaker.SL, Speaker.FL),
            Triple(Speaker.BR, Speaker.SR, Speaker.FR),
            Triple(Speaker.SL, Speaker.BL, Speaker.FL),
            Triple(Speaker.SR, Speaker.BR, Speaker.FR)
        )) {
            if (!isEmpty(target)) continue
            val n = input.indexOf(neighbour)
            if (n >= 0) addOutput(matrix, n, output, target, INV_SQRT2)
            else addOutput(matrix, input.indexOf(front), output, target, 0.5f)
        }
    }

    /** Сведение в один канал (не RFC). LFE без бас-менеджмента отбрасывается. */
    private fun fillMonoOutput(inputLayout: ChannelLayout, params: MixParams, matrix: Array<FloatArray>) {
        val base = inputLayout.speakers.map { speaker ->
            when (speaker) {
                Speaker.MONO -> 1f
                Speaker.FL, Speaker.FR -> 0.5f
                Speaker.FC -> INV_SQRT2
                Speaker.LFE -> 0f
                else -> 0.25f
            }
        }
        val sum = base.sum()
        val scale = if (sum > 1f) 1f / sum else 1f
        for (i in base.indices) matrix[i][0] = base[i] * scale * gainFor(inputLayout.speakers[i], params)
    }

    private fun addOutput(matrix: Array<FloatArray>, inputIndex: Int, output: List<Speaker>, target: Speaker, k: Float) {
        val o = output.indexOf(target)
        if (inputIndex >= 0 && o >= 0) matrix[inputIndex][o] += k
    }

    private fun firstPresent(speakers: List<Speaker>, vararg candidates: Speaker): Speaker? =
        candidates.firstOrNull { it in speakers }

    private fun gainFor(speaker: Speaker, p: MixParams): Float = when (speaker) {
        Speaker.MONO, Speaker.FC, Speaker.TC -> p.center
        Speaker.FL, Speaker.FR, Speaker.TFL, Speaker.TFR -> p.front
        Speaker.LFE -> p.lfe
        Speaker.SL, Speaker.SR -> p.middle
        Speaker.BL, Speaker.BR, Speaker.BC, Speaker.TBL, Speaker.TBR -> p.rear
    }

    private fun MixParams.sanitized(): MixParams {
        fun clean(value: Float) = if (value.isFinite()) value.coerceIn(0f, MAX_CHANNEL_GAIN) else 1f
        return MixParams(clean(front), clean(center), clean(rear), clean(middle), clean(lfe))
    }
}
