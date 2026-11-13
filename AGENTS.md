# AGENTS.md

Памятка для AI-агентов и новых разработчиков. Прочитайте ее целиком перед изменениями.
Подробности по подсистемам лежат в [docs/](docs/README.md).

## Что это за проект

DDD Video Player (`top.rootu.dddplayer`) — видеоплеер для Android TV и телефонов (minSdk 23, targetSdk 34,
compileSdk 36). Умеет обычное 2D/HDR видео и стереопары (SBS/HSBS, OU/HOU, Interlaced, 3D(z)Tiled).
Стерео выводится в анаглиф (матрицы Дюбуа), Cardboard VR или моно (один глаз).
Кроме того, плеер открывает плейлисты из Intent и M3U/IPTV (группы, эфирный режим, переключение каналов),
а еще поддерживает PiP, AFR (подстройку частоты экрана), микшер даунмикса и встроенное обновление с GitHub Releases.

Модуль один — `:app`. Kotlin, Android Views (без Compose UI), MVVM на LiveData и корутинах.
Плеер построен на Media3/ExoPlayer 1.9.0, сеть идет через OkHttp 5. Настройки видео хранятся в Room,
глобальные — в SharedPreferences, постеры грузит Coil 2. Декодеры ffmpeg-audio и AV1 и нативная конвертация Dolby Vision P7 → 8.1 (модуль `dovi`, собирается
только с `-Pdovi=true`) подключены как AAR из `app/libs`.

## Сборка и проверка

```bash
./gradlew testDebugUnitTest      # юнит-тесты (JUnit + Robolectric)
./gradlew assembleDebug          # сборка APK
./gradlew assembleRelease        # релиз (minify выключен)
```

- Нужен JDK 17 и Android SDK (compileSdk 36). Версия приложения берется из git:
  `versionCode = git rev-list --count HEAD`, `versionName = git describe --tags --dirty`.
  Поэтому нужна полная история (`fetch-depth: 0`), а в shallow-клоне версия будет неправильной.
- CI: `.github/workflows/android-build.yml` на push/PR в `main` запускает юнит-тесты и собирает `assembleDebug`,
  APK выкладывается как артефакт `dddplayer-debug-apk` (при падении тестов - отчеты `unit-test-reports`).
- Релиз: `.github/workflows/release.yml` запускается на тег `vX.Y.Z` (`git tag v1.2.5 && git push origin v1.2.5`).
  Он собирает `assembleRelease`, подписывает APK ключом из приватного репозитория (секреты `KEYSTORE_*`,
  `RELEASE_SIGN_*`) и публикует GitHub Release с `app-release.apk`. Встроенное обновление
  (`UpdateManager`) берет последний не-draft и не-prerelease релиз и первый `.apk` в нем.
  Подпись настраивается в `app/build.gradle.kts` из переменных окружения, без них релиз собирается неподписанным.
- **Тесты:** `app/src/test` — JVM юнит-тесты. Чистая логика тестируется на JUnit, код с Android API
  (`Uri`, `SharedPreferences`, `org.json`, `SystemClock`) — под `RobolectricTestRunner`
  (`src/test/resources/robolectric.properties`: SDK 34 и простой `Application` вместо `App`).
  Синглтоны `SettingsRepository`/`AppDatabase` в тестах сбрасываются через reflection (см. `SettingsRepositoryTest`).
  Новую логику (парсеры, расчеты, настройки и их миграции) покрывайте тестами. UI-тестов нет.
- Если Android SDK недоступен (облачная песочница), тесты и компиляцию проверяет только CI. Поэтому:
  - перечитывайте свой diff и проверяйте импорты, порядок инициализации свойств и ссылки на ресурсы;
  - проверяйте, что каждый `R.string.*`, `R.id.*`, `R.layout.*` и `R.drawable.*` существует;
  - не пушьте наугад: одна выверенная правка лучше трех.

## Карта кода

```
app/src/main/java/top/rootu/dddplayer/
├── App.kt                      Application: CrashHandler, локаль
├── data/                       SettingsRepository (SharedPreferences + Room), VideoSettings, AppDatabase
├── model/                      MediaItem, SubtitleItem, StereoInputType/OutputMode, ResizeMode,
│                               AspectRatioMode, PlaybackSpeed, MenuItem
├── player/                     PlayerManager (ExoPlayer, OkHttp, источники, M3U-проба),
│                               NetworkBufferLoadControl, PrefetchingDataSource, ParsingDataSource
├── logic/                      Чистая логика: M3uParser, TrackLogic, AnaglyphLogic, AudioMixerLogic,
│                               Mp4/UnifiedMetadataReader, SettingsMutator, StereoFormatLogic, DeviceInfoLogic,
│                               UpdateManager
├── renderer/                   StereoGLSurfaceView, StereoRenderer, ShaderSource (GLSL ES 2.0/3.0)
├── viewmodel/                  PlayerViewModel (центр всего), AnaglyphDelegate, SettingsViewModel,
│                               GlobalSettingsViewModel, UpdateViewModel
├── ui/                         PlayerActivity (Intent, PiP, результат), PlayerFragment (экран плеера),
│                               GlobalSettingsActivity, FileBrowserActivity (файлы), DeviceInfoActivity (диагностика),
│                               VrConfigActivity, CrashActivity
│   ├── controller/             PlayerUiController (вьюхи), PlayerInputHandler (пульт),
│   │                           PlayerTouchHandler (жесты), PlayerTimerController (таймеры, часы)
│   ├── adapter/                RecyclerView адаптеры меню/плейлиста
│   └── wiget/                  OutlineTextView (часы с обводкой), CenterLayoutManager
└── utils/                      IntentUtils (разбор Intent), MediaFormatHelper, StereoTypeDetector,
                                DuboisMath, CardboardParamsParser, CrashHandler, LocaleUtils, DeviceInfoCollector, afr/*
```

Самые большие файлы: `PlayerViewModel.kt`, `PlayerFragment.kt`, `PlayerUiController.kt`,
`GlobalSettingsActivity.kt` и `PlayerManager.kt`. Читайте их по частям через grep по именам функций.

## Ключевые потоки (кратко)

1. **Запуск:** `PlayerActivity.onCreate` разбирает Intent (`IntentUtils.parseIntent`) и вызывает
   `PlayerViewModel.loadMedia`. Запуск из лаунчера открывает стартовый экран `FileBrowserActivity`:
   «Продолжить просмотр», плейлисты по ссылке (`forceM3uProbe = true`), хранилища, SMB и DLNA.
2. **Загрузка:** `loadMedia` проверяет одиночную ссылку `.m3u`/`.m3u8` через `PlayerManager.expandM3u`.
   Ссылка скачивается один раз, HLS (`#EXT-X-`) отдается плееру из `PrefetchStore`.
   Затем `loadPlaylist` (VM) передает список в `PlayerManager.loadPlaylist`, а тот собирает `MediaSource`.
3. **Вывод:** 2D идет напрямую в `SurfaceView` (нужно для HDR). Стерео идет через `StereoGLSurfaceView` → `StereoRenderer`
   (`SurfaceTexture`/`samplerExternalOES`, шейдеры ES 3.0 с откатом на ES 2.0).
   Переключением поверхностей занимается `PlayerUiController.setSurfaceMode`.
4. **Состояние UI:** `PlayerViewModel` отдает LiveData, `PlayerFragment.observeViewModel` их рисует.
   Одноразовые события оборачиваются в `Event<T>` или сбрасываются в null.
5. **Настройки:** глобальные лежат в `SettingsRepository` (SharedPreferences), а для каждого видео —
   в Room `VideoSettings` по URI (стерео-режим, глубина, позиция, дорожки).
   При смене «жестких» настроек (`getHardSettingsSignature`) плеер пересоздается, остальные применяются на лету.

Подробно: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/PLAYBACK.md](docs/PLAYBACK.md),
[docs/PLAYLISTS.md](docs/PLAYLISTS.md), [docs/RENDERING.md](docs/RENDERING.md),
[docs/SETTINGS.md](docs/SETTINGS.md), [docs/INTENT_API.md](docs/INTENT_API.md).

## Правила проекта (обязательно)

- **Не ломайте текущее поведение воспроизведения.** Новую функциональность добавляйте за опцией,
  а значения по умолчанию подбирайте так, чтобы у существующих пользователей ничего не поменялось.
  Примеры из истории проекта: PiP по умолчанию выключен, эфирный режим тоже выключен,
  внешняя позиция по умолчанию применяется сразу.
- **TLS trust-all в `PlayerManager` оставлен намеренно.** На старых Android устарели корневые сертификаты,
  и видео с недоверенным сертификатом лучше, чем видео, которое не открывается.
  Не «чините» это и не предлагайте как замечание ревью.
- **Буфер в МБ** (`NetworkBufferLoadControl`) применяется только к сетевому видео.
  Локальные файлы и режим «Авто» используют прежние параметры по времени (15–50 с).
  Размер всегда ограничен кучей: `maxBufferBytesForHeap()`, резерв `clamp(heap/2, 64 МБ, 160 МБ)`.
- **M3U:** HLS отсекается по любому тегу `#EXT-X-`. Повторного запроса нет: ответ пробы
  переиспользуется одноразово через `PrefetchStore` (TTL 30 с), иначе теряются одноразовые токены.
- **Последний канал** восстанавливается только для M3U-плейлиста и только по точному URI.
- **Строки:** любая новая строка добавляется во все 5 локалей: `values` (en), `values-ru`, `values-uk`,
  `values-be`, `values-zh-rCN`. Хардкодить текст в UI нельзя.
- **Лицензии:** новая библиотека в APK или код, перенесенный из другого проекта, добавляется
  в `OpenSourceLicenses` (экран «Приложение → Лицензии»), у перенесенного кода — еще и ссылка на источник в шапке файла.
- **Комментарии в коде пишутся на русском**, коротко и по делу, в стиле окружающего кода.
- **Миграции настроек:** если меняется смысл ключа, добавьте миграцию в `SettingsRepository`
  (пример: `show_clock = false` превращается в `clock_transparency = CLOCK_OFF`).
  Не переиспользуйте старый ключ с новым смыслом.
- Новая настройка должна пройти весь путь: геттер и сеттер в `SettingsRepository`, `GlobalSettingsViewModel`,
  вьюха и обработчик в `GlobalSettingsActivity` и `activity_global_settings.xml`, строки во всех локалях.
  Если для нее нужен перезапуск плеера, добавьте ее в `getHardSettingsSignature()`. См. [docs/SETTINGS.md](docs/SETTINGS.md).

## Частые ловушки

- **Порядок инициализации свойств в `PlayerUiController`.** Свойства инициализируются сверху вниз.
  Если свойство обращается к `controlsView` и другим полям, объявленным ниже, сборка упадет с ошибкой
  `Variable '...' must be initialized`. Новые поля объявляйте после тех, от которых они зависят.
- **LiveData:** в главном потоке используйте `.value`, из корутин на IO и из колбэков других потоков — `postValue`.
  Помните, что `postValue` асинхронный, и следом читать `.value` нельзя.
- **`mediaId` у Media3 `MediaItem` равен `MediaItem.uuid`** из модели проекта. По нему сопоставляются
  текущий элемент, плейлист, группы и зап-переключение. `absoluteIndex` — индекс в полном плейлисте
  (без фильтра по группе).
- **Колбэки диалогов** храните в полях, а не захватывайте в лямбде один раз при создании диалога.
  Иначе после переоткрытия будут вызываться устаревшие ссылки (такая ошибка уже была в плейлисте).
- **`isRecoveringFromError`:** при перезагрузке после IO-ошибки `onMediaItemTransition` приходит для того же видео.
  В этот момент нельзя сбрасывать счетчик повторов и настройки видео.
- **GL поток.** Методы `StereoRenderer` вызываются из UI. Uniform-ы применяются в `onDrawFrame`,
  а смена программы делается флагом `shouldReloadShaders`. Не вызывайте GLES вне GL потока.
- **ES 3.0:** контекст создается как ES 3, если устройство это поддерживает. Если шейдеры ES 3.0 не собрались
  или нет `GL_OES_EGL_image_external_essl3`, рендер откатывается на ES 2.0 и сообщает
  `onEs3ShadersUnavailable`. Правки шейдеров вносите **в обе версии** в `ShaderSource.kt`.
  Режим «GLES 2.0» создает контекст ES 2.0 целиком, это обход проблемных драйверов. Шейдеры ES 3.0 → 2.0
  переключаются на лету, а обратно (из контекста ES 2.0) — только после перезапуска экрана плеера.
- **Интент-ключи** читает только `IntentUtils`. Если меняете их, обновите README.md, README_RU.md и docs/INTENT_API.md.

## Git и PR

- Работайте в своей ветке, PR делайте в `main`. Force-push в общие ветки запрещен: чтобы подтянуть `main`,
  делайте merge.
- Сообщения коммитов пишутся по Conventional Commits на английском: `type(scope): summary`, где type —
  `feat`/`fix`/`refactor`/`docs`, а scope — `player`/`ui`/`buffer`/`live`/`intent`/`render`/...
  Если коммит закрывает issue, номер ставится в конце: `feat(ui): forced aspect ratio (#17)`.
  Одна логическая правка — один коммит.
