package top.rootu.dddplayer.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.exoplayer.DefaultRenderersFactory
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import java.util.Locale

class GlobalSettingsActivity : AppCompatActivity() {

    private lateinit var repo: SettingsRepository

    // UI Elements
    private lateinit var btnClose: android.widget.ImageButton // Кнопка крестик
    private lateinit var itemExit: LinearLayout // Пункт выход
    private lateinit var itemDecoder: LinearLayout
    private lateinit var textDecoderDesc: TextView
    private lateinit var textDecoderValue: TextView

    private lateinit var itemTunneling: LinearLayout
    private lateinit var switchTunneling: Switch

    private lateinit var itemPassthrough: LinearLayout
    private lateinit var switchPassthrough: Switch

    private lateinit var itemAudioLang: LinearLayout
    private lateinit var textAudioLangValue: TextView

    private lateinit var itemSubLang: LinearLayout
    private lateinit var textSubLangValue: TextView

    private lateinit var itemCalibrateVr: LinearLayout
    private lateinit var itemCalibrateAnaglyph: LinearLayout

    // Список популярных языков для перебора (ISO 639-1)
    // "" = System Default
    private val languages = listOf(
        "",     // Как в системе
        "en",   // English
        "ru",   // Русский
        "uk",   // Українська
        "be",   // Беларуская
        "kk",   // Қазақ тілі
        "uz",   // Oʻzbek
        "az",   // Azərbaycan
        "hy",   // Հայերեն (Армянский)
        "ka",   // ქართული (Грузинский)
        "ky",   // Кыргызча
        "tg",   // Тоҷикӣ
        "tk",   // Türkmen
        "ro",   // Română (Молдова)
        // Остальной мир
        "de", "fr", "es", "it", "ja", "ko", "zh"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_settings)
        repo = SettingsRepository(this)

        bindViews()
        setupLogic()
    }

    private fun bindViews() {
        btnClose = findViewById(R.id.btn_close)
        itemExit = findViewById(R.id.item_exit)

        itemDecoder = findViewById(R.id.item_decoder)
        textDecoderDesc = findViewById(R.id.text_decoder_desc)
        textDecoderValue = findViewById(R.id.text_decoder_value)

        itemTunneling = findViewById(R.id.item_tunneling)
        switchTunneling = findViewById(R.id.switch_tunneling)

        itemPassthrough = findViewById(R.id.item_passthrough)
        switchPassthrough = findViewById(R.id.switch_passthrough)

        itemAudioLang = findViewById(R.id.item_audio_lang)
        textAudioLangValue = findViewById(R.id.text_audio_lang_value)

        itemSubLang = findViewById(R.id.item_sub_lang)
        textSubLangValue = findViewById(R.id.text_sub_lang_value)

        itemCalibrateVr = findViewById(R.id.item_calibrate_vr)
        itemCalibrateAnaglyph = findViewById(R.id.item_calibrate_anaglyph)
    }

    private fun setupLogic() {
        // 1. Decoder Priority
        updateDecoderUI()
        itemDecoder.setOnClickListener {
            val current = repo.getDecoderPriority()
            val next = when (current) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
            repo.setDecoderPriority(next)
            updateDecoderUI()
        }

        // 2. Tunneling
        switchTunneling.isChecked = repo.isTunnelingEnabled()
        itemTunneling.setOnClickListener {
            val newState = !switchTunneling.isChecked
            switchTunneling.isChecked = newState
            repo.setTunnelingEnabled(newState)
        }

        // 3. Passthrough
        switchPassthrough.isChecked = repo.isAudioPassthroughEnabled()
        itemPassthrough.setOnClickListener {
            val newState = !switchPassthrough.isChecked
            switchPassthrough.isChecked = newState
            repo.setAudioPassthroughEnabled(newState)
        }

        // 4. Audio Language
        updateLangUI(textAudioLangValue, repo.getPreferredAudioLang())
        itemAudioLang.setOnClickListener {
            val current = repo.getPreferredAudioLang()
            val next = getNextLanguage(current)
            repo.setPreferredAudioLang(next)
            updateLangUI(textAudioLangValue, next)
        }

        // 5. Subtitle Language
        updateLangUI(textSubLangValue, repo.getPreferredSubLang())
        itemSubLang.setOnClickListener {
            val current = repo.getPreferredSubLang()
            val next = getNextLanguage(current)
            repo.setPreferredSubLang(next)
            updateLangUI(textSubLangValue, next)
        }

        // 6. Calibration Stubs
        itemCalibrateVr.setOnClickListener {
            Toast.makeText(this, "Мастер настройки VR: Скоро будет", Toast.LENGTH_SHORT).show()
        }

        itemCalibrateAnaglyph.setOnClickListener {
            Toast.makeText(this, "Мастер настройки Анаглифа: Скоро будет", Toast.LENGTH_SHORT).show()
        }

        // 7. Exit Actions
        btnClose.setOnClickListener {
            finish()
        }

        itemExit.setOnClickListener {
            finish()
        }
    }

    private fun updateDecoderUI() {
        val mode = repo.getDecoderPriority()
        when (mode) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> {
                textDecoderValue.text = "Только HW"
                textDecoderDesc.text = "Только аппаратные декодеры\n(Макс. производительность)"
            }
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> {
                textDecoderValue.text = "HW + SW"
                textDecoderDesc.text = "Аппаратные, программные при ошибке\n(Рекомендуется)"
            }
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> {
                textDecoderValue.text = "SW (FFmpeg)"
                textDecoderDesc.text = "Предпочитать программные\n(Исправляет проблемы воспроизведения аудио, нагружает CPU)"
            }
        }
    }

    private fun getNextLanguage(current: String): String {
        val idx = languages.indexOf(current)
        val nextIdx = (idx + 1) % languages.size
        return languages[nextIdx]
    }

    private fun updateLangUI(textView: TextView, langCode: String) {
        if (langCode.isEmpty()) {
            textView.text = "Как в системе"
        } else {
            val loc = Locale(langCode)
            val name = loc.displayLanguage.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            textView.text = "$name ($langCode)"
        }
    }
}