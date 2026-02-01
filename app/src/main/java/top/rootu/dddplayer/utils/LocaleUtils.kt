package top.rootu.dddplayer.utils

import android.content.Context
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import java.util.Locale

object LocaleUtils {

    /**
     * Возвращает отформатированное, читаемое имя для кода языка.
     * Обрабатывает специальные коды, такие как "system", "device", "und".
     * Для обычных кодов генерирует строку формата "NativeName (EnglishName)", например, "Русский (Russian)".
     *
     * @param langCode Код языка (ISO 639-1) или специальный код.
     * @param context Контекст для доступа к строковым ресурсам.
     * @return Отформатированная строка.
     */
    fun getFormattedLanguageName(langCode: String, context: Context): String {
        return when (langCode) {
            SettingsRepository.LANG_SYSTEM_DEFAULT -> context.getString(R.string.pref_language_system)
            SettingsRepository.TRACK_DEVICE -> context.getString(R.string.pref_language_track_device)
            SettingsRepository.TRACK_DEFAULT -> context.getString(R.string.pref_language_track_default)
            "und" -> context.getString(R.string.track_unknown) // "undetermined"
            "" -> context.getString(R.string.pref_language_track_default) // Пустая строка тоже означает "по умолчанию"
            else -> {
                val locale = Locale.forLanguageTag(langCode)

                // Получаем "родное" название, например "Русский"
                val nativeName = locale.getDisplayName(locale).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                }

                // Получаем английское название, например "Russian"
                val englishName = locale.getDisplayName(Locale.ENGLISH).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString()
                }

                // Если названия совпадают (например, для "English"), показываем только одно
                if (nativeName.equals(englishName, ignoreCase = true)) {
                    nativeName
                } else {
                    // Формируем строку "Русский (Russian)"
                    "$nativeName ($englishName)"
                }
            }
        }
    }
}