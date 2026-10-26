# Воспроизведение (`player/`)

## PlayerManager

`PlayerManager` владеет экземпляром ExoPlayer. Обращается к нему только `PlayerViewModel`.

- `initializePlayer()` собирает плеер из текущих настроек:
  - `MediaCodecSelector`: запрос декодера Dolby Vision подменяется на HEVC по `dv_mode` — всегда, никогда или
    «авто» (если нет DV декодера или экран не сообщает о поддержке DV, `isDolbyVisionSupported`);
    селектор видит только MIME-тип, поэтому видеорендерер заменяется на `PlayerVideoRenderer`:
    при подмене для профилей без совместимого слоя HEVC (5, 9, 10, см. `DolbyVisionLogic`) он берет обычный выбор
    декодера. Кроме того, для декодеров HiSilicon (`OMX.hisi.*`) он включает `codecNeedsSetOutputSurfaceWorkaround`:
    они не умеют менять поверхность на ходу (`setOutputSurface` падает "cannot change usage" после возврата из
    настроек, оверлей зеленый), поэтому при смене поверхности декодер пересоздается;
  - `dv_mode` = 3: `ExtractorsFactory` оборачивает MKV/MP4 в `DoviExtractorWrapper`. Для `dvhe.07` RPU
    конвертируется в 8.1 (libdovi, режим 2, как `convertdovi` у Kodi), слой EL отбрасывается, формат
    становится `dvhe.08`. Профили 5/8 проходят без изменений (в логе `DoviConvertTrack: DV ...: native`).
    Нативная часть (`libdovi_bridge.so`: JNI-мост `DoviBridge` и статическая libdovi из quietvoid/dovi_tool)
    собирается отдельным модулем `dovi` в `app/libs/lib-dovi-release.aar` (`./gradlew -Pdovi=true
    :dovi:assembleRelease`, см. `app/libs/README.md`). Без AAR `DoviBridge.isAvailable()` = false: пункт
    скрыт в настройках, а сохраненный `dv_mode` = 3 работает как «авто»;
  - `hdr10plus_enabled` = false (#10, как в Kodi): MKV/MP4/fMP4/TS оборачиваются в `Hdr10PlusExtractorWrapper`,
    видеодорожки идут через `Hdr10PlusStrippingTrackOutput`. `Hdr10PlusStripper` убирает ITU-T T.35 HDR10+
    (страна 0xB5, провайдер 0x003C, приложение 4): в HEVC — сообщения prefix SEI (смешанный SEI собирается
    заново), в AV1 — metadata OBU; у VP9 из MKV отбрасываются дополнительные данные сэмпла. Сканируется только
    начало кадра до первого слайса. T.35 Dolby Vision не трогаем. HLS и DASH не обрабатываются;
  - `DefaultTrackSelector`: туннелирование, предпочтительные языки аудио и субтитров (`""` — по умолчанию,
    `device` — языки системы), `exceedRendererCapabilitiesIfNecessary`. Включилось ли туннелирование на деле,
    видно по `AudioTrackConfig.tunneling` (`onAudioTrackInitialized`): тогда у бейджа видео в инфопанели стоит `↳`.
    Бейдж аудио показывает дорожку и маршрут (`DTS 5.1 > проброс`, `AAC 5.1 > PCM 2.0`, `TrueHD 7.1 > AC3 5.1`);
  - `DefaultRenderersFactory`: приоритет декодеров-расширений отдельно для видео (`video_decoder_priority`, AV1) и аудио
    (`audio_decoder_priority`, ffmpeg) — передается в `buildVideoRenderers`/`buildAudioRenderers`,
    `enableDecoderFallback`. Приоритет аудио решает только, кто декодирует то, что не пробрасывается;
  - настройки звука берутся из `SettingsRepository.getAudioConfig`: в экспертном режиме ручные, в простом —
    профиль аудиосистемы (`AudioProfileLogic.simpleConfig`; во что перекодировать, решают возможности выхода);
  - цепочка звука (снаружи внутрь): `PassthroughPolicySink` → `TranscodingAudioSink` (если перекодирование) →
    `IecCarrierSink` (если `hd_audio_iec` и проброс) → sink с микшером PCM или стандартный.
    `PassthroughPolicySink` отдает выходу как есть только сжатый звук, разрешенный `AudioPassthroughLogic.isAllowed`
    (`audio_passthrough` и группа формата в `audio_passthrough_formats`; форматы вне списка, например AC-4, — по
    переключателю), остальной сжатый звук выход «не поддерживает», и его декодируют. При пробросе первым в списке
    стоит `MediaCodecAudioRenderer` без декодеров (пустой `MediaCodecSelector`): он берет только то, что sink
    примет как есть, поэтому проброс работает при любом приоритете декодеров;
  - микшер PCM считает во float (`SoftLimitAudioProcessor.ToFloat` перед ним) и заканчивается мягким ограничителем
    (`SoftLimiter`, порог -2 дБ), который возвращает 16 бит: стерео 5–8 каналов сводится на прежнем уровне плеера
    (пропорции RFC 7845, сумма 2 вместо sqrt(2)), а усиление голоса доходит до +12 дБ;
  - микшер PCM: свой `DefaultAudioSink` с `ChannelMixingAudioProcessor` (матрицы `AudioMixerLogic.createMatrices`
    по `audio_output_channels` и пресету) и аудиобуфером не больше 256 КБ, чтобы не упасть по памяти. Ставится,
    если каналы не «как в источнике» или пресет не стандартный. Выбранная раскладка — предел: шире сводится
    (стерео — RFC 7845, многоканальные — маршрутизация по позициям с правилами `swr_build_matrix2`), уже — остается
    как есть, а с `audio_upmix` расширяется простой матрицей (центр из фронтов, тылы из соседних каналов).
    С `audio_generate_lfe` в 5.1/7.1 собирается и LFE: все каналы поровну, суммарно -10 дБ (ресивер играет LFE
    на +10 дБ), уровень — ползунок LFE пресета. Матрица фильтровать не умеет, поэтому после микшера стоит
    `LfeLowPassAudioProcessor` (ФНЧ Баттерворта 2-го порядка 120 Гц, `LowPassFilter`); число входных каналов
    ему передает `Probe` перед микшером. При перекодировании тот же фильтр стоит в `TranscodingAudioSink`;
  - `hd_audio_iec`: выбранный sink оборачивается в `IecCarrierSink` (`IecCarrierOutput.wrap`). DTS-HD MA и
    TrueHD упаковываются в IEC 61937 (`DtsHdIecPacker`, `TrueHdMatPacker`, порт из plezy, совпадает с FFmpeg
    байт в байт) и идут отдельным `DefaultAudioSink` без процессоров с `AudioTrack` в `ENCODING_IEC61937`
    192 кГц/7.1. Остальное идет как раньше. Зачем: Fire OS принимает сырой `ENCODING_DTS_HD`, но передает
    только ядро DTS (а на части ресиверов тишину), сырой TrueHD передает не везде. Даунмикс на битстрим не
    влияет, как и для AC3/DTS passthrough. Несущая не используется при туннелировании, скорости не 1.0x,
    частотах семейства 44.1 кГц и если маршрут HDMI не принимает IEC 61937 192 кГц/7.1. Если маршрут несущую
    принимает, но сейчас она недоступна, TrueHD и DTS-HD декодируются (сырой поток там молчит); без несущей на
    маршруте (Android < 10, нет IEC 61937) — прежний путь, как с выключенной опцией. Трек с меткой A_DTS
    (MKV) проверяется по первому блоку: без HD-части он уходит в обычный sink (несущую ему предлагаем, только
    если обычный sink принимает DTS напрямую; ошибка такого перехода приходит как `InitializationException`).
    Бейдж аудио при несущей показывает `TrueHD (IEC 61937)` / `DTS-HD (IEC 61937)` (`carrierCodecLabel`).
    Защелка несовпадения потока сбрасывается в `loadPlaylist` и при `onMediaItemTransition`;
  - `audio_transcode` AC3/E-AC3: `TranscodingAudioSink` стоит перед микшером PCM. Декодированный PCM (16 бит или
    float, 44.1/48 кГц) сводится матрицей `AudioMixerLogic.mixCoefficients` в раскладку по `audio_output_channels`
    (не больше 5.1; если выходит 2.0 и меньше — не перекодируется и идет в микшер PCM) с пресетом микшера,
    кодируется FFmpeg (`AudioEncoderJni`, `libdddaudioenc.so` из `audioenc/`, AC3 640 кбит/с,
    E-AC3 1024 кбит/с, не больше 5.1 — кодировщик FFmpeg не умеет 7.1) и уходит в выбранный sink как проброс
    AC3/E-AC3. Время кадров считается от якоря по числу кадров, громкость плеера применяется в микшере
    (битстрим ее не меняет), скорость не применяется (как у обычного проброса). Если устройство не выводит
    AC3/E-AC3 битстримом или библиотеки нет — звук идет в PCM;
  - смена скорости при битстриме: `PlayerManager.bitstreamDisabledForSession` выключает проброс и перекодирование
    до конца сеанса плеера (плеер пересоздается), звук сводится в стерео PCM 2.0: по оптике и ARC многоканальный
    PCM не проходит, а для ускоренного просмотра стерео достаточно. Аналогично
    `tunnelingDisabledForSession` выключает туннелирование, если включено ручное увеличение (`ResizeMode.SCALE`
    масштабирует View, а туннельный кадр выводит композитор мимо него). Вопрос показывает `PlayerFragment`
    (`SessionPrompt`, `PlayerViewModel.checkSessionConflicts`), отмена возвращает 1.00x или прежний масштаб;
  - `NetworkBufferLoadControl` (см. ниже);
  - `LoudnessEnhancer` по `audioSessionId` (`loudness_boost`), `skipSilence`, шаг перемотки 15 с.
- `loadPlaylist(items, startIndex, startPosMs)` превращает `MediaItem` в `Media3MediaItem`
  (`mediaId = uuid`, метаданные, внешние субтитры, `LiveConfiguration`: target offset 15 с,
  max 30 с, ускорение до 1.05x) и делает `setMediaSources`.
- `buildMediaSources` выбирает HLS-фабрику, если тип — `application/x-mpegURL`. Тип определяется
  интерцептором OkHttp по Content-Type или расширению после редиректов, M3U-пробой или по расширению.
  Для остального используется `DefaultMediaSourceFactory`. Обе фабрики работают через `ParsingDataSource`.
- `reloadMediaItems` перезагружает источники с позиции при восстановлении после ошибки.
- `releasePlayer(isFinalRelease, saveState)`: при `saveState` запоминаются индекс и позиция,
  нужные для горячего перезапуска.
- `applyLiveSettings()` применяет настройки, которым не нужен перезапуск: дорожки, skip silence, громкость.

### Сеть

- Один `OkHttpClient`: редиректы, повтор соединения, таймауты 60 с (чтение 120 с).
- **TCP keepalive** (`KeepAliveSocketFactory`): пробы через 30 с простоя, каждые 15 с, 4 без ответа — соединение
  мертвое. Держит запись NAT роутера на паузе, а молча пропавший сервер дает ошибку (и повтор), а не ожидание 120 с.
  Интервалы задаются для сокета через `Os.setsockoptInt` (по умолчанию у ядра первая проба через 2 часа).
- **TLS trust-all и `hostnameVerifier { true }` оставлены намеренно.** На старых Android устарели корневые
  сертификаты, а открыть видео важнее, чем проверить сертификат. Не меняйте это.
- Application-interceptor записывает MIME-тип HLS, DASH или SmoothStreaming по Content-Type и финальному URL
  в `resolvedMediaTypes`. Так после редиректа выбирается правильная фабрика при повторной загрузке.
- HTTP-заголовки элемента (`MediaItem.headers` из Intent `headers` или из M3U `#EXTVLCOPT`/`#EXTHTTP`)
  привязаны к самому элементу: `buildMediaSources` берет их по `mediaId` (`headersByMediaId`), и фабрика
  источников этого элемента (`createParsingDataSourceFactory(headers)`, `ResolvingDataSource`) добавляет их
  в каждый запрос: плейлист HLS, сегменты, субтитры, чтение метаданных. Глобально по `onMediaItemTransition`
  их не переключаем: следующий элемент плеер начинает грузить раньше. User-Agent по умолчанию —
  `App.USER_AGENT`, только если источник не задал свой (`requestHeaders`). У `OkHttpDataSource.Factory`
  `setUserAgent` не вызываем: он добавляет User-Agent вторым заголовком поверх заданного, и сервер отвечает 403.

### Цепочка DataSource

```
ParsingDataSource → PrefetchingDataSource → DefaultDataSource (file/content/asset) → IdleReconnectDataSource → OkHttpDataSource
```

- **`IdleReconnectDataSource`.** Пока буфер полный, загрузчик не читает, но держит соединение. Если между чтениями
  прошло больше 60 с (пауза), перед следующим чтением соединение открывается заново с того же байта (`subrange`).
  Буфер плеера не теряется. Последняя линия - сторож в `PlayerViewModel.checkLoadingStall`: 20 с буферизации
  без роста буфера (впереди меньше 5 с) - перезагрузка видео с текущей позиции.

- **`PrefetchingDataSource` / `PrefetchStore`.** Если ответ уже скачан при M3U-пробе и оказался не M3U
  (например, HLS), он отдается плееру без повторного запроса. Ответ одноразовый и живет 30 с. `getUri()`
  возвращает URI после редиректов, чтобы относительные пути в HLS разрешались правильно.
  Это важно для ссылок с одноразовыми токенами.
- **`ParsingDataSource`** через `TeeDataSource` копирует первые байты в пайп (64 КБ, не больше 50 МБ)
  и в отдельной корутине разбирает метаданные MKV/MP4 (`UnifiedMetadataReader`: названия и языки дорожек,
  которые ExoPlayer не отдает). У каждой сессии свои пайпы, поэтому повторный `open` не ломает предыдущую.

### Экстракторы

- TS: HDMV DTS, non-IDR keyframes, detect access units, игнор splice info, расширенный поиск
  timestamp. Это нужно для битых IPTV-потоков. HLS использует те же флаги (`DefaultHlsExtractorFactory`).
- MP4: `FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES(_H265)`. Для CBR включен seeking.
- `DefaultLoadErrorHandlingPolicy`: не меньше 5 повторов загрузки.

## Буфер: NetworkBufferLoadControl

Наследник `DefaultLoadControl`, который вызывает его protected-конструктор.

| Режим | Сеть | Локальные файлы |
|:---|:---|:---|
| «Авто» (`target_buffer_mb = -1`) | 15–50 с по времени (прежние параметры) | 15–50 с |
| N МБ | целевой объем N МБ, лимит по времени снят | 15–50 с (по умолчанию) |

- Старт воспроизведения начинается после 0.5 с буфера, после ребуфера — после 5 с.
- В любом режиме объем ограничен `maxBufferBytesForHeap()`: резерв = `clamp(heap / 2, 64 МБ, 160 МБ)`,
  буфер = `heap − резерв`, но не меньше 16 МБ. Примеры: куча 100 МБ → 36 МБ, 192 → 96, 512 → 352, 1024 → 864.
  Без этого на ТВ с маленькой кучей 4K-ремуксы падали с `OutOfMemoryError`.
- `prioritizeTimeOverSizeThresholds = false` во всех режимах: время не должно раздувать буфер сверх объема.
- Варианты в настройках (`getTargetBufferOptions`) фильтруются по тому же лимиту. Если сохраненное значение
  больше недоступно, `getTargetBufferCorrectMB()` возвращает «Авто».
- Аудиобуфер при даунмиксе ограничен 256 КБ, и это ограничение остается.
- Процент заполнения для UI возвращает `getNetworkBufferFillPercent()`. Где он отображается, задает
  `buffer_display_mode`: выкл, в панели или рядом с часами (одиночные часы и инфопанель). Кнопка слева в панели
  (`PlayerFragment.renderVersionButton`): доступное обновление — кнопка с иконкой `system_update_alt` и номером версии,
  иначе при выводе «в панели» — просто текст буфера (`panel_buffer_value`, на одной линии со временем; под полосой
  перемотки его тогда нет), без него — кнопка с текущей версией (нажатие проверяет обновления).

## Восстановление после ошибок (`PlayerViewModel.tryRecoverFromError`)

- `HlsPlaylistTracker.PlaylistStuckException`: для live плеер переходит на край, для VOD — на текущую позицию,
  затем снова `prepare()`.
- IO-ошибки (`IO_UNSPECIFIED`, `NETWORK_CONNECTION_FAILED/TIMEOUT`) повторяются до 3 раз с текущей позиции
  через `reloadMediaItems`. Пока идет перезагрузка, `isRecoveringFromError = true`, и переход на тот же
  элемент не сбрасывает счетчик и настройки.
- `PARSING_CONTAINER_UNSUPPORTED` (за ссылкой скрыт HLS или DASH): если интерцептор узнал реальный тип,
  проблемному элементу задается MIME-тип, и плейлист перезагружается.
- Ошибка рендерера видео: видеодорожка отключается, звук продолжает играть (`videoDisabledError`
  и тост). При переходе на другой элемент видео включается обратно.
- Ошибка рендерера аудио: аудио выключается, тост подсказывает выбрать другую дорожку.
- Экран ошибки показывает код, сообщение и цепочку причин (`PlaybackErrorLogic.causeLines`), долгое нажатие
  копирует подробности. Для IO-ошибки чтения через провайдер другого приложения (`content://`) добавляется
  подсказка: такие приложения часто отдают файл без перемотки.

## AFR и FPS

- `utils/afr/*` переключает режим дисплея под частоту кадров видео (опционально и разрешение).
  Есть коррекция 23.976/29.97, удвоение частоты, пропуск 24 Гц, пауза при переключении и пропуск коротких видео.
- `RuntimeFpsDetector` измеряет FPS по рендеру, если в контейнере его нет.

## Обновления

`UpdateManager` читает `https://api.github.com/repos/usmanec/dddplayer/releases`, сравнивает версии,
скачивает APK и запускает установку (`REQUEST_INSTALL_PACKAGES`, `FileProvider`).
