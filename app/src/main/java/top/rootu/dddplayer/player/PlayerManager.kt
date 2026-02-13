package top.rootu.dddplayer.player

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.view.accessibility.CaptioningManager
import androidx.core.os.LocaleListCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import okhttp3.OkHttpClient
import top.rootu.dddplayer.App.Companion.USER_AGENT
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AudioMixerLogic
import top.rootu.dddplayer.logic.UnifiedMetadataReader
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.utils.MediaFormatHelper
import java.util.concurrent.TimeUnit
import androidx.media3.common.MediaItem as Media3MediaItem

class PlayerManager(
    private val context: Context,
    private val listener: Player.Listener
) {
    private val appContext = context.applicationContext
    private val settingsRepo = SettingsRepository(appContext)

    var exoPlayer: ExoPlayer? = null
        private set

    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    // Состояние для восстановления
    private var currentWindowIndex = 0
    private var currentPosition = 0L
    private var currentMediaItems: List<Media3MediaItem> = emptyList()
    private var playWhenReady = true

    private var currentTrackInfo: Map<Int, UnifiedMetadataReader.TrackInfo> = emptyMap()
    var onMetadataAvailable: (() -> Unit)? = null
    var onPlayerCreated: ((ExoPlayer) -> Unit)? = null
    var onAudioOutputFormatChanged: ((String) -> Unit)? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())

            // Проверяем, если это HLS, но неподдерживаемый тип, то сменим его
            val contentType = response.header("Content-Type")
            if (contentType != null
                && contentType.contains("application/vnd.apple.mpegurl", true)
            ) {
                // Это HLS! Подменяем тип. todo бессмысленно (заголовки не учавствуют и тип определен заранее), но возможно пригодится для failback стратегии
                return@addNetworkInterceptor response.newBuilder()
                    .header("Content-Type", MimeTypes.APPLICATION_M3U8)
                    .build()
            }
            response
        }
        .build()

    // Фабрика для HTTP (сеть)
    private val baseHttpFactory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent(USER_AGENT)

    // Универсальная фабрика, которая умеет работать с content://, file:// и http://
    // Мы передаем baseHttpFactory как источник для сетевых запросов.
    private val defaultDataSourceFactory = DefaultDataSource.Factory(appContext, baseHttpFactory)

    // Пересоздаем фабрику при инициализации, чтобы гарантировать чистое состояние
    private fun createParsingDataSourceFactory(): ParsingDataSourceFactory {
        return ParsingDataSourceFactory(
            upstreamFactory = defaultDataSourceFactory,
            onMetadataParsed = { metadataMap ->
                currentTrackInfo = metadataMap
                onMetadataAvailable?.invoke()
            },
            isMetadataParsed = { currentTrackInfo.isNotEmpty() }
        )
    }

    fun initializePlayer() {
        if (exoPlayer != null) {
            releasePlayer(saveState = true)
        }

        // 1. TrackSelector
        val trackSelector = DefaultTrackSelector(appContext)
        val parametersBuilder = trackSelector.buildUponParameters()
            .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
            .setTunnelingEnabled(settingsRepo.isTunnelingEnabled())

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

        // 2. ExtractorsFactory
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES)
            .setConstantBitrateSeekingEnabled(true)

        val renderersFactory = object : DefaultRenderersFactory(appContext) {
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
                // Решаем, какой Sink использовать
                val finalSink = if (settingsRepo.isStereoDownmixEnabled()) {
                    // Если нужен Downmix -> создаем свой Sink с процессором
                    val sinkBuilder = DefaultAudioSink.Builder(appContext)
                        .setEnableAudioOutputPlaybackParameters(true)
                        .setEnableFloatOutput(false) // Для совместимости с процессором

                    val mixingProcessor = ChannelMixingAudioProcessor()
                    val matrices = AudioMixerLogic.createMatrices(settingsRepo)
                    matrices.forEach { matrix ->
                        mixingProcessor.putChannelMixingMatrix(matrix)
                    }

                    sinkBuilder.setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(mixingProcessor)
                    )

                    sinkBuilder.build()
                } else {
                    // Если Downmix не нужен -> используем стандартный (для Passthrough и т.д.)
                    audioSink
                }

                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    finalSink,
                    eventHandler,
                    eventListener,
                    out
                )
            }
        }.apply {
            setExtensionRendererMode(settingsRepo.getDecoderPriority())
        }

        // 3. LoadErrorHandlingPolicy
        val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 5
        }

        // 4. Build Player
        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector)
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

        // Seek Parameters
        player.setSeekBackIncrementMs(15000)
        player.setSeekForwardIncrementMs(15000)

        // --- LoudnessEnhancer ---
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                initLoudnessEnhancer(audioSessionId)
            }
        })
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            initLoudnessEnhancer(player.audioSessionId)
        }

        player.addListener(listener)

        // --- MediaSession ---
        if (player.canAdvertiseSession()) {
            try {
                mediaSession = MediaSession.Builder(context, player).build()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                config: AudioSink.AudioTrackConfig
            ) {
                // config.encoding говорит нам, в каком формате данные идут на железо.
                // Если это PCM (16-bit, Float), значит плеер декодировал звук.
                // Если это AC3, DTS и т.д., значит работает Passthrough.
                val encodingName = MediaFormatHelper.getAudioCodecName(config.encoding)
                val channelStr = MediaFormatHelper.getChannelConfigString(config.channelConfig)
                val passthrough = if (config.tunneling) " ↳" else ""
                val info = "$encodingName $channelStr$passthrough"

                onAudioOutputFormatChanged?.invoke(info)
            }
        })

        this.exoPlayer = player

        // 6. Restore State
        if (currentMediaItems.isNotEmpty()) {
            player.setMediaItems(currentMediaItems, currentWindowIndex, currentPosition)
            player.playWhenReady = playWhenReady
            player.prepare()
        }

        onPlayerCreated?.invoke(player)
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

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int, startPosMs: Long = 0) {
        currentTrackInfo = emptyMap()
        if (items.isNotEmpty()) {
            baseHttpFactory.setDefaultRequestProperties(items[startIndex].headers)
        }

        val exoItems = items.map { item ->
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

            Media3MediaItem.Builder()
                .setUri(item.uri)
//                .setMimeType(mimeType)
                .setMediaMetadata(metadata)
                .setSubtitleConfigurations(subConfigs)
                .build()
        }

        currentMediaItems = exoItems
        currentWindowIndex = startIndex
        currentPosition = startPosMs
        playWhenReady = true

        if (exoPlayer == null) {
            initializePlayer()
        } else {
            exoPlayer?.setMediaItems(exoItems, startIndex, startPosMs)
            exoPlayer?.playWhenReady = true
            exoPlayer?.prepare()
        }
    }

    fun releasePlayer(saveState: Boolean = true) {
        exoPlayer?.let { player ->
            if (saveState) {
                currentWindowIndex = player.currentMediaItemIndex
                currentPosition = player.currentPosition
                playWhenReady = player.playWhenReady
            }
            player.removeListener(listener)
            player.release()
        }
        mediaSession?.release()
        mediaSession = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        exoPlayer = null
    }

    fun getTrackMetadata(): Map<Int, UnifiedMetadataReader.TrackInfo> = currentTrackInfo

    fun togglePlayPause() {
        exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekForward() {
        exoPlayer?.let { it.seekTo((it.currentPosition + it.seekForwardIncrement).coerceAtMost(it.duration)) }
    }

    fun seekBack() {
        exoPlayer?.let { it.seekTo((it.currentPosition - it.seekBackIncrement).coerceAtLeast(0)) }
    }
}