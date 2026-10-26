package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import top.rootu.dddplayer.logic.AudioMixerLogic.ChannelLayout
import top.rootu.dddplayer.logic.AudioMixerLogic.Layouts
import top.rootu.dddplayer.logic.AudioMixerLogic.Speaker

class AudioMixerLogicTest {

    private val standard = AudioMixerLogic.STANDARD_PARAMS

    private fun coef(input: ChannelLayout, output: ChannelLayout, from: Speaker, to: Speaker,
                     params: AudioMixerLogic.MixParams = standard): Float {
        val m = AudioMixerLogic.mixCoefficients(input, output, params)
        return m[input.speakers.indexOf(from) * output.channelCount + output.speakers.indexOf(to)]
    }

    /** Левый выход стерео: коэффициенты входов по порядку раскладки. */
    private fun leftRow(input: ChannelLayout): List<Float> {
        val m = AudioMixerLogic.mixCoefficients(input, Layouts.STEREO, standard)
        return input.speakers.indices.map { m[it * 2] }
    }

    /** [scale] - во сколько раз уровень выше RFC (5–8 каналов сводятся на прежнем уровне плеера, в sqrt(2) раз громче). */
    private fun assertRow(expected: List<Double>, actual: List<Float>, scale: Double = 1.0) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { i, e -> assertEquals("index $i", (e * scale).toFloat(), actual[i], 1e-5f) }
    }

    private val louder = sqrt(2.0)

    @Test
    fun `stereo downmix keeps RFC 7845 proportions`() {
        // Figure 4: L R C
        assertRow(listOf(0.585786, 0.0, 0.414214), leftRow(Layouts.THREE_ZERO))
        // Figure 5: квадро
        assertRow(listOf(0.422650, 0.0, 0.366025, 0.211325), leftRow(Layouts.QUAD))
        // Figure 6: 5.0
        assertRow(listOf(0.460186, 0.0, 0.325401, 0.398533, 0.230093), leftRow(Layouts.FIVE_ZERO), louder)
        // Figure 7: 5.1
        assertRow(listOf(0.374107, 0.0, 0.264534, 0.264534, 0.323986, 0.187053), leftRow(Layouts.FIVE_ONE), louder)
        // Figure 8: 6.1 (BC = sqrt(3)/2 * 1/sqrt(2), а не деление)
        assertRow(
            listOf(0.321953, 0.0, 0.227655, 0.227655, 0.278819, 0.160976, 0.197155),
            leftRow(Layouts.SIX_ONE), louder
        )
        // Figure 9: 7.1
        assertRow(
            listOf(0.274804, 0.0, 0.194316, 0.194316, 0.237987, 0.137402, 0.237987, 0.137402),
            leftRow(Layouts.SEVEN_ONE), louder
        )
    }

    @Test
    fun `stereo to stereo and 51 to 51 are identity`() {
        for (layout in listOf(Layouts.STEREO, Layouts.FIVE_ONE, Layouts.SEVEN_ONE)) {
            val n = layout.channelCount
            val m = AudioMixerLogic.mixCoefficients(layout, layout, standard)
            for (i in 0 until n) for (o in 0 until n) {
                assertEquals(if (i == o) 1f else 0f, m[i * n + o], 1e-6f)
            }
        }
    }

    @Test
    fun `preset gains apply after normalisation`() {
        val voice = AudioMixerLogic.MixParams(front = 0.8f, center = 1.5f, rear = 0.6f, middle = 0.6f, lfe = 0.5f)
        assertEquals(1.5f, coef(Layouts.FIVE_ONE, Layouts.FIVE_ONE, Speaker.FC, Speaker.FC, voice), 1e-6f)
        assertEquals(0.8f, coef(Layouts.FIVE_ONE, Layouts.FIVE_ONE, Speaker.FL, Speaker.FL, voice), 1e-6f)
        // В стерео усиление ложится на коэффициент RFC
        assertEquals(
            (0.264534 * sqrt(2.0) * 1.5).toFloat(), coef(Layouts.FIVE_ONE, Layouts.STEREO, Speaker.FC, Speaker.FL, voice), 1e-5f
        )
        // Центр до +12 дБ (x4), не больше
        val loud = voice.copy(center = 10f)
        assertEquals(AudioMixerLogic.MAX_CHANNEL_GAIN, coef(Layouts.FIVE_ONE, Layouts.FIVE_ONE, Speaker.FC, Speaker.FC, loud), 1e-6f)
        assertEquals(12f, AudioMixerLogic.gainToDb(AudioMixerLogic.dbToGain(12f)), 1e-4f)
    }

    @Test
    fun `71 to 51 shares the rear between back and side`() {
        val back = coef(Layouts.SEVEN_ONE, Layouts.FIVE_ONE, Speaker.BL, Speaker.BL)
        val side = coef(Layouts.SEVEN_ONE, Layouts.FIVE_ONE, Speaker.SL, Speaker.BL)
        assertEquals(0.5f, back, 1e-6f)
        assertEquals(0.5f, side, 1e-6f)
        assertEquals(1f, coef(Layouts.SEVEN_ONE, Layouts.FIVE_ONE, Speaker.FC, Speaker.FC), 1e-6f)
        assertEquals(1f, coef(Layouts.SEVEN_ONE, Layouts.FIVE_ONE, Speaker.LFE, Speaker.LFE), 1e-6f)
    }

    @Test
    fun `51 to quad folds the center and keeps the balance`() {
        val fl = coef(Layouts.FIVE_ONE, Layouts.QUAD, Speaker.FL, Speaker.FL)
        val fc = coef(Layouts.FIVE_ONE, Layouts.QUAD, Speaker.FC, Speaker.FL)
        val bl = coef(Layouts.FIVE_ONE, Layouts.QUAD, Speaker.BL, Speaker.BL)
        // Общая нормировка: самая нагруженная колонка = 1, фронт и тыл ослаблены одинаково
        assertEquals(1f, fl + fc, 1e-5f)
        assertEquals(fl, bl, 1e-6f)
        // LFE без выхода LFE отбрасывается
        assertEquals(0f, coef(Layouts.FIVE_ONE, Layouts.QUAD, Speaker.LFE, Speaker.FL), 1e-6f)
    }

    @Test
    fun `quad to 51 does not invent center or lfe`() {
        val m = AudioMixerLogic.mixCoefficients(Layouts.QUAD, Layouts.FIVE_ONE, standard)
        for (i in 0 until 4) {
            assertEquals(0f, m[i * 6 + 2], 1e-6f)
            assertEquals(0f, m[i * 6 + 3], 1e-6f)
        }
        assertEquals(1f, coef(Layouts.QUAD, Layouts.FIVE_ONE, Speaker.BL, Speaker.BL), 1e-6f)
    }

    @Test
    fun `columns never exceed unity without preset gain`() {
        val outputs = listOf(Layouts.QUAD, Layouts.FIVE_ONE, Layouts.SEVEN_ONE)
        for (count in 1..8) {
            val input = AudioMixerLogic.inputLayoutFor(count)!!
            for (output in outputs) {
                val m = AudioMixerLogic.mixCoefficients(input, output, standard)
                for (o in 0 until output.channelCount) {
                    var sum = 0f
                    for (i in 0 until count) sum += m[i * output.channelCount + o]
                    assertTrue("$count -> ${output.id} column $o = $sum", sum <= 1f + 1e-5f)
                }
            }
        }
    }

    @Test
    fun `output layout follows the setting and the channel limit`() {
        val six = AudioMixerLogic.inputLayoutFor(7)!!
        assertEquals(Layouts.SIX_ONE, AudioMixerLogic.outputLayoutFor(six, AudioMixerLogic.CHANNELS_SOURCE))
        assertEquals(
            Layouts.FIVE_ONE,
            AudioMixerLogic.outputLayoutFor(six, AudioMixerLogic.CHANNELS_SOURCE, AudioMixerLogic.MAX_ENCODED_CHANNELS)
        )
        assertEquals(
            Layouts.FIVE_ONE,
            AudioMixerLogic.outputLayoutFor(Layouts.SEVEN_ONE, AudioMixerLogic.CHANNELS_71, AudioMixerLogic.MAX_ENCODED_CHANNELS)
        )
        assertEquals(Layouts.QUAD, AudioMixerLogic.outputLayoutFor(Layouts.FIVE_ONE, AudioMixerLogic.CHANNELS_40))
        assertEquals(Layouts.STEREO, AudioMixerLogic.outputLayoutFor(Layouts.FIVE_ONE, AudioMixerLogic.CHANNELS_20))
        assertEquals(Layouts.STEREO, AudioMixerLogic.outputLayoutFor(Layouts.FIVE_ONE, AudioMixerLogic.CHANNELS_SOURCE, 2))
        // Выбранная раскладка - предел: каналов не добавляем
        assertEquals(Layouts.STEREO, AudioMixerLogic.outputLayoutFor(Layouts.STEREO, AudioMixerLogic.CHANNELS_51))
        assertEquals(Layouts.FIVE_ONE, AudioMixerLogic.outputLayoutFor(Layouts.FIVE_ONE, AudioMixerLogic.CHANNELS_71))
    }

    @Test
    fun `upmix expands to the chosen layout`() {
        assertEquals(
            Layouts.FIVE_ONE,
            AudioMixerLogic.outputLayoutFor(Layouts.STEREO, AudioMixerLogic.CHANNELS_51, upmix = true)
        )
        // "Как в источнике" не расширяется
        assertEquals(
            Layouts.STEREO,
            AudioMixerLogic.outputLayoutFor(Layouts.STEREO, AudioMixerLogic.CHANNELS_SOURCE, upmix = true)
        )
        val m = AudioMixerLogic.mixCoefficients(Layouts.STEREO, Layouts.FIVE_ONE, standard, upmix = true)
        // FL: в FL целиком, в центр и левый тыл по -6 дБ, в LFE ничего
        assertEquals(listOf(1f, 0f, 0.5f, 0f, 0.5f, 0f), m.slice(0 until 6))
        // Квадро -> 7.1: боковые из тылов той же стороны
        val q = AudioMixerLogic.mixCoefficients(Layouts.QUAD, Layouts.SEVEN_ONE, standard, upmix = true)
        assertEquals(0.70710677f, q[2 * 8 + 6], 1e-6f)
        assertEquals(0f, q[2 * 8 + 7], 1e-6f)
    }

    @Test
    fun `generated lfe takes all channels at minus 10 dB and follows the preset`() {
        val m = AudioMixerLogic.mixCoefficients(Layouts.STEREO, Layouts.FIVE_ONE, standard, upmix = true, generateLfe = true)
        assertEquals(0.31622776f / 2, m[0 * 6 + 3], 1e-6f)
        assertEquals(0.31622776f / 2, m[1 * 6 + 3], 1e-6f)
        assertEquals(3, AudioMixerLogic.generatedLfeIndex(Layouts.STEREO, Layouts.FIVE_ONE, upmix = true, generateLfe = true))
        // Ночной режим (LFE 0) - сабвуфер молчит
        val night = standard.copy(lfe = 0f)
        val n = AudioMixerLogic.mixCoefficients(Layouts.STEREO, Layouts.FIVE_ONE, night, upmix = true, generateLfe = true)
        assertEquals(0f, n[3], 1e-6f)
        // Настоящий LFE источника не трогаем
        assertEquals(-1, AudioMixerLogic.generatedLfeIndex(Layouts.FIVE_ONE, Layouts.SEVEN_ONE, upmix = true, generateLfe = true))
        val real = AudioMixerLogic.mixCoefficients(Layouts.FIVE_ONE, Layouts.SEVEN_ONE, standard, upmix = true, generateLfe = true)
        assertEquals(0f, real[0 * 8 + 3], 1e-6f)
        assertEquals(1f, real[3 * 8 + 3], 1e-6f)
    }

    @Test
    fun `ffmpeg masks follow the layout`() {
        assertEquals(0x3L, AudioMixerLogic.ffmpegMask(Layouts.STEREO))
        assertEquals(0x33L, AudioMixerLogic.ffmpegMask(Layouts.QUAD))
        assertEquals(0x37L, AudioMixerLogic.ffmpegMask(Layouts.FIVE_ZERO))
        assertEquals(0x3FL, AudioMixerLogic.ffmpegMask(Layouts.FIVE_ONE))
        assertEquals(0x63FL, AudioMixerLogic.ffmpegMask(Layouts.SEVEN_ONE))
    }
}
