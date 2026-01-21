package top.rootu.dddplayer.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import okhttp3.OkHttpClient
import top.rootu.dddplayer.logic.UnifiedMetadataReader
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.viewmodel.TrackOption
import top.rootu.dddplayer.viewmodel.VideoQualityOption
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.media3.common.MediaItem as Media3MediaItem

@UnstableApi
class PlayerManager(private val context: Context, private val listener: Player.Listener) {

    // Используем Application Context для предотвращения утечек Activity
    private val appContext = context.applicationContext

    // Хранилище метаданных (названия треков из MKV/MP4)
    private var currentTrackInfo: Map<Int, UnifiedMetadataReader.TrackInfo> = emptyMap()

    // Callback для уведомления ViewModel о том, что метаданные распарсились
    var onMetadataAvailable: (() -> Unit)? = null

    // 1. OkHttp для сети
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS) // Время на установку соединения
        .readTimeout(60, TimeUnit.SECONDS)    // Время ожидания пакета данных
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true) // Автоматически пробовать другой маршрут при сбое
        .build()

    private val baseHttpFactory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent("DDDPlayer/1.0")

    // 2. Обертка-перехватчик для чтения заголовков файла
    private val parsingDataSourceFactory = ParsingDataSourceFactory(
        upstreamFactory = baseHttpFactory,
        onMetadataParsed = { metadataMap ->
            currentTrackInfo = metadataMap
            onMetadataAvailable?.invoke()
        },
        isMetadataParsed = {
            // Проверяем, есть ли у нас уже данные.
            // Если map не пустой, значит парсить не надо.
            currentTrackInfo.isNotEmpty()
        }
    )

    // 3. ExtractorsFactory (Фикс для DTS в TS контейнерах)
    private val extractorsFactory = DefaultExtractorsFactory()
        // Фикс для DTS в .ts файлах
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
        // Включаем чтение расширенных метаданных для MP4
        // Это часто заставляет парсер читать 'udta' и 'hdlr' атомы более подробно.
        .setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES)
        // Разрешаем поиск в файлах с постоянным битрейтом (ускоряет старт)
        .setConstantBitrateSeekingEnabled(true)

    // 4. TrackSelector (Автовыбор языка системы)
    private val trackSelector = DefaultTrackSelector(appContext).apply {
        parameters = buildUponParameters()
            // Предпочитать язык системы для аудио и субтитров
            .setPreferredAudioLanguage(Locale.getDefault().language)
            .setPreferredTextLanguage(Locale.getDefault().language)
            // Разрешить туннелирование (важно для Android TV 4K HDR)
            .setTunnelingEnabled(true)
            .build()
    }

    // 5. AudioAttributes (Для Passthrough)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    // 6. RenderersFactory (С поддержкой FFmpeg и Passthrough)
    private val renderersFactory = object : DefaultRenderersFactory(appContext) {
        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink, // Игнорируем дефолтный, создаем свой
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>
        ) {
            // Создаем AudioSink с поддержкой Passthrough
            val customSink = DefaultAudioSink.Builder(appContext)
                .setEnableFloatOutput(false) // Важно: Passthrough часто не работает с Float выходом
                .setEnableAudioTrackPlaybackParams(true)
                .build()

            // Передаем наш настроенный Sink
            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                customSink,
                eventHandler,
                eventListener,
                out
            )
        }
    }.apply {
        // Включаем FFmpeg расширение (если есть в libs)
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    }

    // Кастомная политика обработки ошибок
    private val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            // Если ошибка - это Таймаут или потеря связи, пробуем больше раз
            // Стандартная логика: min((errorCount - 1) * 1000, 5000)

            // Вернём стандартную задержку, но разрешим больше попыток в getMinimumLoadableRetryCount
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int {
            // Увеличиваем количество попыток до 5 (по умолчанию 3)
            // плеер будет пытаться восстановить связь перед тем, как выкинуть фатальную ошибку.
            return 5
        }
    }
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(appContext, extractorsFactory)
            .setDataSourceFactory(parsingDataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        ) // Используем наши обертки
        .setTrackSelector(trackSelector) // Подключаем селектор треков
        .setAudioAttributes(audioAttributes, true)
        .setSeekBackIncrementMs(15000)
        .setSeekForwardIncrementMs(15000)
        .setHandleAudioBecomingNoisy(true) // Пауза при отключении наушников
        .build()

    init {
        exoPlayer.addListener(listener)
    }

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int) {
        if (items.isNotEmpty()) {
            baseHttpFactory.setDefaultRequestProperties(items[startIndex].headers)
        }
        // Сброс метаданных перед загрузкой нового
        currentTrackInfo = emptyMap()

        val exoItems = items.map { item ->
            val subConfigs = item.subtitles.map { sub ->
                Media3MediaItem.SubtitleConfiguration.Builder(sub.uri)
                    .setMimeType(sub.mimeType ?: MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("und")
                    .setLabel(sub.name ?: sub.filename)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtworkUri(item.posterUri)
                .build()

            Media3MediaItem.Builder()
                .setUri(item.uri)
                .setMediaMetadata(metadata)
                .setSubtitleConfigurations(subConfigs)
                .build()
        }

        exoPlayer.setMediaItems(exoItems, startIndex, items[startIndex].startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun getTrackMetadata(): Map<Int, UnifiedMetadataReader.TrackInfo> {
        return currentTrackInfo
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    fun seekForward() {
        val target = (exoPlayer.currentPosition + exoPlayer.seekForwardIncrement).coerceAtMost(exoPlayer.duration)
        exoPlayer.seekTo(target)
    }

    fun seekBack() {
        val target = (exoPlayer.currentPosition - exoPlayer.seekBackIncrement).coerceAtLeast(0)
        exoPlayer.seekTo(target)
    }

    fun selectTrack(option: TrackOption, trackType: Int) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()

        if (option.isOff) {
            builder.setTrackTypeDisabled(trackType, true)
        } else {
            builder.setTrackTypeDisabled(trackType, false)
            option.group?.let {
                builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, option.trackIndex))
            }
        }

        exoPlayer.trackSelectionParameters = builder.build()
    }

    fun selectVideoQuality(option: VideoQualityOption) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()

        if (option.isAuto) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            option.group?.let {
                builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, option.trackIndex))
            }
        }

        exoPlayer.trackSelectionParameters = builder.build()
    }

    fun release() {
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }
}