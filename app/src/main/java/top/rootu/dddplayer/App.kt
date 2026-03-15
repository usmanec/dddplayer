package top.rootu.dddplayer

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.utils.CrashHandler
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {
    lateinit var okHttpClient: OkHttpClient
        private set

    companion object {
        // Изменяем const val на lateinit var, чтобы инициализировать при запуске
        lateinit var USER_AGENT: String
            private set

        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Формируем User-Agent в onCreate, когда у нас есть доступ к Build.*
        USER_AGENT = "DDDPlayer/${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE} (${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT})"

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val builder = originalRequest.newBuilder()
                if (originalRequest.header("User-Agent") == null) {
                    // Используем уже сформированную строку
                    builder.header("User-Agent", USER_AGENT)
                }
                chain.proceed(builder.build())
            }
            .build()

        CrashHandler.init(this)
        applyAppLanguage()
    }

    private fun applyAppLanguage() {
        val repository = SettingsRepository.getInstance(this)
        val langCode = repository.getAppLanguage()

        val appLocale = if (langCode == SettingsRepository.LANG_SYSTEM_DEFAULT) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(langCode)
        }

        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    /**
     * Оптимизированная настройка Coil для видеоплеера.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            // Memory Cache - снижаем до 3%, чтобы не мешать плееру
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.03)
                    .strongReferencesEnabled(true)
                    .build()
            }
            // Disk Cache - 100 МБ на диске (не в RAM)
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB
                    .build()
            }
            // Оптимизация ресурсов
            .allowHardware(true) // Использовать аппаратное ускорение для отрисовки Bitmap
            .crossfade(true)
            .build()
    }

    // Дополнительно: очистка памяти при критических ситуациях
    override fun onLowMemory() {
        super.onLowMemory()
        // Coil сам умеет чистить кэш при нехватке памяти,
        // но здесь можно добавить принудительную очистку других ресурсов, если потребуется.
    }
}