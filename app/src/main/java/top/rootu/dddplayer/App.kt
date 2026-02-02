package top.rootu.dddplayer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.utils.CrashHandler
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {
    companion object {
        const val USER_AGENT = "DDDPlayer/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        instance = this
        CrashHandler.init(this)
        applyAppLanguage()
        super.onCreate()
    }

    private fun applyAppLanguage() {
        val repository = SettingsRepository(this)
        val langCode = repository.getAppLanguage()

        val appLocale = if (langCode == SettingsRepository.LANG_SYSTEM_DEFAULT) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(langCode)
        }

        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    // Разрешаем редиректы
                    .followRedirects(true)
                    .followSslRedirects(true)
                    // Увеличиваем таймауты для медленных серверов
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .build()
                        chain.proceed(request)
                     }
                     .build()
            }
            .crossfade(true)
            .build()
    }
}