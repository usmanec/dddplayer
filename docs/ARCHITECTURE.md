# Архитектура

## Слои

```
PlayerActivity ──(Intent, PiP, результат)──┐
                                            ▼
PlayerFragment ◄──LiveData── PlayerViewModel ──► PlayerManager ──► ExoPlayer
 │  PlayerUiController (вьюхи)    │  AnaglyphDelegate     │  OkHttp, DataSource-цепочка,
 │  PlayerInputHandler (пульт)    │  SettingsRepository   │  NetworkBufferLoadControl, M3U-проба
 │  PlayerTouchHandler (жесты)    │  (prefs + Room)       │
 │  PlayerTimerController         │                       │
 ▼                                ▼                       ▼
SurfaceView (2D/HDR)  или  StereoGLSurfaceView ─► StereoRenderer (GLSL)
```

- **`PlayerActivity`** — единственная точка входа для видео. Здесь проверяются разрешения на файлы,
  разбирается Intent (`IntentUtils`) и работает PiP (действие
  play/pause через `RemoteAction` и `BroadcastReceiver`, авто-PiP по настройке). Точка входа лаунчера —
  `FileBrowserActivity`, у `PlayerActivity` своя `taskAffinity`: иначе Android TV считает окно PiP и иконку
  приложения одной задачей и открывает стартовый экран внутри задачи плеера. Старые ярлыки на `PlayerActivity`
  (MAIN/LAUNCHER) по-прежнему переводятся на стартовый экран. В PiP система переносит плеер
  в отдельную задачу, поэтому он запоминается в `PlayerActivity.pipActivity`: новое видео закрывает старое окно
  PiP (`closeFromPictureInPicture`), а открытие приложения снаружи (лаунчер, «Недавние», `PipReturnCallbacks`)
  разворачивает его повторным запуском той же активити (стартовый экран, открытый иконкой, при этом сразу
  закрывается и не показывается) (`restorePictureInPicture`, SystemUI считает это
  возвратом из PiP). Плеер, свернутый кнопкой «Домой» (не в PiP), `PipReturnCallbacks` при старте стартового
  экрана поднимает вместе с его задачей (`restorePlayer` → `ActivityManager.moveTaskToFront`, разрешение
  `REORDER_TASKS`): плеер живет в своей задаче или в задаче вызвавшего приложения (TorrServe, Lampa), и лаунчер
  его не находит. Экраны, открытые из самого плеера (настройки), это не задевает: плеер в этот момент еще запущен.
  В `finish()` (`prepareFinish`) формируется результат для вызывающего приложения (`return_result`). Нажатия клавиш передаются в
  `PlayerFragment.handleKeyEvent` через `dispatchKeyEvent`.
- **`PlayerFragment`** — экран плеера. Создает GL-поверхность (выбор ES 2/3), подписывается на LiveData
  (`observeViewModel`), показывает меню, диалоги (продолжение просмотра, плейлист, группы,
  качество, зум) и AFR. Еще здесь обрабатывается «Назад» с подтверждением выхода.
- **`PlayerUiController`** — `findViewById` и операции над вьюхами: панели, часы, бейджи,
  переключение поверхностей, раскладка стерео/VR.
- **`PlayerInputHandler`** — логика пульта: OSD-настройки, OK/Вверх по настройкам, перемотка
  по сикбару, эфирный режим (OK — плейлист, Влево/Вправо — каналы, Вверх — инфопанель).
- **`PlayerViewModel`** — состояние плеера и плейлиста. Отвечает за загрузку медиа, группы, зап-переключение,
  продолжение просмотра, восстановление после ошибок, настройки текущего видео (стерео, дорожки, масштаб,
  соотношение сторон), меню и FPS/инфо-бейджи. 3D/VR параметры вынесены в `AnaglyphDelegate`.
- **`SettingsViewModel`** — состояние OSD-панели настроек (видимость, текущий пункт).
- **`PlayerManager`** — владеет ExoPlayer. Создает и пересоздает плеер, собирает источники,
  применяет заголовки, делает M3U-пробу и выдает метаданные дорожек.
- **`GlobalSettingsActivity` / `GlobalSettingsViewModel`** — экран глобальных настроек, проверка
  и установка обновлений (`UpdateManager`, GitHub Releases).
- **`FileBrowserActivity`** — стартовый экран (открывается при запуске из лаунчера) и встроенный файловый браузер.
  Главный экран: «Продолжить просмотр» (история по источникам: папка или плейлист, `HistoryEntry`;
  серия в той же папке заменяет запись, а не добавляет новую; первые 3, остальные — «Вся история»),
  плейлисты по ссылке (`SavedPlaylist`, добавление/изменение/удаление, прежний `startup_playlist_uri` переносится сюда)
  хранилища, сетевые папки SMB и медиасерверы DLNA. Плеер получает источник через внутренние ключи `IntentUtils.EXTRA_HISTORY_*` и обновляет историю
  при смене видео и сохранении (`PlayerViewModel.updateWatchHistory`); видео, открытые другими приложениями, в историю
  не попадают. Файловый браузер: внутренняя память, SD, USB (корни — `StorageRoots`,
  фильтр и естественная сортировка — `FileBrowserLogic`). Видео папки передаются плееру плейлистом через
  `video_list` (Uri в extras: `file://` в data запрещен системой) с `FLAG_ACTIVITY_CLEAR_TOP | SINGLE_TOP`,
  чтобы не открыть второй плеер. Путь `file://` дает плееру перемотку (в отличие от `content://`
  некоторых файловых менеджеров) и поиск субтитров рядом с видео. На Android 11+ субтитры (не медиафайлы) видны
  только с «доступом ко всем файлам» (`MANAGE_EXTERNAL_STORAGE`), браузер предлагает его отдельным пунктом.
- **DLNA** (`logic/DlnaLogic`, `player/DlnaClient`) — без сторонних библиотек: поиск медиасерверов SSDP (M-SEARCH
  на 239.255.255.250:1900 с `MulticastLock`, через каждый IPv4-интерфейс, на `MediaServer:1`, `ContentDirectory:1`
  и `upnp:rootdevice`; описание устройства читается с таймаутом 4 с), описание устройства (служба ContentDirectory), запрос `Browse`
  постранично и разбор DIDL-Lite (папки, видео по `protocolInfo` video/*, субтитры text/srt и т. п.). Видео папки
  сервера открываются плейлистом по HTTP-ссылкам (перемотка через Range), субтитры сервера передаются в
  `video_list.subtitles`. В истории источник — адрес описания сервера и id папки (`HomeLogic.dlnaSource`): при
  продолжении папка читается заново, видео ищется по ссылке, затем по названию (адрес сервера мог смениться).
- **SMB** (`logic/SmbLogic`, `player/SmbConnections`, `player/SmbDataSource`, библиотека smbj) — сетевые папки
  стартового экрана (`smb_servers`: адрес `smb://host/share[/folder]`, пользователь, пароль, домен; пусто — гость).
  Список общих ресурсов сервера smbj не получает, поэтому ресурс указывается в адресе. Плеер получает ссылки
  `smb://...`: `SmbRoutingDataSource` под `PrefetchingDataSource` отдает их в `SmbDataSource` (чтение с любого места
  файла, перемотка), остальное — в `DefaultDataSource`. Учетные данные `SmbConnections` находит по серверу и ресурсу
  среди сохраненных папок; сессии и ресурсы переиспользуются. Субтитры рядом с видео (`SmbLogic.subtitlesFor`)
  передаются в `video_list.subtitles`. В истории источник — ссылка на папку без учетных данных.
- **`VrConfigActivity`** принимает ссылки Cardboard (QR-коды профилей линз) и сохраняет параметры дисторсии.
- **`CrashHandler` / `CrashActivity`** перехватывают необработанные исключения и показывают лог. К логу
  дописывается отчет об устройстве (`DeviceInfoCollector`); собирается он в процессе `:crash`, а не в упавшем.
- **`DeviceInfoActivity`** (Настройки → Приложение → Диагностика устройства) — отчет для разбора жалоб:
  модель и Android, режимы экрана и HDR (`Display`), аудиовыходы с форматами из EDID (`AudioDeviceInfo`) и
  проброс по мнению Media3 (`AudioCapabilities`), видео- и аудиодекодеры (`MediaCodecList`: HW/SW, размер,
  10-bit/HDR10/HDR10+, профили Dolby Vision) плюс встроенные dav1d и FFmpeg. Данные собирает
  `DeviceInfoCollector` (каждый раздел отдельно, ошибка одного не ломает остальные), подписи профилей —
  `DeviceInfoLogic`. Экран тот же, что у лога ошибки: `LogReportView` (layout `dialog_error_log`) — выгрузка
  на termbin.com со ссылкой и QR-кодом, копирование всего текста долгим нажатием на лог. Последняя выгрузка
  кешируется (`LogUploadCache`, файл настроек `log_upload_cache`): при том же SHA-256 текста ссылка и QR
  показываются сразу. termbin хранит вставки месяц, кеш верит ссылке 15 дней.

## Модель данных

- `model/MediaItem` — элемент плейлиста. Поля: `uri`, `title`, `filename`, `posterUri`, `headers`, `subtitles`,
  `startPositionMs`, `group`, `uuid` (он же `mediaId` в Media3) и `absoluteIndex` (индекс в полном плейлисте).
- `data/VideoSettings` (Room, таблица `video_settings`, ключ — URI) хранит стерео-режим, анаглиф,
  swap, глубину, последнюю позицию и длительность, а также ID аудио- и субтитровой дорожек.
- Глобальные настройки лежат в `SettingsRepository` (SharedPreferences), см. [SETTINGS.md](SETTINGS.md).

## Жизненный цикл плеера

1. `PlayerFragment.onViewCreated` создает `PlayerUiController` и GL-поверхность, а затем, когда плеер
   создан, подключает поверхность (`attachSurfaceToPlayer`).
2. `PlayerViewModel.loadMedia` → `PlayerManager.loadPlaylist`. Если ExoPlayer еще нет, вызывается
   `initializePlayer()`, иначе меняются источники.
3. `onMediaItemTransition` → `handleMediaItemTransition`: сбрасывается стерео-режим, загружаются
   `VideoSettings` по URI (или глобальные значения по умолчанию), для первого элемента нового плейлиста
   срабатывает продолжение просмотра, а для M3U запоминается последний канал.
4. Если при возврате из настроек сменились «жесткие» настройки (декодеры, туннелирование, DV→HEVC,
   даунмикс, буфер), `checkSettingsAndRestart()` пересоздает плеер с сохранением позиции.
   Остальные настройки применяются через `applyLiveSettings()`.
5. Во время работы плеер сохраняет текущие настройки видео (`saveCurrentSettings`) при уходе с экрана и в `finish()`.

## Потоки и корутины

- ExoPlayer и LiveData обновляются в главном потоке. Сеть и чтение файлов при M3U-пробе идут на
  `Dispatchers.IO` (`expandM3u`), после чего результат возвращается в `viewModelScope`.
- `ParsingDataSource` разбирает метаданные MKV/MP4 в собственной корутине, данные приходят через пайп.
- `StereoRenderer` работает в GL-потоке `GLSurfaceView` (`RENDERMODE_WHEN_DIRTY`, кадры по
  `onFrameAvailable`).

## Ресурсы

- Разметка лежит в `res/layout`: `player_fragment.xml`, `playback_controls.xml`, `layout_top_info_panel.xml`,
  `layout_top_settings_panel.xml`, `activity_global_settings.xml` и `dialog_*.xml`.
- Строки есть в 5 локалях: `values` (en), `values-ru`, `values-uk`, `values-be`, `values-zh-rCN`.
  Lint `MissingTranslation` выключен, поэтому за полнотой переводов следите сами.
- `xml/network_security_config.xml` разрешает cleartext и пользовательские сертификаты.
