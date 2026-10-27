package top.rootu.dddplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Display
import android.view.accessibility.CaptioningManager
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.rootu.dddplayer.App.Companion.USER_AGENT
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AudioMixerLogic
import top.rootu.dddplayer.logic.AudioPassthroughLogic
import top.rootu.dddplayer.logic.M3uParser
import top.rootu.dddplayer.logic.StreamTypeLogic
import top.rootu.dddplayer.logic.UnifiedMetadataReader
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.utils.MediaFormatHelper
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import androidx.media3.common.MediaItem as Media3MediaItem

private const val MAX_PROBE_BYTES = 32 * 1024 * 1024 // Ограничение размера проверяемого плейлиста
private const val DAV1D_MAX_FRAME_DELAY = 4 // Кадров в работе у dav1d (по умолчанию 2)
private const val DAV1D_INPUT_BUFFERS = 8
private const val DAV1D_OUTPUT_BUFFERS = 6
// Пауза между выключением и включением звука при пересоздании аудиовыхода
private const val AUDIO_REFRESH_DELAY_MS = 300L

// Счетчик для уникальных ID MediaSession (общий для всех экземпляров PlayerManager в процессе)
private val sessionCounter = AtomicInteger()

class PlayerManager(
    context: Context,
    listener: Player.Listener
) {
    private var playerListener: Player.Listener? = listener
    private val appContext = context.applicationContext
    private val settingsRepo = SettingsRepository.getInstance(appContext)

    init {
        // Учетные данные сетевых папок SMB берутся из настроек
        SmbConnections.init(appContext)
    }

    var exoPlayer: ExoPlayer? = null
        private set

    private var mediaSession: MediaSession? = null

    // Для расчета заполненности буфера, заданного в МБ
    private var bufferAllocator: DefaultAllocator? = null
    private var networkBufferBytes: Int = C.LENGTH_UNSET
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var iecSink: IecCarrierSink? = null
    private var transcodingSink: TranscodingAudioSink? = null

    /**
     * Проброс и перекодирование выключены до конца сеанса плеера: битстрим нельзя ускорить,
     * и для смены скорости звук декодируется в PCM. Применяется при пересоздании плеера.
     */
    var bitstreamDisabledForSession = false

    // Bluetooth после звонка или голосового ассистента: пересоздаем аудиовыход, см. AudioRouteWatcher
    private val audioRouteWatcher = AudioRouteWatcher(appContext) { refreshAudioOutput("voice mode ended") }
    private val audioRefreshHandler = Handler(android.os.Looper.getMainLooper())

    /** Туннелирование выключено до конца сеанса плеера (с ним не работает масштаб). */
    var tunnelingDisabledForSession = false

    // Состояние для восстановления
    private var currentWindowIndex = 0
    private var currentPosition = 0L
    private var currentMediaItems: List<Media3MediaItem> = emptyList()
    // Заголовки (Referer, Cookie, User-Agent и т.п.) каждого видео по mediaId: у элементов плейлиста они свои
    private var headersByMediaId: Map<String, Map<String, String>> = emptyMap()
    private var playWhenReady = true

    // Пишется из IO-потока парсера, читается из потоков загрузки и главного потока
    @Volatile
    private var currentTrackInfo: Map<Int, UnifiedMetadataReader.TrackInfo> = emptyMap()
    var onMetadataAvailable: (() -> Unit)? = null
    var onPlayerCreated: ((ExoPlayer) -> Unit)? = null
    var onVideoFormatChanged: ((Format) -> Unit)? = null
    var onAudioOutputFormatChanged: ((String) -> Unit)? = null
    // Туннельный режим реально включился (декодеры и аудиовыход его поддержали)
    var onTunnelingChanged: ((Boolean) -> Unit)? = null
    // "Стоп" извне (карточка "Сейчас играет" на Android TV, пульт, ассистент): закрываем плеер, а не замираем на кадре
    var onStopRequested: (() -> Unit)? = null

    /**
     * Звук идет на HDMI битстримом. Такой выход в системе один: плеер в фоне держит его,
     * и следующий экземпляр плеера получает ошибку инициализации AudioTrack.
     */
    @Volatile
    var isDirectAudioOutput = false
        private set

    private val resolvedMediaTypes = ConcurrentHashMap<String, String>()

    private val trustAllCerts = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
    object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("TLS")
    private fun socketFactory(): SSLSocketFactory {
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext.socketFactory
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        // TCP keepalive: соединение на паузе не выкидывается роутером, мертвое замечается сразу
        .socketFactory(KeepAliveSocketFactory())
        // Разрешаем самоподписанные сертификаты (для пользовательских серверов с видео)
        .sslSocketFactory(socketFactory(), trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        // Запишим правильный MimeType от сервера, чтобы перезапустить видео при ошибке контейнера
        // Используем Application Interceptor, чтобы поймать оригинальный URL
        .addInterceptor { chain ->
            val request = chain.request()
            val originalUrl = request.url.toString()

            // Выполняем запрос (включая все редиректы)
            val response = chain.proceed(request)

            // Получаем заголовки и финальный URL (после редиректов)
            val contentType = response.header("Content-Type")
            val finalUrl = response.request.url.toString()

            val exoMimeType = StreamTypeLogic.fromResponse(contentType, finalUrl.toUri().path)

            // Сохраняем найденный тип
            if (exoMimeType != null) {
                resolvedMediaTypes[originalUrl] = exoMimeType
            }

            response
        }
        .build()

    // Фабрика для HTTP (сеть). User-Agent не задаем: OkHttpDataSource добавляет его вторым заголовком
    // поверх User-Agent источника, и часть серверов отвечает 403. Он приходит в заголовках видео (requestHeaders)
    private val baseHttpFactory = OkHttpDataSource.Factory(okHttpClient)

    // Универсальная фабрика, которая умеет работать с content://, file:// и http://
    // Сетевые запросы идут через baseHttpFactory; после долгого простоя (пауза) соединение переоткрывается
    private val defaultDataSourceFactory =
        DefaultDataSource.Factory(appContext, IdleReconnectDataSource.Factory(baseHttpFactory))

    // Ответы, уже скачанные при проверке ссылки на M3U: плеер получает их без повторного запроса
    private val prefetchStore = PrefetchStore()
    // smb:// (сетевые папки стартового экрана) читает SmbDataSource, остальное - универсальная фабрика
    private val prefetchingDataSourceFactory =
        PrefetchingDataSource.Factory(SmbRoutingDataSource.Factory(defaultDataSourceFactory), prefetchStore)

    // Типы, определенные при проверке ссылки (переживают очистку resolvedMediaTypes в loadPlaylist)
    private val probedMediaTypes = ConcurrentHashMap<String, String>()

    /** Заголовки запросов видео: свои заголовки источника и наш User-Agent, если источник его не задал. */
    private fun requestHeaders(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) headers
        else headers + ("User-Agent" to USER_AGENT)

    /**
     * Фабрика источников одного видео. Заголовки добавляются в каждый запрос этого видео
     * (плейлист HLS, сегменты, субтитры, чтение метаданных), а не переключаются глобально
     * при смене элемента: следующий элемент плеер начинает грузить раньше, чем приходит onMediaItemTransition.
     */
    private fun createParsingDataSourceFactory(headers: Map<String, String> = emptyMap()): ParsingDataSourceFactory {
        val itemHeaders = requestHeaders(headers)
        return ParsingDataSourceFactory(
            upstreamFactory = ResolvingDataSource.Factory(prefetchingDataSourceFactory) { dataSpec ->
                dataSpec.withAdditionalHeaders(itemHeaders)
            },
            onMetadataParsed = { metadataMap ->
                currentTrackInfo = metadataMap
                onMetadataAvailable?.invoke()
            },
            isMetadataParsed = { currentTrackInfo.isNotEmpty() }
        )
    }

    private val tsExtractorFlags = DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM

    private val defaultExtractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(tsExtractorFlags)
        .setTsExtractorTimestampSearchBytes(5000 * TsExtractor.TS_PACKET_SIZE)
        .setMp4ExtractorFlags(
            Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES or
                    Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265
        )
        // Включаем поиск метаданных в начале каждого чанка для MKV
        .setMatroskaExtractorFlags(0)
        .setConstantBitrateSeekingEnabled(true)

    // Dolby Vision Profile 7 -> 8.1: режим выбирается в initializePlayer(), MKV/MP4 оборачиваются при создании
    @Volatile
    private var dvConversionMode = DvConversionMode.DISABLED

    // Опция "HDR10+" выключена: вырезаем динамические метаданные HDR10+ из видео (играет как HDR10)
    @Volatile
    private var stripHdr10Plus = false

    private val extractorsFactory = object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> =
            wrapHdr10Plus(wrapDovi(defaultExtractorsFactory.createExtractors()))

        override fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            wrapHdr10Plus(wrapDovi(defaultExtractorsFactory.createExtractors(uri, responseHeaders)))

        // Настройки субтитров и GOP передаем в исходную фабрику, иначе DefaultMediaSourceFactory их потеряет
        override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory =
            apply { defaultExtractorsFactory.experimentalSetTextTrackTranscodingEnabled(enabled) }

        override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory =
            apply { defaultExtractorsFactory.setSubtitleParserFactory(subtitleParserFactory) }

        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags: Int): ExtractorsFactory =
            apply { defaultExtractorsFactory.experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags) }
    }

    private fun wrapDovi(extractors: Array<Extractor>): Array<Extractor> {
        val mode = dvConversionMode
        if (mode == DvConversionMode.DISABLED) return extractors
        return Array(extractors.size) { i ->
            when (extractors[i].underlyingImplementation) {
                is MatroskaExtractor, is Mp4Extractor, is FragmentedMp4Extractor -> DoviExtractorWrapper(extractors[i], mode)
                else -> extractors[i]
            }
        }
    }

    private fun wrapHdr10Plus(extractors: Array<Extractor>): Array<Extractor> {
        if (!stripHdr10Plus) return extractors
        return Array(extractors.size) { i ->
            when (extractors[i].underlyingImplementation) {
                is MatroskaExtractor, is Mp4Extractor, is FragmentedMp4Extractor, is TsExtractor ->
                    Hdr10PlusExtractorWrapper(extractors[i])
                else -> extractors[i]
            }
        }
    }

    private val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 5
    }

    fun initializePlayer() {
        if (exoPlayer != null) {
            releasePlayer(isFinalRelease = false, saveState = true)
        }

        // === Кастомный MediaCodecSelector для Dolby Vision Fallback ===
        val dvMode = settingsRepo.getDvMode()
        val dvSupported by lazy { isDolbyVisionSupported() }
        // Конвертация нужна только при DV экране и декодере, иначе режим ведет себя как "Авто"
        val dvConvert = dvMode == SettingsRepository.DV_MODE_CONVERT && dvSupported && DoviBridge.isAvailable()
        dvConversionMode = if (dvConvert) DvConversionMode.DV81 else DvConversionMode.DISABLED
        Log.i("PlayerManager", "DV mode=$dvMode, P7->8.1 conversion=$dvConvert")
        stripHdr10Plus = !settingsRepo.isHdr10PlusEnabled()
        val mapDvToHevc = when (dvMode) {
            SettingsRepository.DV_MODE_ALWAYS -> true
            SettingsRepository.DV_MODE_AUTO, SettingsRepository.DV_MODE_CONVERT -> !dvSupported
            else -> false
        }
        val mediaCodecSelector = if (mapDvToHevc) {
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                var finalMimeType = mimeType

                // Если плеер запрашивает декодер для Dolby Vision...
                if (MimeTypes.VIDEO_DOLBY_VISION == mimeType) {
                    // ...мы "обманываем" его и говорим системе искать декодер для HEVC.
                    Log.i("PlayerManager", "DV Fallback: Intercepted DV request, substituting with HEVC.")
                    finalMimeType = MimeTypes.VIDEO_H265
                }

                // Запрашиваем у системы декодеры для (возможно) подмененного MIME-типа.
                try {
                    MediaCodecUtil.getDecoderInfos(
                        finalMimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )
                } catch (e: MediaCodecUtil.DecoderQueryException) {
                    Log.e("PlayerManager", "Failed to query decoders for $finalMimeType", e)
                    emptyList()
                }
            }
        } else {
            MediaCodecSelector.DEFAULT
        }

        val trackSelector = DefaultTrackSelector(appContext)
        val parametersBuilder = trackSelector.buildUponParameters()
            .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
            .setTunnelingEnabled(settingsRepo.isTunnelingEnabled() && !tunnelingDisabledForSession)
            // Разрешаем плееру игнорировать битые дорожки
            .setExceedRendererCapabilitiesIfNecessary(true)
            .setAllowMultipleAdaptiveSelections(true)

        // Audio Language
        val audioPref = settingsRepo.getPreferredAudioLang()
        when (audioPref) {
            SettingsRepository.TRACK_DEFAULT -> parametersBuilder.setPreferredAudioLanguages()
            SettingsRepository.TRACK_DEVICE -> parametersBuilder.setPreferredAudioLanguages(*getDeviceLanguages())
            else -> parametersBuilder.setPreferredAudioLanguage(audioPref)
        }

        // Subtitle Language & CaptioningManager
        val captioningManager = appContext.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        if (captioningManager == null || !captioningManager.isEnabled) {
            parametersBuilder.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        }

        val subPref = settingsRepo.getPreferredSubLang()
        if (subPref == SettingsRepository.TRACK_DEVICE && captioningManager?.locale != null) {
            parametersBuilder.setPreferredTextLanguage(captioningManager.locale?.toLanguageTag())
        } else {
            when (subPref) {
                SettingsRepository.TRACK_DEFAULT -> parametersBuilder.setPreferredTextLanguages()
                SettingsRepository.TRACK_DEVICE -> parametersBuilder.setPreferredTextLanguages(*getDeviceLanguages())
                else -> parametersBuilder.setPreferredTextLanguage(subPref)
            }
        }

        trackSelector.setParameters(parametersBuilder)

        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            init {
                setMediaCodecSelector(mediaCodecSelector)
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                val start = out.size
                // Приоритет встроенного AV1 (dav1d) - своя настройка видео, общий режим фабрики не используется
                super.buildVideoRenderers(
                    context, settingsRepo.getVideoDecoderPriority(), mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, out
                )
                // dav1d: стандартный рендерер держит в работе только 2 кадра, а у 1-плиточного AV1
                // (обычные релизы) потоки параллелятся именно между кадрами. 4 кадра заметно быстрее
                // на слабых ТВ-боксах (подход Just+ Player). Буферы растут вместе с конвейером
                for (i in start until out.size) {
                    if (out[i] is Libdav1dVideoRenderer) {
                        out[i] = Libdav1dVideoRenderer(
                            allowedVideoJoiningTimeMs, eventHandler, eventListener,
                            DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                            Libdav1dVideoRenderer.THREAD_COUNT_DECODER_DEFAULT,
                            DAV1D_MAX_FRAME_DELAY, DAV1D_INPUT_BUFFERS, DAV1D_OUTPUT_BUFFERS,
                            /* useCustomAllocator = */ false
                        )
                    }
                }
                // Стандартный рендерер заменяем своим с теми же параметрами (как в DefaultRenderersFactory):
                // подмена DV на HEVC только для профилей с совместимым слоем HEVC и пересоздание декодера
                // HiSilicon при смене поверхности (иначе зеленый экран после настроек), см. PlayerVideoRenderer
                val index = (start until out.size).firstOrNull { out[it] is MediaCodecVideoRenderer } ?: return
                out[index] = PlayerVideoRenderer(
                    MediaCodecVideoRenderer.Builder(context)
                        .setCodecAdapterFactory(getCodecAdapterFactory())
                        .setMediaCodecSelector(mediaCodecSelector)
                        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                        .setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler)
                        .setEventListener(eventListener)
                        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                    mapDvToHevc
                )
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink, // <-- Стандартный Sink от ExoPlayer
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                // Ручные настройки (экспертный режим) или профиль аудиосистемы (простой). Профилю нужно знать,
                // во что перекодировать: AC3, если выход его принимает, иначе E-AC3
                val canEncode = AudioOutputCapabilities.isEncoderAvailable
                val outputFormats = if (settingsRepo.isAudioExpertMode() || !canEncode) emptySet()
                    else AudioOutputCapabilities.passthroughFormats(appContext, iec = false)
                val config = settingsRepo.getAudioConfig(
                    canAc3 = canEncode && AudioPassthroughLogic.AC3 in outputFormats,
                    canEac3 = canEncode && AudioPassthroughLogic.EAC3 in outputFormats
                )
                val bitstreamAllowed = !bitstreamDisabledForSession
                val passthrough = bitstreamAllowed && config.passthrough
                val passthroughFormats = config.passthroughFormats
                val transcode = if (bitstreamAllowed) config.transcode else SettingsRepository.AUDIO_TRANSCODE_OFF
                val channelsSetting = config.outputChannels
                val mixParams = config.mixParams
                val upmix = config.upmix
                // LFE из других каналов - часть апмикса
                val generateLfe = config.generateLfe
                // Проброс выключен на сеанс (смена скорости): звук сводится в стерео. Многоканальный PCM
                // по оптике и ARC не проходит, а ради ускоренного просмотра стерео достаточно
                val maxPcmChannels = if (bitstreamDisabledForSession) 2 else 8
                // "Как в источнике" не расширяется, апмикс работает только с выбранной раскладкой
                val pcmMixing = channelsSetting != AudioMixerLogic.CHANNELS_SOURCE ||
                    mixParams != AudioMixerLogic.STANDARD_PARAMS || maxPcmChannels < 8

                // Микшер PCM: свой Sink с процессором, иначе стандартный. Битстрим идет мимо процессоров
                val baseSink = if (pcmMixing) {
                    // Ограничиваем аудиобуффер, чтобы не упасть по памяти
                    val bufferSizeProvider = DefaultAudioSink.AudioTrackBufferSizeProvider {
                            minSize, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, speed ->

                        // Получаем стандартный размер, рассчитанный ExoPlayer
                        val standardSize = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
                            .getBufferSizeInBytes(
                                minSize,
                                encoding,
                                outputMode,
                                pcmFrameSize,
                                sampleRate,
                                bitrate,
                                speed
                            )

                        standardSize.coerceAtMost(256 * 1024) // 256КБ должно хватить
                    }

                    val audioOutputProvider = AudioTrackAudioOutputProvider.Builder(appContext)
                        .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                        .build()
                    //~ Ограничиваем аудиобуффер, чтобы не упасть по памяти

                    val sinkBuilder = DefaultAudioSink.Builder(appContext)
                        .setEnableAudioOutputPlaybackParameters(true)
                        .setEnableFloatOutput(false) // Важно для стабильности Downmix на старых чипах
                        .setAudioOutputProvider(audioOutputProvider) // Ограничиваем аудиобуффер, чтобы не упасть по памяти

                    val mixingProcessor = ChannelMixingAudioProcessor()
                    AudioMixerLogic.createMatrices(mixParams, channelsSetting, maxPcmChannels, upmix, generateLfe)
                        .forEach { matrix -> mixingProcessor.putChannelMixingMatrix(matrix) }

                    // Созданный LFE - через ФНЧ 120 Гц после микшера, в конце мягкий ограничитель
                    // (усиление голоса и уровень даунмикса могут перегрузить канал)
                    // Микшер считает во float (без обрезки), ограничитель возвращает 16 бит
                    val processors = if (generateLfe) {
                        val probe = LfeLowPassAudioProcessor.Probe()
                        arrayOf(
                            SoftLimitAudioProcessor.ToFloat(), probe, mixingProcessor,
                            LfeLowPassAudioProcessor(probe, channelsSetting, maxPcmChannels), SoftLimitAudioProcessor()
                        )
                    } else {
                        arrayOf(SoftLimitAudioProcessor.ToFloat(), mixingProcessor, SoftLimitAudioProcessor())
                    }
                    sinkBuilder.setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(*processors)
                    )

                    sinkBuilder.build()
                } else {
                    audioSink
                }
                // DTS-HD MA и TrueHD через несущую IEC 61937 - часть проброса
                val iec = if (passthrough && config.hdAudioIec) IecCarrierOutput.wrap(appContext, baseSink) else null
                iecSink = iec
                // Декодированный звук -> AC3/E-AC3 для проброса, тем же микшером. Снаружи микшера PCM:
                // берет звук до его процессоров
                val transcoder = if (transcode != SettingsRepository.AUDIO_TRANSCODE_OFF) {
                    TranscodingAudioSink(
                        iec ?: baseSink,
                        TranscodingAudioSink.Config(
                            eac3 = transcode == SettingsRepository.AUDIO_TRANSCODE_EAC3,
                            channelsSetting = channelsSetting,
                            mixParams = mixParams,
                            upmix = upmix,
                            generateLfe = generateLfe
                        )
                    )
                } else null
                transcodingSink = transcoder
                // Снаружи всего - выбор, какой сжатый звук пробрасывать; остальное декодируется
                val finalSink = PassthroughPolicySink(transcoder ?: iec ?: baseSink) { format ->
                    AudioPassthroughLogic.isAllowed(format.sampleMimeType, passthrough, passthroughFormats)
                }

                // Проброс не зависит от приоритета декодеров: первым стоит рендерер без декодеров,
                // он берет только то, что выход примет как есть
                if (passthrough) {
                    out.add(
                        MediaCodecAudioRenderer(
                            context, MediaCodecSelector { _, _, _ -> emptyList() }, false,
                            eventHandler, eventListener, finalSink
                        )
                    )
                }

                // Приоритет встроенного FFmpeg - своя настройка аудио: кто декодирует то, что не пробрасывается
                super.buildAudioRenderers(
                    context,
                    config.decoderPriority,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    finalSink,
                    eventHandler,
                    eventListener,
                    out
                )
            }
        }.apply {
            // Режим для остальных рендереров; видео и аудио берут свои настройки в build*Renderers
            setExtensionRendererMode(settingsRepo.getVideoDecoderPriority())
            setEnableDecoderFallback(true) // Разрешаем софтовый декодер
        }

        // Объем буфера из настроек применяется только к сетевому видео,
        // локальные файлы и режим "Авто" буферизуются с прежними параметрами
        val networkBufferMB = settingsRepo.getTargetBufferCorrectMB()
        networkBufferBytes = if (networkBufferMB > 0) {
            (networkBufferMB * 1024 * 1024).coerceAtMost(NetworkBufferLoadControl.maxBufferBytesForHeap())
        } else {
            C.LENGTH_UNSET
        }
        val loadControl = NetworkBufferLoadControl.create(networkBufferMB)
        bufferAllocator = loadControl.bufferAllocator

        // Build Player
        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(appContext, extractorsFactory)
                    .setDataSourceFactory(createParsingDataSourceFactory())
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            )
            .build()

        // Audio Attributes
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(audioAttributes, true)

        // Skip Silence
        if (settingsRepo.isSkipSilenceEnabled()) {
            player.skipSilenceEnabled = true
        }

        // Handle Noisy
        player.setHandleAudioBecomingNoisy(true)
        audioRouteWatcher.start()

        // Seek Parameters
        player.setSeekBackIncrementMs(15000)
        player.setSeekForwardIncrementMs(15000)

        // --- LoudnessEnhancer ---
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                initLoudnessEnhancer(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
                // Новое видео снова может идти через несущую, даже если прошлое не подошло
                iecSink?.beginMediaItem()
            }
        })
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            initLoudnessEnhancer(player.audioSessionId)
        }

        playerListener?.let { player.addListener(it) }

        // --- MediaSession ---
        if (player.canAdvertiseSession()) {
            try {
                // ID уникален в процессе: при открытии файла из другого приложения новый экран плеера создает
                // сессию раньше, чем старый освободит свою, и одинаковый ID ("") дал бы IllegalStateException
                val sessionPlayer = object : ForwardingPlayer(player) {
                    override fun stop() {
                        onStopRequested?.invoke() ?: super.stop()
                    }
                }
                mediaSession = MediaSession.Builder(appContext, sessionPlayer)
                    .setId("dddplayer-${sessionCounter.incrementAndGet()}")
                    .build()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
            ) {
                onVideoFormatChanged?.invoke(format)
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                config: AudioSink.AudioTrackConfig
            ) {
                // config.encoding говорит нам, в каком формате данные идут на железо.
                // Если это PCM (16-bit, Float), значит плеер декодировал звук.
                // Если это AC3, DTS и т.д., значит работает Passthrough.
                // Несущая IEC 61937 учитывается как PCM 16 бит, поэтому спрашиваем и ее
                isDirectAudioOutput = !Util.isEncodingLinearPcm(config.encoding) || iecSink?.isCarrierActive == true
                // На несущей AudioTrack видит PCM 16 бит 7.1, а на ресивер уходит TrueHD или DTS-HD как есть
                // Бейдж: дорожка > маршрут ("проброс", "AC3 5.1" при перекодировании, "PCM 2.0")
                val passthroughLabel = appContext.getString(R.string.audio_route_passthrough)
                val carrierCodec = iecSink?.carrierCodecLabel
                if (carrierCodec != null) {
                    onAudioOutputFormatChanged?.invoke("$passthroughLabel (IEC 61937)")
                } else if (!Util.isEncodingLinearPcm(config.encoding) && transcodingSink?.isTranscoding != true) {
                    onAudioOutputFormatChanged?.invoke(passthroughLabel)
                } else {
                    val encodingName = MediaFormatHelper.getAudioCodecName(config.encoding)
                    val channelStr = MediaFormatHelper.getChannelConfigString(config.channelConfig)
                    onAudioOutputFormatChanged?.invoke("$encodingName $channelStr")
                }
                // Туннелирование касается видео (кадры идут прямо в аппаратный вывод), показывается у видео
                onTunnelingChanged?.invoke(config.tunneling)
            }

            override fun onAudioTrackReleased(
                eventTime: AnalyticsListener.EventTime,
                config: AudioSink.AudioTrackConfig
            ) {
                isDirectAudioOutput = false
            }

            // Для диагностики пропадающего звука: плеер приглушен потерей аудиофокуса (звонок, другое приложение)
            override fun onPlaybackSuppressionReasonChanged(
                eventTime: AnalyticsListener.EventTime,
                playbackSuppressionReason: Int
            ) {
                Log.i("PlayerManager", "Playback suppression reason: $playbackSuppressionReason")
            }
        })

        this.exoPlayer = player

        // 6. Restore State
        if (currentMediaItems.isNotEmpty()) {
            val sources = buildMediaSources(currentMediaItems)
            player.setMediaSources(sources, currentWindowIndex, currentPosition)
            player.playWhenReady = playWhenReady
            player.prepare()
        }

        onPlayerCreated?.invoke(player)
    }

    /**
     * Dolby Vision можно выводить как есть: есть системный DV декодер и экран сообщает о поддержке DV.
     * Иначе (режим "Авто") видео играет базовым слоем HEVC.
     */
    @Suppress("DEPRECATION")
    private fun isDolbyVisionSupported(): Boolean {
        val hasDecoder = try {
            MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isNotEmpty()
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            false
        }
        if (!hasDecoder || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val display = (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val hdrTypes = display.hdrCapabilities?.supportedHdrTypes?.toMutableSet() ?: mutableSetOf()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hdrTypes.addAll(display.mode.supportedHdrTypes.toList())
        }
        val supported = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes
        Log.i("PlayerManager", "Dolby Vision: decoder=true, display=$supported")
        return supported
    }

    private fun buildMediaSources(exoItems: List<Media3MediaItem>): List<MediaSource> {
        // Передаем false вторым параметром. Это заставит плеер скачать первый .ts файл
        // и проанализировать его структуру, вместо того чтобы гадать по пустому m3u8.
        val hlsExtractorFactory = DefaultHlsExtractorFactory(tsExtractorFlags, false)

        // Фабрики по набору заголовков: обычно он один на весь плейлист
        val hlsFactories = HashMap<Map<String, String>, HlsMediaSource.Factory>()
        val defaultFactories = HashMap<Map<String, String>, DefaultMediaSourceFactory>()

        return exoItems.map { exoItem ->
            val headers = headersByMediaId[exoItem.mediaId].orEmpty()
            val uriStr = exoItem.localConfiguration?.uri?.toString() ?: ""
            val mimeType = resolvedTypeFor(uriStr)
                ?: exoItem.localConfiguration?.mimeType
                ?: MediaFormatHelper.getVideoMimeType(uriStr.toUri())
            val isHls = mimeType == MimeTypes.APPLICATION_M3U8

            if (isHls) {
                hlsFactories.getOrPut(headers) {
                    HlsMediaSource.Factory(createParsingDataSourceFactory(headers))
                        .setExtractorFactory(hlsExtractorFactory)
                        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                }.createMediaSource(exoItem)
            } else {
                defaultFactories.getOrPut(headers) {
                    DefaultMediaSourceFactory(appContext, extractorsFactory)
                        .setDataSourceFactory(createParsingDataSourceFactory(headers))
                        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                }.createMediaSource(exoItem)
            }
        }
    }

    private fun initLoudnessEnhancer(audioSessionId: Int) {
        try {
            loudnessEnhancer?.release()
            val boost = settingsRepo.getLoudnessBoost()
            if (boost > 0) {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                loudnessEnhancer?.setTargetGain(boost)
                loudnessEnhancer?.enabled = true
            } else {
                loudnessEnhancer = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Применяет настройки, которые не требуют пересоздания плеера.
     */
    fun applyLiveSettings() {
        val player = exoPlayer ?: return
        updateTrackSelectionParameters()
        player.skipSilenceEnabled = settingsRepo.isSkipSilenceEnabled()
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            initLoudnessEnhancer(player.audioSessionId)
        }
    }

    fun updateTrackSelectionParameters() {
        val player = exoPlayer ?: return
        val audioPref = settingsRepo.getPreferredAudioLang()
        val subPref = settingsRepo.getPreferredSubLang()

        val builder = player.trackSelectionParameters.buildUpon()

        when (audioPref) {
            SettingsRepository.TRACK_DEFAULT -> builder.setPreferredAudioLanguages()
            SettingsRepository.TRACK_DEVICE -> builder.setPreferredAudioLanguages(*getDeviceLanguages())
            else -> builder.setPreferredAudioLanguage(audioPref)
        }

        when (subPref) {
            SettingsRepository.TRACK_DEFAULT -> builder.setPreferredTextLanguages()
            SettingsRepository.TRACK_DEVICE -> builder.setPreferredTextLanguages(*getDeviceLanguages())
            else -> builder.setPreferredTextLanguage(subPref)
        }

        player.trackSelectionParameters = builder.build()
    }

    private fun getDeviceLanguages(): Array<String> {
        val locales = LocaleListCompat.getAdjustedDefault()
        val languages = mutableListOf<String>()
        for (i in 0 until locales.size()) {
            locales.get(i)?.language?.let { languages.add(it) }
        }
        return languages.toTypedArray()
    }

    fun getResolvedMimeType(uri: android.net.Uri): String? = resolvedTypeFor(uri.toString())

    // Тип запоминается по URL из OkHttp: он может быть перекодирован (кириллица, пробелы, "|")
    private fun resolvedTypeFor(url: String): String? =
        resolvedMediaTypes[url] ?: url.toHttpUrlOrNull()?.toString()?.let { resolvedMediaTypes[it] }

    /**
     * Если передана одна ссылка на .m3u/.m3u8, проверяет, не плейлист ли это (IPTV/список файлов).
     *
     * Ссылка скачивается один раз. Плейлист M3U разбирается в список элементов. HLS (любой тег #EXT-X-)
     * и прочее остаются как есть, а уже скачанный ответ отдается плееру без повторного запроса.
     *
     * @return список элементов M3U или null, если это не M3U плейлист.
     */
    suspend fun expandM3u(items: List<MediaItem>, force: Boolean = false): List<MediaItem>? = withContext(Dispatchers.IO) {
        prefetchStore.clear()
        val item = items.singleOrNull() ?: return@withContext null
        if (!force && !isM3uCandidate(item.uri)) return@withContext null

        try {
            val scheme = item.uri.scheme?.lowercase()
            val content = if (scheme == "http" || scheme == "https") {
                fetchForProbe(item)
            } else {
                appContext.contentResolver.openInputStream(item.uri)?.use { input ->
                    readLimited(input)?.toString(Charsets.UTF_8)
                }
            } ?: return@withContext null

            if (M3uParser.isPlaylist(content)) {
                M3uParser.parse(content, item.uri, item.headers).ifEmpty { null }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("PlayerManager", "M3U probe failed: ${e.message}")
            null
        }
    }

    /** Одна ссылка на .m3u/.m3u8 - ее нужно проверить на плейлист M3U. */
    fun isM3uCandidate(items: List<MediaItem>): Boolean {
        val item = items.singleOrNull() ?: return false
        return isM3uCandidate(item.uri)
    }

    private fun isM3uCandidate(uri: android.net.Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        return path.endsWith(".m3u") || path.endsWith(".m3u8")
    }

    /**
     * Скачивает ссылку для проверки. Если это не плейлист M3U, сохраняет ответ для плеера.
     * @return текст ответа или null (не текст / ошибка / слишком большой).
     */
    private fun fetchForProbe(item: MediaItem): String? {
        val uriString = item.uri.toString()
        val requestBuilder = Request.Builder().url(uriString)
        item.headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        if (item.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", USER_AGENT)
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            // Видео/аудио по ссылке .m3u - не плейлист, плеер откроет его сам (audio/x-mpegurl - плейлист)
            if (M3uParser.isMediaContentType(response.header("Content-Type"))) return null
            val contentLength = response.body.contentLength()
            if (contentLength > MAX_PROBE_BYTES) return null

            val data = readLimited(response.body.byteStream()) ?: return null
            val text = data.toString(Charsets.UTF_8)

            if (!M3uParser.isPlaylist(text)) {
                // HLS и прочее: отдаем плееру уже скачанный ответ
                prefetchStore.put(
                    uriString,
                    PrefetchedResponse(data, response.request.url.toString().toUri(), response.headers.toMultimap())
                )
                if (text.contains("#EXT-X-")) {
                    probedMediaTypes[uriString] = MimeTypes.APPLICATION_M3U8
                }
            }
            return text
        }
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            if (total > MAX_PROBE_BYTES) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun buildExoItem(item: MediaItem): Media3MediaItem {
        val subConfigs = item.subtitles.map { sub ->
            Media3MediaItem.SubtitleConfiguration.Builder(sub.uri)
                .setMimeType(sub.mimeType)
                .setLanguage("ext")
                .setLabel(sub.name ?: sub.filename)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtworkUri(item.posterUri)
            .build()

        // Даем плееру больше времени на "переваривание" битых кадров,
        // отодвигая точку воспроизведения дальше от края трансляции.
        val liveConfig = Media3MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(15000) // увеличили до 15 сек
            .setMaxOffsetMs(30000)    // Максимальное отставание
            .setMaxPlaybackSpeed(1.05f) // Разрешаем ускоряться до 1.05x чтобы догнать поток
            .build()

        return Media3MediaItem.Builder()
            .setMediaId(item.uuid)
            .setUri(item.uri)
//            .setMimeType(mimeType)
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(subConfigs)
            .setLiveConfiguration(liveConfig)
            .build()
    }

    /**
     * Перезагружает плейлист с измененными элементами (например, добавлены субтитры) с той же позиции.
     */
    fun reloadPlaylistItems(items: List<MediaItem>, index: Int, positionMs: Long) {
        headersByMediaId = items.associate { it.uuid to it.headers }
        reloadMediaItems(items.map { buildExoItem(it) }, index, positionMs)
    }

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int, startPosMs: Long = 0) {
        currentTrackInfo = emptyMap()
        resolvedMediaTypes.clear() // Очищаем кэш типов при новой загрузке
        resolvedMediaTypes.putAll(probedMediaTypes)
        probedMediaTypes.clear()

        val safeStartIndex = if (items.isEmpty()) 0 else startIndex.coerceIn(0, items.lastIndex)
        headersByMediaId = items.associate { it.uuid to it.headers }

        val exoItems = items.map { buildExoItem(it) }

        currentMediaItems = exoItems
        currentWindowIndex = safeStartIndex
        currentPosition = if (startPosMs <= 0L) C.TIME_UNSET else startPosMs
        playWhenReady = true
        // Новый плейлист снова может идти через несущую, даже если прошлое видео не подошло
        iecSink?.beginMediaItem()

        if (exoPlayer == null) {
            initializePlayer()
        } else {
            val sources = buildMediaSources(exoItems)
            exoPlayer?.setMediaSources(sources, safeStartIndex, currentPosition)
            exoPlayer?.playWhenReady = true
            exoPlayer?.prepare()
        }
    }

    /**
     * Перезагружает плейлист в текущий плеер (восстановление после ошибки).
     * Источники собираются через [buildMediaSources], чтобы сохранить
     * кастомные экстракторы и ParsingDataSource.
     * Пауза сохраняется (playWhenReady не меняется): соединение может оборваться, пока видео на паузе,
     * и восстановление не должно само запускать воспроизведение.
     */
    fun reloadMediaItems(items: List<Media3MediaItem>, index: Int, positionMs: Long) {
        val player = exoPlayer ?: return
        currentMediaItems = items
        currentWindowIndex = index
        currentPosition = positionMs
        player.setMediaSources(buildMediaSources(items), index, positionMs)
        player.prepare()
    }

    /**
     * Пересоздает аудиовыход, как "Выкл" и обратно в меню дорожек: новый AudioTrack заново выбирает маршрут
     * (A2DP вместо гарнитурного режима). Включение откладываем: два изменения параметров подряд плеер
     * объединил бы и ничего не пересоздал. Звук, выключенный пользователем, не трогаем.
     */
    private fun refreshAudioOutput(reason: String) {
        val player = exoPlayer ?: return
        val params = player.trackSelectionParameters
        if (C.TRACK_TYPE_AUDIO in params.disabledTrackTypes) return
        Log.i("PlayerManager", "Refreshing audio output: $reason")
        player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        audioRefreshHandler.postDelayed({
            val current = exoPlayer ?: return@postDelayed
            current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        }, AUDIO_REFRESH_DELAY_MS)
    }

    fun releasePlayer(isFinalRelease: Boolean = false, saveState: Boolean = true) {
        audioRouteWatcher.stop()
        audioRefreshHandler.removeCallbacksAndMessages(null)
        exoPlayer?.let { player ->
            if (saveState) {
                currentWindowIndex = player.currentMediaItemIndex
                currentPosition = player.currentPosition
                playWhenReady = player.playWhenReady
            }
            playerListener?.let { player.removeListener(it) }
            player.release()
        }
        mediaSession?.release()
        mediaSession = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        exoPlayer = null

        // Зануляем коллбеки ТОЛЬКО если это полное уничтожение плеера (выход из приложения),
        // чтобы не сломать "горячую перезагрузку" при смене настроек.
        if (isFinalRelease) {
            playerListener = null
            onMetadataAvailable = null
            onPlayerCreated = null
            onVideoFormatChanged = null
            onAudioOutputFormatChanged = null
            onTunnelingChanged = null
        }
    }

    fun getTrackMetadata(): Map<Int, UnifiedMetadataReader.TrackInfo> = currentTrackInfo

    /**
     * Заполненность буфера (0-100) по объему, если для текущего сетевого видео задан размер в МБ.
     * null - режим "Авто" или локальный файл: процент считается по времени, как раньше.
     */
    fun getNetworkBufferFillPercent(): Int? {
        val player = exoPlayer ?: return null
        val allocator = bufferAllocator ?: return null
        if (networkBufferBytes == C.LENGTH_UNSET) return null
        val scheme = player.currentMediaItem?.localConfiguration?.uri?.scheme
        if (scheme.isNullOrEmpty() || scheme in DefaultLoadControl.LOCAL_PLAYBACK_SCHEMES) return null
        return ((allocator.totalBytesAllocated * 100L) / networkBufferBytes).toInt().coerceIn(0, 100)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        // Ориентируемся на playWhenReady, а не на isPlaying: во время буферизации
        // isPlaying == false, но пользователь ожидает именно паузу.
        val state = player.playbackState
        if (player.playWhenReady && state != Player.STATE_ENDED && state != Player.STATE_IDLE) {
            player.pause()
        } else {
            when (state) {
                Player.STATE_ENDED -> player.seekToDefaultPosition()
                Player.STATE_IDLE -> player.prepare()
            }
            player.play()
        }
    }

    fun seekForward() {
        exoPlayer?.let {
            val target = it.currentPosition + it.seekForwardIncrement
            // Для потоков с неизвестной длительностью duration == C.TIME_UNSET,
            // а seekTo(C.TIME_UNSET) означает переход в начало.
            val duration = it.duration
            it.seekTo(if (duration != C.TIME_UNSET) target.coerceAtMost(duration) else target)
        }
    }

    fun seekBack() {
        exoPlayer?.let { it.seekTo((it.currentPosition - it.seekBackIncrement).coerceAtLeast(0)) }
    }
}