import java.io.ByteArrayOutputStream

// Функция для безопасного выполнения команд через Providers API (совместимо с Gradle 8+)
fun getGitOutput(vararg command: String): String {
    return try {
        providers.exec {
            commandLine(*command)
            isIgnoreExitValue = true // Не ломать сборку, если git вернет ошибку (например, нет тегов)
        }.standardOutput.asText.get().trim()
    } catch (e: Exception) {
        ""
    }
}

val gitVersionCode: Int by lazy {
    // Используем HEAD, так как origin/main может быть не обновлен локально
    val output = getGitOutput("git", "rev-list", "--count", "HEAD")
    output.toIntOrNull() ?: 1
}

val gitVersionName: String by lazy {
    val output = getGitOutput("git", "describe", "--tags", "--dirty")

    // Если тегов нет (ошибка fatal или пусто), берем короткий хеш коммита
    if (output.isEmpty() || output.contains("fatal") || output.contains("не найдены")) {
        val commitHash = getGitOutput("git", "rev-parse", "--short", "HEAD")
        // Возвращаем временную версию, пока вы не создадите тег
        if (commitHash.isNotEmpty()) "0.0.1-dev-$commitHash" else "0.0.1"
    } else {
        output.replaceFirst("^v".toRegex(), "")
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "top.rootu.dddplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.rootu.dddplayer"
        minSdk = 23
        targetSdk = 34
        versionCode = gitVersionCode
        versionName = gitVersionName
        // Выводим в консоль при сборке
        println("Building Version: $versionName ($versionCode)")

        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.okhttp)

    // Media3 dependencies
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.leanback)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.decoder)
    // Добавляем поддержку стриминговых протоколов
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    // ffmpeg и av1
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.ui.graphics)
    ksp(libs.androidx.room.compiler)

    // Для загрузки изображений
    implementation(libs.coil)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
