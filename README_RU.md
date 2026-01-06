<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="128" height="128" alt="DDD Player Logo"/>
  <h1>DDD Video Player</h1>

  <p>
    <a href="LICENSE">
      <img src="https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat-square" alt="License GPL v3"/>
    </a>
    <a href="README.md">
      <img src="https://img.shields.io/badge/Lang-English-red.svg?style=flat-square" alt="Read in English"/>
    </a>
  </p>

  <p>
    <b>Продвинутый 3D и HDR видеоплеер для Android TV и телефонов</b>
    <br>
    <i>Поддерживает Android 6.0 (API 23) и выше.</i>
  </p>
</div>

---

**DDD Video Player** — это продвинутый видеоплеер для Android (TV и телефонов), позволяющий просматривать не только обычное видео, но и стереоскопические видеопары, создавая эффект 3D.

Для получения 3D-эффекта потребуются **анаглиф-очки** (например, Красно-Синие) или оборудование типа **Cardboard VR**.

### Основные возможности

*   **Поддержка стереоформатов**:
    *   Горизонтальная стереопара (Side-by-Side)
    *   Вертикальная стереопара (Top-Bottom/Over-Under)
    *   Чересстрочный (Interlaced)
    *   3D(z) Tile Format (720p в 1080p)
*   **Режимы вывода**:
    *   **Анаглиф**: Высококачественный алгоритм Дюбуа с настраиваемыми матрицами (Красно-Синий, Зелено-Малиновый, Желто-Синий).
    *   **VR / Cardboard**: Коррекция дисторсии линз для VR-гарнитур.
    *   **Моно**: Просмотр 3D-контента в 2D (только левый или правый глаз).
*   **Поддержка HDR**: Прямой рендеринг через SurfaceView для нативного воспроизведения HDR на поддерживаемых экранах (в 2D режиме).
*   **Проброс звука (Passthrough)**: Поддержка вывода 5.1 звука (AC3/DTS) на ресиверы через HDMI/Optical.
*   **Субтитры**: Корректное отображение субтитров в 3D-режимах.
*   **Плейлисты**: Встроенный менеджер плейлистов с поддержкой постеров.
*   **Настройки**:
    *   Регулировка глубины 3D (параллакс).
    *   Смена глаз местами (L/R Swap).
    *   Тонкая настройка цветов анаглифа (Оттенок, Утечка).
    *   Параметры линз VR (K1, K2, Масштаб).

### Скриншоты

|                Анаглиф                | Cardboard VR |           Обычный плеер           |
|:-------------------------------------:|:---:|:---------------------------------:|
| ![Anaglyph](screenshots/anaglyph.png) | ![Cardboard VR](screenshots/cardboard-vr.png) | ![Player](screenshots/player.png) |

### Ссылки на последний релиз:
- [Страница релиза](https://github.com/usmanec/dddplayer/releases/latest)
- [Прямая ссылка на загрузку APK](https://github.com/usmanec/dddplayer/releases/latest/download/app-release.apk)

### Intent API (Интеграция)

Вы можете запускать DDD Player из сторонних приложений, используя `Intent.ACTION_VIEW`.

#### Базовый запуск (Одно видео)
```kotlin
val intent = Intent(Intent.ACTION_VIEW)
intent.setDataAndType(Uri.parse("https://example.com/video.mp4"), "video/*")
intent.putExtra("title", "Название фильма")
intent.putExtra("poster", "https://example.com/poster.jpg")
startActivity(intent)
```

#### Поддерживаемые параметры (Extras)

| Ключ | Тип | Описание |
| :--- | :--- | :--- |
| `title` | String | Заголовок видео, отображаемый в интерфейсе. |
| `filename` | String | Имя файла (используется, если нет заголовка). |
| `poster` | String | Ссылка на изображение постера. |
| `position` | Int/Long | Позиция старта в миллисекундах. |
| `headers` | String[] | HTTP заголовки массивом: `["Key1", "Val1", "Key2", "Val2"]`. |
| `return_result`| Boolean | Если true, плеер вернет позицию просмотра в вызывающее приложение при закрытии. |

#### Субтитры (Одно видео)
Для добавления внешних субтитров к одиночному видео используйте следующие параметры:

| Ключ | Тип | Описание |
| :--- | :--- | :--- |
| `subs` | Parcelable[] (Uri) | Массив Uri ссылок на файлы субтитров. |
| `subs.name` | String[] | Массив названий (например, "Русский", "English"). |
| `subs.filename` | String[] | Массив имен файлов (опционально). |

**Пример:**
```kotlin
val subUris = arrayOf(Uri.parse(".../sub_ru.srt"), Uri.parse(".../sub_en.srt"))
val subNames = arrayOf("Русский", "English")
intent.putExtra("subs", subUris)
intent.putExtra("subs.name", subNames)
```

#### Поддержка плейлистов
Для передачи плейлиста используйте extra `video_list` (ParcelableArray of URIs) вместе с параллельными массивами метаданных.

| Ключ | Тип | Описание |
| :--- | :--- | :--- |
| `video_list` | Parcelable[] (Uri) | **Обязательно.** Список ссылок на видео. |
| `video_list.name` | String[] | Список названий. |
| `video_list.filename` | String[] | Список имен файлов. |
| `video_list.poster` | String[] | Список ссылок на постеры. |
| `video_list.subtitles` | ArrayList&lt;Bundle&gt; | Список субтитров для каждого элемента плейлиста. |

**Структура субтитров в плейлисте:**
Параметр `video_list.subtitles` — это `ArrayList`, содержащий объекты `Bundle`. Каждый `Bundle` соответствует видео с тем же индексом в плейлисте.
Внутри каждого Bundle используются ключи:
*   `uris`: Parcelable[] (Uri) - Файлы субтитров.
*   `names`: String[] - Названия языков.

**Пример (Плейлист):**
```kotlin
val videoUris = arrayOf(Uri.parse(".../vid1.mp4"), Uri.parse(".../vid2.mp4"))
val titles = arrayOf("Фильм 1", "Фильм 2")

// Субтитры для Видео 1
val subs1 = Bundle()
subs1.putParcelableArray("uris", arrayOf(Uri.parse(".../vid1_sub.srt")))
subs1.putStringArray("names", arrayOf("Русский"))

// Субтитры для Видео 2 (без субтитров)
val subs2 = Bundle() 

val subsList = ArrayList<Bundle>()
subsList.add(subs1)
subsList.add(subs2)

intent.putExtra("video_list", videoUris)
intent.putExtra("video_list.name", titles)
intent.putParcelableArrayListExtra("video_list.subtitles", subsList)
```

### Технологии

*   **Язык**: Kotlin
*   **Ядро плеера**: Media3 (ExoPlayer)
*   **Рендеринг**: OpenGL ES 2.0 (Кастомные шейдеры для 3D/Анаглифа)
*   **UI**: Android Views, ConstraintLayout
*   **Загрузка изображений**: Coil
*   **База данных**: Room
*   **Архитектура**: MVVM

### Вклад в проект и Обратная связь

Мы приветствуем участие сообщества в развитии проекта! Не стесняйтесь вносить изменения, исправлять ошибки или предлагать новые функции.

*   **Сообщения об ошибках**: Нашли баг? Пожалуйста, создайте [Issue](https://github.com/usmanec/dddplayer/issues) на GitHub.
*   **Pull Requests**: Хотите улучшить код? Форкните репозиторий, внесите изменения и отправьте Pull Request. Мы рады любым улучшениям!
*   **Идеи**: Есть предложения? Давайте обсудим их в разделе Issues.

### Лицензия

Этот проект распространяется под лицензией **GNU General Public License v3.0 (GPLv3)**.

Подробности смотрите в файле [LICENSE](LICENSE).