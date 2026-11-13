package top.rootu.dddplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SpeedDecodeSinkTest {
    private val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()
    private val dts = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build()
    private val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).build()

    // HDMI, пропускающий AC3 как есть (DTS нет); настройка не трогает настоящий AudioTrack
    private val hdmi = object : ForwardingAudioSink(DefaultAudioSink.Builder(RuntimeEnvironment.getApplication()).build()) {
        override fun getFormatSupport(format: Format) =
            if (format.sampleMimeType == MimeTypes.AUDIO_DTS) AudioSink.SINK_FORMAT_UNSUPPORTED else AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {}
    }
    private var signals = 0
    private var requested = 1f // скорость, выбранная пользователем
    private val sink = SpeedDecodeSink(hdmi, { requested }).apply {
        setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() {}
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}
            override fun onAudioCapabilitiesChanged() { signals++ }
        })
    }

    private fun speed(value: Float) {
        requested = value
        sink.setPlaybackParameters(PlaybackParameters(value))
    }

    @Test
    fun passthroughMovesToTheDecoderAndBack() {
        sink.configure(ac3, 0, null)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(ac3))
        speed(1.5f)
        assertEquals(1, signals)
        // плеер переспрашивает: битстрим нельзя, PCM можно
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(ac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm))
        sink.configure(pcm, 0, null)
        speed(2f)
        assertEquals(1, signals) // 1.5x -> 2x: звук уже декодируется
        speed(1f)
        assertEquals(2, signals)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(ac3))
    }

    @Test
    fun decodedAudioIsLeftAlone() {
        sink.configure(pcm, 0, null)
        speed(1.5f)
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(dts)) // и так декодируется
        speed(1f)
        assertEquals(0, signals)
    }

    @Test
    fun liveSpeedControlDoesNotMoveTheAudio() {
        sink.configure(ac3, 0, null)
        sink.setPlaybackParameters(PlaybackParameters(1.02f)) // эфир догоняет поток, пользователь на 1x
        sink.setPlaybackParameters(PlaybackParameters(1f))
        assertEquals(0, signals)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(ac3))
    }

    @Test
    fun startedAtAnotherSpeedDecodesFromTheStart() {
        requested = 1.25f // скорость выбрана до выбора дорожек
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(ac3))
        sink.configure(pcm, 0, null)
        sink.setPlaybackParameters(PlaybackParameters(1.25f))
        assertEquals(0, signals)
        speed(1f)
        assertEquals(1, signals) // обратно в битстрим
    }
}
