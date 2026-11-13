# Prebuilt native libraries (AAR)

This folder contains prebuilt AARs that `app/build.gradle.kts` picks up automatically (`fileTree("libs") { include("*.aar") }`):

| File | Module | Purpose |
| :--- | :--- | :--- |
| `lib-decoder-ffmpeg-release.aar` | `androidx.media3:lib-decoder-ffmpeg` | Software audio decoders (AC3/E-AC3/DTS/TrueHD and more) |
| `lib-decoder-av1-release.aar` | `androidx.media3:lib-decoder-av1` | Software AV1 video decoder (dav1d) |
| `lib-dovi-release.aar` | `:dovi` (this repository) | Dolby Vision Profile 7 → 8.1 conversion (`libdovi_bridge.so`: JNI bridge + libdovi) |

**The AARs must be built from the same Media3 version as `media3` in `gradle/libs.versions.toml` (currently `1.9.0`).**
Rebuild them when Media3 is updated. Media3 1.9.0 requires NDK `27.0.12077973`, FFmpeg branch `release/6.0`.

## Dolby Vision conversion (`lib-dovi-release.aar`)

### English

The AAR holds `libdovi_bridge.so`: the JNI bridge used by `DoviBridge` and the static
[libdovi](https://github.com/quietvoid/dovi_tool/tree/main/dolby_vision) built from the official
`quietvoid/dovi_tool` repository (tag `libdovi-3.3.2`, set in `dovi/build.gradle.kts`).
The `:dovi` module is not part of the normal build: the app, the tests and CI use the AAR from this folder and need
neither NDK nor Rust. Without the AAR the app still builds; the "P7 → P8.1" option is then hidden.

Requirements: Android SDK with NDK `27.0.12077973` and CMake `3.22.1` (Android Studio → SDK Manager → SDK Tools),
Rust via [rustup](https://rustup.rs), `git`, `bash` (Linux, macOS or WSL).

```bash
./gradlew -Pdovi=true -PdoviAbis=arm64-v8a,armeabi-v7a,x86_64,x86 :dovi:assembleRelease
```

The task clones `dovi_tool` at the tag into `dovi/build/`, builds `libdovi.a` for each ABI with
`cargo rustc --crate-type staticlib --features capi` (`dovi/build_libdovi.sh`, the Rust targets are added with
`rustup target add`), builds the bridge with CMake and copies the result to `app/libs/lib-dovi-release.aar`.
Commit the AAR. ABIs default to `arm64-v8a,armeabi-v7a`; for an emulator add x86:
`-PdoviAbis=arm64-v8a,armeabi-v7a,x86_64,x86`.

When updating libdovi, change the tag in `dovi/build.gradle.kts` and regenerate
`dovi/src/main/cpp/include/libdovi/rpu_parser.h` for that tag (`cargo install cargo-c`, then
`cargo cbuild --release --features capi` in `dovi_tool/dolby_vision`, the header is under `target/`).

Licenses: libdovi is MIT (© quietvoid); the bridge and `DoviExtractorWrapper` / `DoviConvertingTrackOutput` /
`BufferedTransformingTrackOutput` are ported from [edde746/plezy](https://github.com/edde746/plezy) (GPL-3.0).

### Русский

AAR содержит `libdovi_bridge.so`: JNI-мост для `DoviBridge` и статическую
[libdovi](https://github.com/quietvoid/dovi_tool/tree/main/dolby_vision), собранную из официального репозитория
`quietvoid/dovi_tool` (тег `libdovi-3.3.2`, задан в `dovi/build.gradle.kts`).
Модуль `:dovi` не входит в обычную сборку: приложение, тесты и CI берут готовый AAR из этой папки, NDK и Rust им не
нужны. Без AAR приложение тоже собирается, просто пункт «P7 → P8.1» в настройках скрыт.

Нужно: Android SDK с NDK `27.0.12077973` и CMake `3.22.1` (Android Studio → SDK Manager → SDK Tools),
Rust через [rustup](https://rustup.rs), `git`, `bash` (Linux, macOS или WSL).

```bash
./gradlew -Pdovi=true -PdoviAbis=arm64-v8a,armeabi-v7a,x86_64,x86 :dovi:assembleRelease
```

Задача клонирует `dovi_tool` нужного тега в `dovi/build/`, собирает `libdovi.a` под каждый ABI командой
`cargo rustc --crate-type staticlib --features capi` (`dovi/build_libdovi.sh`, Rust-таргеты добавляются через
`rustup target add`), собирает мост через CMake и копирует результат в `app/libs/lib-dovi-release.aar`.
AAR нужно закоммитить. По умолчанию ABI `arm64-v8a,armeabi-v7a`; для эмулятора добавьте x86:
`-PdoviAbis=arm64-v8a,armeabi-v7a,x86_64,x86`.

При обновлении libdovi поменяйте тег в `dovi/build.gradle.kts` и перегенерируйте
`dovi/src/main/cpp/include/libdovi/rpu_parser.h` для этого тега (`cargo install cargo-c`, затем
`cargo cbuild --release --features capi` в `dovi_tool/dolby_vision`, заголовок появится в `target/`).

Лицензии: libdovi — MIT (© quietvoid); мост и `DoviExtractorWrapper` / `DoviConvertingTrackOutput` /
`BufferedTransformingTrackOutput` перенесены из [edde746/plezy](https://github.com/edde746/plezy) (GPL-3.0).

## Media3 decoders

### English version

The binary ffmpeg extension was built with the following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

Complete build instructions:
 - [decoder ffmpeg](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md)
 - [decoder AV1](https://github.com/androidx/media/blob/release/libraries/decoder_av1/README.md)


To assemble ``.aar``:

```
./gradlew :lib-decoder-ffmpeg:assembleRelease
./gradlew :lib-decoder-av1:assembleRelease
```

### AV1: 10-bit (`av1/`)

`libdav1dJNI.so` inside `lib-decoder-av1-release.aar` is built from `av1/jni`: the Media3 1.9.0 JNI wrapper with
10-bit output ported from `androidx/media` main (P010 with a BT.2020 PQ/HLG dataspace, 8-bit YV12 fallback when
the window does not accept P010). JNI signatures are unchanged, so the AAR's Java classes stay Media3 1.9.0.
Rebuild: GitHub Actions → "Build AV1 decoder" (commits the AAR to the branch it runs on), or locally
`./av1/build_av1.sh <ndk path>` (needs cmake, meson, ninja, nasm).

`libdav1dJNI.so` в `lib-decoder-av1-release.aar` собирается из `av1/jni`: JNI-обертка Media3 1.9.0 с выводом
10-bit из ветки main `androidx/media` (P010 с dataspace BT.2020 PQ/HLG, откат на 8-bit YV12, если окно не принимает P010).
Сигнатуры JNI не менялись, Java-классы AAR остаются от Media3 1.9.0. Пересборка: GitHub Actions → «Build AV1 decoder»
(коммитит AAR в ветку, с которой запущен) или локально `./av1/build_av1.sh <путь к NDK>` (нужны cmake, meson, ninja, nasm).

### Russian version

Если у вас Linux, это идеально подходит для сборки SW декодеров Media3 в AAR.

Это пошаговая инструкция на русском языке (подойдёт для Debian/Mint/Ubuntu).

#### Шаг 0: Подготовка окружения

Вам понадобятся:
1.  **Git** (для скачивания исходников).
2.  **Android NDK** (для компиляции C++ кода).
3.  **CMake** и **Ninja** (системы сборки).
4.  **Nasm** (для сборки dav1d/AV1).
5.  **Meson** (для сборки dav1d).

Откройте терминал и установите необходимые пакеты:

```bash
sudo apt update
sudo apt install git cmake ninja-build nasm python3-pip
pip3 install meson
```
Проверьте:
```bash
meson --version
```

Должно вывести версию (например, 1.10.1). Если выдало ошибку, 
выполните в терминале: `export PATH="$HOME/.local/bin:$PATH"` и проверьте снова.
Если `meson` все равно не находится, попробуйте установить его системно через apt (но там может быть старая версия):
`sudo apt install meson`. Но лучше использовать pip-версию с исправленным PATH.

#### Шаг 1: Скачивание исходного кода Media3

Нам нужно скачать исходники `androidx.media3` версии `1.9.0`, которую мы используем в проекте.

1.  Создайте рабочую папку:
    ```bash
    mkdir media3-build
    cd media3-build
    ```

2.  Клонируйте репозиторий Media3:
    ```bash
    git clone https://github.com/androidx/media.git
    cd media
    ```

3.  Переключитесь на тег нужной версии (например, `1.9.0`):
    ```bash
    git checkout 1.9.0
    ```

#### Шаг 2: Настройка Android NDK

1.  Скачайте NDK (**Требуется** `27.0.12077973` - это прописано в `android.ndkVersion` внутри Media3).
    Лучший вариант установить NDK 27 (он будет лежать, например, в `~/Android/Sdk/ndk/27.0.12077973`):
     *  Откройте Android Studio -> SDK Manager -> SDK Tools.
     *  Поставьте галочку "Show Package Details".
     *  Найдите NDK (Side by side) и выберите версию **27.0.12077973**.
     *  Нажмите Apply.

2.  Задайте переменную окружения `NDK_PATH`:
    ```bash
    NDK_PATH="$HOME/Android/Sdk/ndk/27.0.12077973"
    # Проверьте путь!
    ```

3.  Задайте платформу хоста:
    ```bash
    HOST_PLATFORM="linux-x86_64"
    ```

4.  Установите версию ABI для нативного кода (она равна minSdk и не должна превышать его):
    ```bash
    ANDROID_ABI=21
    ```

5.  Установите следующую переменную оболочки:
    ```bash
    FFMPEG_MODULE_PATH="$(pwd)/libraries/decoder_ffmpeg/src/main"
    ```

#### Шаг 3: Сборка FFmpeg (Аудио декодеры)

1.  Перейдите в папку модуля FFmpeg:
    ```bash
    cd "${FFMPEG_MODULE_PATH}/jni"
    ```

2.  Скачайте исходники FFmpeg (ветка `release/6.0`!!!):
    ```bash
    git clone git://source.ffmpeg.org/ffmpeg
    cd ffmpeg
    git checkout release/6.0
    cd ..
    ```

3.  Настройте список аудио кодеков:
    ```bash
    ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
    ```
    *Примечание: `dca` — это DTS.*

4.  Запустите скрипт сборки (это займет время):
    ```bash
    # Мы в libraries/decoder_ffmpeg/src/main/jni
    # cd "${FFMPEG_MODULE_PATH}/jni"
    
    ./build_ffmpeg.sh \
      "$(pwd)/.." \
      "${NDK_PATH}" \
      "${HOST_PLATFORM}" \
      ${ANDROID_ABI} \
      "${ENABLED_DECODERS[@]}"
    ```
    *(Здесь `${ANDROID_ABI}` = `21` — это минимальный API Level, `$(pwd)/..` — путь к модулю).*

    Если скрипт отработает успешно, в папке `ffmpeg` появятся папка `android-libs`.

#### Шаг 4: Сборка AV1 (dav1d)

1.  Вернитесь в корень репозитория `media`:
    ```bash
    cd ../../../../../
    ```

2.  Перейдите в папку модуля AV1:
    ```bash
    cd libraries/decoder_av1/src/main/jni
    ```

3.  Скачайте `cpu_features`:
    ```bash
    git clone https://github.com/google/cpu_features
    ```

4.  Скачайте `dav1d`:
    ```bash
    git clone https://code.videolan.org/videolan/dav1d.git
    ```

5.  Запустите скрипт сборки:
    ```bash
    ./build_dav1d.sh \
      "$(pwd)/.." \
      "${NDK_PATH}" \
      "${HOST_PLATFORM}"
    ```

    Если скрипт отработает успешно, в этой же папке появятся папка `nativelib`.

#### Шаг 5: Сборка AAR файлов

Теперь, когда нативные библиотеки (`.so` / `.a`) собраны, нужно упаковать их в Android Archive (`.aar`) с помощью Gradle.

1.  Вернитесь в самый корень проекта `media`:
    ```bash
    cd ../../../../../
    ```

2.  Создайте файл `local.properties` и укажите путь к SDK и NDK (если их нет в переменных среды):
    ```properties
    sdk.dir=/home/ваш_юзер/Android/Sdk
    ndk.dir=/home/ваш_юзер/Android/Sdk/ndk/27.0.12077973
    ```

3.  Запустите сборку AAR для FFmpeg:
    ```bash
    ./gradlew :lib-decoder-ffmpeg:assembleRelease
    ```

4.  Запустите сборку AAR для AV1:
    ```bash
    ./gradlew :lib-decoder-av1:assembleRelease
    ```

#### Шаг 6: Копирование в проект

После успешной сборки файлы `.aar` будут лежать в:
*   `libraries/decoder_ffmpeg/buildout/outputs/aar/`
*   `libraries/decoder_av1/buildout/outputs/aar/`

1.  Скопируйте их в папку `libs` проекта `DDD Video Player`.
2.  Переименуйте их для удобства (например, `lib-decoder-ffmpeg-release.aar`).

#### Шаг 7: Обновление проекта

1.  В `libs.versions.toml` установите версию `media3` точно такую же, какую вы скомпилили (например, `1.9.0`).
2.  В `build.gradle.kts` убедитесь, что подключены AAR и исключены конфликтующие модули (если они есть).

Помните что версия из которой сделали AAR и модулей `media3` должны совпадать!