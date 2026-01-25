## English version

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

## Russian version

Если у вас Linux, это идеально подходит для сборки SW декодеров Media3 в AAR.

Это пошаговая инструкция на русском языке (подойдёт для Debian/Mint/Ubuntu).

### Шаг 0: Подготовка окружения

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

### Шаг 1: Скачивание исходного кода Media3

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

### Шаг 2: Настройка Android NDK

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

### Шаг 3: Сборка FFmpeg (Аудио декодеры)

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

### Шаг 4: Сборка AV1 (dav1d)

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

### Шаг 5: Сборка AAR файлов

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

### Шаг 6: Копирование в проект

После успешной сборки файлы `.aar` будут лежать в:
*   `libraries/decoder_ffmpeg/buildout/outputs/aar/`
*   `libraries/decoder_av1/buildout/outputs/aar/`

1.  Скопируйте их в папку `libs` проекта `DDD Video Player`.
2.  Переименуйте их для удобства (например, `lib-decoder-ffmpeg-release.aar`).

### Шаг 7: Обновление проекта

1.  В `libs.versions.toml` установите версию `media3` точно такую же, какую вы скомпилили (например, `1.9.0`).
2.  В `build.gradle.kts` убедитесь, что подключены AAR и исключены конфликтующие модули (если они есть).

Помните что версия из которой сделали AAR и модулей `media3` должны совпадать!