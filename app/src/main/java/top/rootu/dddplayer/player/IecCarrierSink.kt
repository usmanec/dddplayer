// Перенесено из edde746/plezy (GPL-3.0, https://github.com/edde746/plezy). Комментарии на английском оставлены как
// в оригинале, чтобы переносить исправления из plezy диффами; номера #1804/#1988 - issues plezy.
// Изменения DDD (комментарии на русском): сигнатура configure для Media3 1.9, A_DTS, перенос play() между
// делегатами, туннелирование, прежний путь без несущей на выходе, ошибка запасного пути, кодек для бейджа,
// скорость, выбранная пользователем (requestedSpeed), вместо скорости часов.
package top.rootu.dddplayer.player

import android.media.AudioDeviceInfo
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes Dolby TrueHD (#1804) and DTS-HD Master Audio (#1988) through an IEC 61937 carrier, and
 * everything else through the normal sink.
 *
 * Android will not bitstream raw TrueHD on the TV routes measured for #1804: the platform reports
 * `ENCODING_DOLBY_TRUEHD` as offload-only while reporting `ENCODING_IEC61937` at 192kHz/7.1 as
 * bitstream-capable. Raw `ENCODING_DTS_HD` is worse on Fire OS: the route advertises it, the
 * AudioTrack initialises and drains, and the receiver hears silence, because the encoding only
 * means "basic profile" there (#1988). Kodi models the same split and packs the carrier itself;
 * media3 only ever hands Android the raw encodings, at the stream rate. This sink adds the
 * missing path, with one [IecCarrierPacker] per codec.
 *
 * **Why two delegates rather than one sink with the processors held inactive.** The carrier is a
 * bit-exact byte stream that happens to be shaped like PCM. Any sample mutation — downmix, Sonic,
 * silence skipping, float conversion — turns it into full-scale noise at the receiver. Keeping the
 * carrier on a delegate built with an empty [AudioProcessorChain] makes that impossible by
 * construction. The alternative, asserting the normal chain stays inactive, puts the guarantee in a
 * different class from the thing it protects, so adding a processor later would silently break
 * playback in the loudest possible way.
 *
 * [carrierSink] must therefore be built with no audio processors and with an output provider whose
 * `AudioTrack` is forced to `ENCODING_IEC61937`; see [IecCarrierOutput].
 *
 * Selection happens only in [configure]. Persistent controls and the listener are mirrored to both
 * delegates so either can be activated later; per-stream calls go to the active one alone.
 */
@OptIn(UnstableApi::class)
internal class IecCarrierSink(
  private val defaultSink: AudioSink,
  private val carrierSink: AudioSink,
  /**
   * Whether the current route can bitstream this format on the carrier. Per format: the tuple is
   * shared, but a route carrying it does not necessarily advertise every codec riding it.
   */
  private val carrierRouteAvailable: (Format) -> Boolean,
  /** Whether policy currently forbids bitstreaming at all (downmix, normalization, user setting). */
  private val directOutputBlocked: (Format) -> Boolean,
  private val log: ((String, String, String) -> Unit)? = null,
  /**
   * Скорость, выбранная пользователем; без нее - скорость часов. В эфире LivePlaybackSpeedControl сам
   * двигает скорость часов около 1x (0.97–1.05, ровно 1.0 в пределах ±20 мс, не чаще раза в секунду),
   * и по ней TrueHD/DTS-HD уходил бы с несущей на декодер и обратно при каждом переходе через 1.0.
   */
  private val requestedSpeed: (() -> Float)? = null
) : AudioSink {

  private val matPacker = TrueHdMatPacker()
  private val dtsHdPacker = DtsHdIecPacker()

  /** The packer for the configured stream; chosen by mime type in [configure]. */
  private var activePacker: IecCarrierPacker = matPacker

  private var active: AudioSink = defaultSink
  // Читается и из UI-потока (бейдж аудио, isDirectAudioOutput)
  @Volatile
  private var carrierActive = false
  private var loggedCoreStrip = false

  /** A burst the delegate refused; it must be placed before any further input is consumed. */
  private var pendingBurst: ByteBuffer? = null
  private var pendingBurstTimeUs: Long = 0

  /**
   * Anchor for carrier timestamps, and how many carrier frames have been emitted since it.
   *
   * Every burst is a whole number of carrier frames, so timestamps are derived from the cadence
   * rather than from whichever access unit happened to close the burst. Handing the sink the
   * closing unit's own presentation time drifts against the time it derives from written frames,
   * which it reports as a discontinuity on nearly every burst. Counting frames rather than bursts
   * keeps the arithmetic exact for burst durations that are not whole microseconds (a DTS-HD
   * burst is 10666.67us).
   */
  private var carrierAnchorUs: Long = C.TIME_UNSET
  private var carrierFramesSinceAnchor: Long = 0

  private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
  private var sinkListener: AudioSink.Listener? = null

  /**
   * Latched when a stream's bitstream contradicts the shape its container announced, which
   * selection was made from.
   *
   * Deliberately outlives [flush] and [reset]: media3 resets every renderer disabled by a new
   * selection before enabling the replacement (ExoPlayerImplInternal.enableRenderers), and both
   * audio renderers share this sink, so the outgoing renderer's reset lands here in the middle of
   * the very handover this latch exists to cause. Clearing it there would re-offer the carrier and
   * loop straight back into the mismatch.
   *
   * The real boundary is a new media item, which only the caller knows. [beginMediaItem] is that
   * hook; without it one malformed item would cost bitstreaming for every later item sharing the
   * player.
   *
   * It is held as a generation rather than a flag because that hook is called on the app thread
   * while the mismatch is discovered on the playback thread. A late buffer from the outgoing stream
   * can land after the new item began; latching the generation the carrier was configured for makes
   * that write name the stream it belongs to instead of poisoning its successor.
   */
  private val mediaGeneration = AtomicInteger(0)

  /** Generation [mediaGeneration] held when the carrier was last configured; playback thread. */
  private var configuredGeneration = 0

  @Volatile
  private var mismatchGeneration = -1

  /**
   * True when this format should ride the carrier.
   *
   * Speed changes are excluded deliberately: a bitstream cannot be resampled, so anything other
   * than 1.0x has to decode. The same is true of the existing downmix/normalization blocks, which
   * [directOutputBlocked] already reports.
   *
   * The rate family is decided from the format rather than from the packer, which only learns it
   * from the bitstream once buffers are already flowing — far too late for a selection that
   * happens before configure.
   */
  private fun shouldUseCarrier(format: Format): Boolean {
    if (!isTrueHd(format) && !isDtsCandidate(format)) return false
    if (mismatchGeneration == mediaGeneration.get()) return false
    // Туннелирование синхронизирует видео с AudioTrack обычного Sink; несущая его не поддерживает
    if (tunneling) return false
    if (!isCarrierRateFamily(format.sampleRate)) return false
    if (!isUnitSpeed()) return false
    if (directOutputBlocked(format)) return false
    // A_DTS может оказаться обычным ядром: тогда его заберет обычный Sink, и он должен это уметь
    if (format.sampleMimeType == MimeTypes.AUDIO_DTS &&
      defaultSink.getFormatSupport(format) != AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    ) return false
    return carrierRouteAvailable(format)
  }

  /**
   * The 48kHz family rides the 192kHz carrier this path builds. The 44.1kHz family needs a 176.4kHz
   * carrier, which would be a different AudioTrack tuple throughout, so it decodes instead.
   */
  private fun isCarrierRateFamily(sampleRate: Int): Boolean = sampleRate == 48_000 || sampleRate == 96_000 || sampleRate == 192_000

  /** The PCM-shaped format the carrier delegate is configured with. */
  private fun carrierFormat(): Format = Format.Builder()
    .setSampleMimeType(MimeTypes.AUDIO_RAW)
    .setPcmEncoding(C.ENCODING_PCM_16BIT)
    .setChannelCount(IecCarrier.CHANNEL_COUNT)
    .setSampleRate(IecCarrier.SAMPLE_RATE)
    .build()

  /**
   * TrueHD is deliberately binary: the carrier, or decoded PCM. Never media3's own raw TrueHD path.
   *
   * That path builds an `ENCODING_DOLBY_TRUEHD` track at the *stream* rate, which is the
   * configuration #1804 is about — one box takes a single write and never advances its
   * playback head, another freezes for ten seconds, and the third declines it and decodes anyway.
   * Even Kodi's raw fallback is a different thing: it only offers raw TrueHD after verifying it at
   * 192kHz, which media3 never requests. So when the carrier is unavailable — no IEC route, a speed
   * change, downmix, normalization, or a 44.1kHz-family stream — report unsupported and let the
   * bundled FFmpeg decoder take it, exactly as the existing decoded-PCM fallback does.
   *
   * В DDD это действует только на маршруте с несущей (см. forceDecode): без нее TrueHD идет прежним путем,
   * как с выключенной опцией, чтобы включенная опция не отнимала проброс там, где он работал.
   */
  private fun isTrueHd(format: Format): Boolean = format.sampleMimeType == MimeTypes.AUDIO_TRUEHD

  /**
   * DTS-HD is binary only while the carrier route exists: the carrier, or decoded PCM. Falling
   * through to media3's raw `ENCODING_DTS_HD` path on such a route would land on exactly the
   * silent configuration #1988 is about — the route that advertises the carrier and raw DTS-HD at
   * once is the one whose raw path renders silence. Without a carrier route the raw path is the
   * pre-carrier behavior and is left alone: those routes never had the carrier to lose, and some
   * of them bitstream raw DTS-HD genuinely.
   *
   * DTS Express deliberately stays off the carrier: its mime carries a `;profile=lbr` suffix, so
   * the equality below excludes it and it keeps decoding as before.
   */
  private fun isDtsHd(format: Format): Boolean = format.sampleMimeType == MimeTypes.AUDIO_DTS_HD

  /**
   * MKV помечает DTS-HD MA как A_DTS (audio/vnd.dts), и Media3 1.9 не отличает его от ядра DTS.
   * Такой трек пробуем через несущую, а первый блок решает: без HD-части уходим в обычный Sink.
   */
  private fun isDtsCandidate(format: Format): Boolean = isDtsHd(format) || format.sampleMimeType == MimeTypes.AUDIO_DTS

  /** Аргументы последнего configure: нужны, чтобы на месте переключить DTS-ядро в обычный Sink. */
  @Volatile
  private var configuredFormat: Format? = null
  private var configuredBufferSize = 0
  private var configuredOutputChannels: IntArray? = null
  private var dtsExtensionCheckPending = false
  private var playing = false
  private var tunneling = false

  /** Переиспользуемая копия входного буфера: без новой аллокации на каждый сэмпл. */
  private var scratch = ByteArray(0)

  // Выход с несущей, но сейчас она недоступна (скорость, туннелирование, 44.1 кГц, несовпадение потока):
  // сырой TrueHD/DTS-HD на таком выходе молчит, поэтому декодируем. Выход без несущей (Android < 10, нет
  // IEC 61937) - прежний путь, как с выключенной опцией: там сырой поток мог работать и раньше
  private fun forceDecode(format: Format): Boolean =
    (isTrueHd(format) || isDtsHd(format)) && carrierRouteAvailable(format)

  override fun supportsFormat(format: Format): Boolean = when {
    shouldUseCarrier(format) -> true
    forceDecode(format) -> false
    else -> defaultSink.supportsFormat(format)
  }

  override fun getFormatSupport(format: Format): Int = when {
    shouldUseCarrier(format) -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    forceDecode(format) -> AudioSink.SINK_FORMAT_UNSUPPORTED
    else -> defaultSink.getFormatSupport(format)
  }

  // Media3 1.9: configure(Format, bufferSize, outputChannels) вместо AudioSinkConfig из 1.11
  override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
    val useCarrier = shouldUseCarrier(inputFormat)
    if (useCarrier != carrierActive) {
      log?.invoke(
        "info",
        "audio",
        if (useCarrier) {
          "${carrierCodecName(inputFormat)} via IEC 61937 carrier at ${IecCarrier.SAMPLE_RATE}Hz/" +
            "${IecCarrier.CHANNEL_COUNT}ch"
        } else {
          "Leaving the IEC 61937 carrier; audio returns to the normal sink"
        }
      )
    }
    log?.invoke(
      "info",
      "audio",
      "configure ${inputFormat.sampleMimeType} ${inputFormat.channelCount}ch ${inputFormat.sampleRate}Hz: " +
        "carrier=$useCarrier route=${if (isTrueHd(inputFormat) || isDtsCandidate(inputFormat)) carrierRouteAvailable(inputFormat) else null}"
    )
    carrierActive = useCarrier
    configuredGeneration = mediaGeneration.get()
    configuredFormat = inputFormat
    configuredBufferSize = specifiedBufferSize
    configuredOutputChannels = outputChannels
    dtsExtensionCheckPending = useCarrier && inputFormat.sampleMimeType == MimeTypes.AUDIO_DTS
    activePacker = if (isDtsCandidate(inputFormat)) dtsHdPacker else matPacker
    val previous = active
    active = if (useCarrier) carrierSink else defaultSink
    if (previous !== active) {
      // При смене дорожки во время воспроизведения play() приходит раньше configure и достается старому
      // делегату. Без переноса AudioTrack несущей не стартует, блокирующая запись вешает поток плеера
      // (ERROR_CODE_TIMEOUT). Старый делегат освобождаем: прямой HDMI-выход в системе один.
      previous.pause()
      previous.flush()
      if (playing) active.play()
    }
    loggedCoreStrip = false
    discardCarrierState()

    if (useCarrier) {
      carrierSink.configure(carrierFormat(), specifiedBufferSize, null)
    } else {
      defaultSink.configure(inputFormat, specifiedBufferSize, outputChannels)
    }
  }

  private fun carrierCodecName(format: Format): String = if (isDtsCandidate(format)) "DTS-HD" else "TrueHD (MAT)"

  /** Кодек на несущей для бейджа аудио (AudioTrack видит ее как PCM 16 бит 7.1); null - несущая не используется */
  val carrierCodecLabel: String?
    get() = if (carrierActive) configuredFormat?.let { if (isDtsCandidate(it)) "DTS-HD" else "TrueHD" } else null

  override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
    if (!carrierActive) return defaultSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    // A refused burst blocks everything: placing it must come before consuming more input, or the
    // carrier would lose a frame.
    pendingBurst?.let { burst ->
      if (!carrierSink.handleBuffer(burst, pendingBurstTimeUs, 1)) return false
      pendingBurst = null
    }
    if (!buffer.hasRemaining()) return true
    if (carrierAnchorUs == C.TIME_UNSET) carrierAnchorUs = presentationTimeUs

    // One media3 sample holds many access units. The buffer position advances per unit, so a burst
    // the delegate refuses leaves the units behind it in the buffer for the retry rather than
    // dropping them — media3 re-delivers the same buffer whenever this returns false.
    val size = buffer.remaining()
    if (scratch.size < size) scratch = ByteArray(size)
    buffer.duplicate().get(scratch, 0, size)
    val remaining = scratch

    var offset = 0
    while (offset < size) {
      val length = activePacker.accessUnitLength(remaining, offset, size)
      if (activePacker.unsupportedStream) return latchMismatch()
      if (dtsExtensionCheckPending && length > 0) {
        dtsExtensionCheckPending = false
        if (!dtsHdPacker.lastUnitHadExtension) return fallBackToDefaultSink(buffer, presentationTimeUs, encodedAccessUnitCount)
      }
      if (length == 0) {
        // Not a unit boundary we recognise. Consuming the tail keeps the stream moving; trying to
        // resynchronise mid-carrier would splice a frame.
        buffer.position(buffer.limit())
        return true
      }
      val burst = activePacker.packAccessUnit(remaining, offset, length)
      if (activePacker.unsupportedStream) return latchMismatch()
      offset += length
      buffer.position(buffer.position() + length)
      if (burst == null) continue
      if (activePacker === dtsHdPacker && dtsHdPacker.strippedToCore && !loggedCoreStrip) {
        loggedCoreStrip = true
        log?.invoke(
          "warn",
          "audio",
          "DTS-HD MA bitrate exceeds the IEC 61937 carrier; sending the DTS core substream for ~60s"
        )
      }

      val burstTimeUs = carrierAnchorUs + carrierFramesSinceAnchor * 1_000_000L / IecCarrier.SAMPLE_RATE
      carrierFramesSinceAnchor += burst.remaining().toLong() / IecCarrier.BYTES_PER_FRAME
      if (!carrierSink.handleBuffer(burst, burstTimeUs, 1)) {
        pendingBurst = burst
        pendingBurstTimeUs = burstTimeUs
        return false
      }
    }
    return true
  }

  /**
   * The bitstream announced a shape the carrier cannot ride, which selection could not see in the
   * container's Format. The packer emits nothing in that state; consuming here would turn the
   * stream into silence. Leave the unit in the buffer, latch the carrier off, and ask for
   * reselection so the decoder takes over and receives it.
   */
  private fun latchMismatch(): Boolean {
    if (mismatchGeneration != configuredGeneration) {
      mismatchGeneration = configuredGeneration
      log?.invoke(
        "warn",
        "audio",
        if (activePacker === dtsHdPacker) {
          "DTS-HD bitstream cannot ride the 192kHz carrier (44.1kHz-family core or non-standard framing); " +
            "leaving the carrier so it decodes"
        } else {
          "TrueHD bitstream announced a 44.1kHz-family rate its container did not; " +
            "leaving the carrier so it decodes"
        }
      )
      sinkListener?.onAudioCapabilitiesChanged()
    }
    return false
  }

  /**
   * Обычное ядро DTS под меткой A_DTS: несущая ему не нужна. Переселекция трека тут ничего не меняет
   * (поддержка формата та же), поэтому переключаемся на обычный Sink сами, до записи первого блока.
   */
  private fun fallBackToDefaultSink(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
    val format = configuredFormat ?: return false
    log?.invoke("info", "audio", "DTS without HD extension; using the normal sink")
    carrierActive = false
    discardCarrierState()
    carrierSink.flush()
    active = defaultSink
    try {
      defaultSink.configure(format, configuredBufferSize, configuredOutputChannels)
    } catch (e: AudioSink.ConfigurationException) {
      // configure() бросает проверяемое исключение, которого нет в сигнатуре handleBuffer: без обертки оно
      // уходит мимо обработки ошибок рендерера и роняет поток плеера
      throw AudioSink.InitializationException(
        "DTS core fallback to the normal sink failed", AudioTrack.STATE_UNINITIALIZED, format, false, e
      )
    }
    if (playing) defaultSink.play()
    return defaultSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
  }

  override fun getCurrentPositionUs(sourceEnded: Boolean): Long = active.getCurrentPositionUs(sourceEnded)

  override fun playToEndOfStream() {
    // A partially filled burst cannot be emitted; up to one burst is dropped at the end of a stream.
    active.playToEndOfStream()
  }

  override fun isEnded(): Boolean = active.isEnded()

  override fun hasPendingData(): Boolean = pendingBurst != null || active.hasPendingData()

  override fun handleDiscontinuity() {
    discardCarrierState()
    active.handleDiscontinuity()
  }

  override fun play() {
    playing = true
    active.play()
  }

  override fun pause() {
    playing = false
    active.pause()
  }

  override fun flush() {
    discardCarrierState()
    active.flush()
  }

  override fun getAudioTrackBufferSizeUs(): Long = active.getAudioTrackBufferSizeUs()

  private fun discardCarrierState() {
    matPacker.reset()
    dtsHdPacker.reset()
    pendingBurst = null
    // Re-anchor on the next burst: after a seek the carrier restarts from a new media time.
    carrierAnchorUs = C.TIME_UNSET
    carrierFramesSinceAnchor = 0
  }

  // --- Persistent state: mirrored, so either delegate can be activated later ---

  override fun setListener(listener: AudioSink.Listener) {
    sinkListener = listener
    defaultSink.setListener(listener)
    carrierSink.setListener(listener)
  }

  override fun setPlayerId(playerId: PlayerId?) {
    defaultSink.setPlayerId(playerId)
    carrierSink.setPlayerId(playerId)
  }

  override fun setClock(clock: Clock) {
    defaultSink.setClock(clock)
    carrierSink.setClock(clock)
  }

  private fun isUnitSpeed() = (requestedSpeed?.invoke() ?: playbackParameters.speed) == 1f

  // Была ли скорость 1x на прошлом setPlaybackParameters: выбранная скорость меняется вне Sink
  private var lastUnitSpeed = true

  override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
    val wasUnitSpeed = lastUnitSpeed
    this.playbackParameters = playbackParameters
    val isUnitSpeed = isUnitSpeed()
    lastUnitSpeed = isUnitSpeed
    defaultSink.setPlaybackParameters(playbackParameters)
    // A bitstream cannot be resampled, and the carrier delegate has no processor chain to do it
    // with, so it is never handed a speed nothing can apply.
    carrierSink.setPlaybackParameters(
      if (playbackParameters.speed == 1f) playbackParameters else PlaybackParameters.DEFAULT
    )

    // Crossing 1x changes whether a bitstream may ride the carrier, but nothing re-asks on its
    // own: the renderer only consults the sink when capabilities are invalidated. Rebuilding the
    // track selector parameters is not enough either — DefaultTrackSelector skips invalidation
    // when the rebuilt parameters compare equal. This is the path media3 itself uses for a route
    // change, and it reaches onRendererCapabilitiesChanged, so the format is re-evaluated and the
    // stream moves between the carrier and the decoder.
    if (wasUnitSpeed != isUnitSpeed && (carrierActive || isUnitSpeed)) {
      log?.invoke(
        "info",
        "audio",
        "Playback speed ${if (isUnitSpeed) "returned to" else "left"} 1.0x; re-evaluating the bitstream carrier"
      )
      sinkListener?.onAudioCapabilitiesChanged()
    }
  }

  /** Whether the configured stream is currently riding the carrier. */
  val isCarrierActive: Boolean
    get() = carrierActive

  /**
   * While the carrier is live the delegate is deliberately pinned to 1x, but the player polls this
   * through the media clock and adopts whatever it reads. Reporting the delegate's value would push
   * the pinned 1x back and silently undo the user's speed change, so the requested parameters are
   * reported instead; the reselection triggered above then moves the stream onto the decoder, which
   * really can apply them.
   *
   * Поправки LivePlaybackSpeedControl (выбрана 1x) несущую не меняют: битстрим идет на 1x, как AC3 passthrough,
   * и задержку эфира поправка не сокращает.
   */
  override fun getPlaybackParameters(): PlaybackParameters = if (carrierActive) playbackParameters else active.getPlaybackParameters()

  override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
    // Only the normal sink: the carrier delegate is fed a fixed-rate bitstream,
    // and dropping "silent" carrier bytes would break the frame cadence.
    defaultSink.setSkipSilenceEnabled(skipSilenceEnabled)
  }

  override fun getSkipSilenceEnabled(): Boolean = defaultSink.getSkipSilenceEnabled()

  override fun setAudioAttributes(audioAttributes: AudioAttributes) {
    defaultSink.setAudioAttributes(audioAttributes)
    carrierSink.setAudioAttributes(audioAttributes)
  }

  override fun getAudioAttributes(): AudioAttributes? = active.audioAttributes

  override fun setAudioSessionId(audioSessionId: Int) {
    defaultSink.setAudioSessionId(audioSessionId)
    carrierSink.setAudioSessionId(audioSessionId)
  }

  override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
    defaultSink.setAuxEffectInfo(auxEffectInfo)
    carrierSink.setAuxEffectInfo(auxEffectInfo)
  }

  override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
    defaultSink.setPreferredDevice(audioDeviceInfo)
    carrierSink.setPreferredDevice(audioDeviceInfo)
  }

  override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
    defaultSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
    carrierSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
  }

  override fun setVolume(volume: Float) {
    defaultSink.setVolume(volume)
    carrierSink.setVolume(volume)
  }

  override fun enableTunnelingV21() {
    // Tunneling is a decoded-PCM/video-sync arrangement; the carrier never uses it.
    tunneling = true
    defaultSink.enableTunnelingV21()
  }

  override fun disableTunneling() {
    tunneling = false
    defaultSink.disableTunneling()
  }

  override fun reset() {
    playing = false
    discardCarrierState()
    defaultSink.reset()
    carrierSink.reset()
  }

  /**
   * Called by the owner when a new media item starts, which is the only point where
   * "this stream lied about its shape" stops being true and the carrier can be offered again.
   * В DDD: при загрузке плейлиста и при переходе к следующему элементу (onMediaItemTransition). Переход
   * приходит уже после выбора дорожек следующего элемента, поэтому после несовпадения следующий элемент
   * плейлиста может один раз декодироваться - это безопасная сторона.
   */
  fun beginMediaItem() {
    mediaGeneration.incrementAndGet()
  }

  override fun release() {
    mediaGeneration.incrementAndGet()
    discardCarrierState()
    defaultSink.release()
    carrierSink.release()
  }
}
