# Intent API: реализация

Пользовательское описание с примерами есть в [README.md](../README.md#intent-api-integration)
и [README_RU.md](../README_RU.md#intent-api-интеграция). Этот документ описывает, как ключи разбираются в коде.
При изменении ключей обновляйте все три документа.

## Точка входа

`PlayerActivity` (`exported`, повторный Intent приходит в `onNewIntent`) принимает `ACTION_VIEW`
для `rtsp`, `http`, `https`, `ftp`, `content`, `file`. Подходят MIME-типы `video/*`, `application/sdp`,
HLS (`application/vnd.apple.mpegurl`, `application/x-mpegURL`), `application/dash+xml`, а также
видео-расширения в пути без MIME-типа (см. `AndroidManifest.xml`). Для не-сетевых URI сначала запрашивается разрешение
`READ_MEDIA_VIDEO` (API 33+) или `READ_EXTERNAL_STORAGE`.

`handleIntent` → `IntentUtils.parseIntent(context, intent)` → `Pair<List<MediaItem>, startIndex>`
→ `PlayerViewModel.loadMedia`. Флаг `return_result` читается отдельно.

## Разбор (`utils/IntentUtils`)

Если есть `video_list`, работает **режим плейлиста**, иначе при наличии `intent.data` разбирается
**одиночный файл**. Если нет ни того ни другого, ничего не открывается (в debug-сборке — тестовое видео).
Запуск из лаунчера (`ACTION_MAIN`) переводит на стартовый экран (`FileBrowserActivity`).

Внутренние ключи стартового экрана (`IntentUtils.EXTRA_HISTORY_KIND/SOURCE/NAME`, `EXTRA_FORCE_M3U`) с префиксом
`top.rootu.dddplayer.` внешним приложениям не нужны: по ним плеер ведет историю «Продолжить просмотр»
и принудительно проверяет ссылку плейлиста на M3U.

### Одиночный файл

| Ключ | Тип | Поле `MediaItem` |
|:---|:---|:---|
| `title` / `android.intent.extra.TITLE` | String | `title` (иначе имя файла из `OpenableColumns.DISPLAY_NAME` или последний сегмент пути) |
| `position` | Int / Long / String | `startPositionMs` (`getLongExtraCompat`) |
| `thumbnail` | String | `posterUri` |
| `headers` | String[] / ArrayList<String> | `headers` (пары имя-значение) |
| `subs`, `subs.name`, `subs.filename` | Uri[] (или ArrayList), String[] | `subtitles`. Если не переданы, для `file://` ищутся файлы рядом с видео (`SubtitleFinder`) |

### Плейлист

| Ключ | Тип | Примечание |
|:---|:---|:---|
| `video_list` | Parcelable[] Uri / ArrayList<Uri> / ArrayList<String> | Обязателен |
| `video_list.name` | String[] | Названия (иначе `filename`, иначе сегмент пути) |
| `video_list.filename` | String[] | Имена файлов |
| `video_list.thumbnail` | String[] | Постеры, пустая строка — без постера |
| `video_list.subtitles` | ArrayList<Bundle> | В каждом Bundle: `uris` (Uri[]) и `names` (String[]) |
| `headers` | String[] | Общие для всех элементов |
| `start_index` | Int | Запасной стартовый индекс (если `intent.data` не совпала ни с одним элементом), приводится к допустимому диапазону |
| `position` | Int / Long / String | Применяется только к элементу, URI которого совпадает с `intent.data` |

Стартовый элемент — тот, чей URI совпадает с `intent.data` (основной способ). `start_index` используется, только если
`intent.data` не задана или не найдена в списке; без обоих стартует первый элемент.

Массивы строк читает `getSmartStringArray`: подходят `String[]`, `ArrayList<String>`, `CharSequence[]`
и `ArrayList<CharSequence>`.

## Позиция от внешнего приложения

`startPositionMs > 0` у стартового элемента:
- при `external_position_mode = APPLY` (по умолчанию) плеер сразу стартует с этой позиции;
- при `= ASK` плеер стартует с 0 и показывает диалог продолжения с этой позицией.

Позиция из Intent приоритетнее сохраненной в плеере. См. [PLAYLISTS.md](PLAYLISTS.md#продолжение-просмотра-handleresumelogic).

## Результат (`return_result = true`)

`PlayerActivity.finish()` вызывает `setResult(RESULT_OK, Intent("top.rootu.dddplayer.intent.result.VIEW"))`:

| Ключ | Тип | Значение |
|:---|:---|:---|
| `data` | Uri | URI текущего (последнего) элемента |
| `position` | Long | Позиция в мс. При завершении видео равна длительности |
| `duration` | Long | Длительность в мс |
| `end_by` | String | `completion` (видео закончилось само; при `return_result` активити закрывается) или `user` |

## Пропуск заставки и титров (`SkipSegmentLogic`)
`segments` (одно видео) и `video_list.segments` (String[] по элементам плейлиста) разбирает `SkipSegmentLogic.parse`
в `MediaItem.segments`: `intro|recap|credits:<начало>-<конец>` через `;`, мс, у `credits` конец можно опустить.
Неизвестные виды, конец раньше начала и заставка без конца отбрасываются. В `PlayerViewModel`:
- `progressUpdater` (раз в 200 мс во время воспроизведения) находит отрезок под позицией (`active`) и при смене
  отдает его в `skipSegment`; при `auto_skip_intro` заставка и пересказ перематываются сразу, без кнопки;
- `skipCurrentSegment` перематывает в `target`: конец отрезка, а для титров, кончающихся в последние 15 с
  (`runsToEnd`), - в конец видео: дальше обычный переход к следующему элементу или завершение.
Кнопка (`PlayerUiController.showSkipButton`) видна, пока идет отрезок; OK нажимает ее при скрытой панели управления
(`PlayerInputHandler`; с панелью OK - ее кнопки). Автопропуск (`auto_skip_intro`) - один раз за видео: перемотал назад
в заставку - кнопка. «Закончится в» считается до начала титров, идущих до конца (`creditsStart`).
