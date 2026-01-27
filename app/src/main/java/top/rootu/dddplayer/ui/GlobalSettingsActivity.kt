package top.rootu.dddplayer.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.exoplayer.DefaultRenderersFactory
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AudioMixerLogic
import java.util.Locale

class GlobalSettingsActivity : AppCompatActivity() {

    private lateinit var repo: SettingsRepository

    // UI Elements
    private lateinit var btnClose: ImageButton
    private lateinit var itemExit: LinearLayout

    private lateinit var itemDecoder: LinearLayout
    private lateinit var textDecoderDesc: TextView
    private lateinit var textDecoderValue: TextView

    private lateinit var itemTunneling: LinearLayout
    private lateinit var switchTunneling: Switch

    private lateinit var itemDv7: LinearLayout
    private lateinit var switchDv7: Switch

    private lateinit var itemAfr: LinearLayout
    private lateinit var switchAfr: Switch
    private lateinit var textAfrDesc: TextView

    private lateinit var itemSkipSilence: LinearLayout
    private lateinit var switchSkipSilence: Switch
    private lateinit var textSkipSilenceDesc: TextView

    private lateinit var itemBoost: LinearLayout
    private lateinit var textBoostValue: TextView

    private lateinit var itemAudioLang: LinearLayout
    private lateinit var textAudioLangValue: TextView

    private lateinit var itemSubLang: LinearLayout
    private lateinit var textSubLangValue: TextView

    private lateinit var itemCalibrateVr: LinearLayout
    private lateinit var itemCalibrateAnaglyph: LinearLayout

    private lateinit var itemDownmix: LinearLayout
    private lateinit var switchDownmix: Switch
    private lateinit var itemDownmixConfig: LinearLayout

    // Список популярных языков для перебора (ISO 639-1)
    private val languages = listOf(
        SettingsRepository.TRACK_DEFAULT,
        SettingsRepository.TRACK_DEVICE,
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

        itemDv7 = findViewById(R.id.item_dv7)
        switchDv7 = findViewById(R.id.switch_dv7)

        itemAfr = findViewById(R.id.item_afr)
        switchAfr = findViewById(R.id.switch_afr)
        textAfrDesc = findViewById(R.id.text_afr_desc)

        itemSkipSilence = findViewById(R.id.item_skip_silence)
        switchSkipSilence = findViewById(R.id.switch_skip_silence)
        textSkipSilenceDesc = findViewById(R.id.text_skip_silence_desc)

        itemBoost = findViewById(R.id.item_boost)
        textBoostValue = findViewById(R.id.text_boost_value)

        itemAudioLang = findViewById(R.id.item_audio_lang)
        textAudioLangValue = findViewById(R.id.text_audio_lang_value)

        itemSubLang = findViewById(R.id.item_sub_lang)
        textSubLangValue = findViewById(R.id.text_sub_lang_value)

        itemCalibrateVr = findViewById(R.id.item_calibrate_vr)
        itemCalibrateAnaglyph = findViewById(R.id.item_calibrate_anaglyph)

        itemDownmix = findViewById(R.id.item_downmix)
        switchDownmix = findViewById(R.id.switch_downmix)
        itemDownmixConfig = findViewById(R.id.item_downmix_config)
    }

    private fun setupLogic() {
        btnClose.setOnClickListener { finish() }
        itemExit.setOnClickListener { finish() }

        // Decoder
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
        itemDecoder.requestFocus()

        // Downmix
        fun updateDownmixUI() {
            val enabled = repo.isStereoDownmixEnabled()
            switchDownmix.isChecked = enabled
            itemDownmixConfig.visibility = if (enabled) View.VISIBLE else View.GONE
        }

        updateDownmixUI()

        itemDownmix.setOnClickListener {
            val newState = !switchDownmix.isChecked
            repo.setStereoDownmixEnabled(newState)
            updateDownmixUI()
        }

        itemDownmixConfig.setOnClickListener {
            showAudioMixDialog()
        }

        // Tunneling
        switchTunneling.isChecked = repo.isTunnelingEnabled()
        itemTunneling.setOnClickListener {
            val newState = !switchTunneling.isChecked
            switchTunneling.isChecked = newState
            repo.setTunnelingEnabled(newState)
        }

        // DV7
        switchDv7.isChecked = repo.isMapDv7ToHevcEnabled()
        itemDv7.setOnClickListener {
            val newState = !switchDv7.isChecked
            switchDv7.isChecked = newState
            repo.setMapDv7ToHevcEnabled(newState)
        }

        // AFR
        switchAfr.isChecked = repo.isFrameRateMatchingEnabled()
        updateAfrDesc(switchAfr.isChecked)
        itemAfr.setOnClickListener {
            val newState = !switchAfr.isChecked
            switchAfr.isChecked = newState
            repo.setFrameRateMatchingEnabled(newState)
            updateAfrDesc(newState)
        }

        // Skip Silence
        switchSkipSilence.isChecked = repo.isSkipSilenceEnabled()
        updateSkipSilenceDesc(switchSkipSilence.isChecked)
        itemSkipSilence.setOnClickListener {
            val newState = !switchSkipSilence.isChecked
            switchSkipSilence.isChecked = newState
            repo.setSkipSilenceEnabled(newState)
            updateSkipSilenceDesc(newState)
        }

        // Boost
        updateBoostUI()
        itemBoost.setOnClickListener {
            val current = repo.getLoudnessBoost()
            val next = if (current >= 1000) 0 else current + 200
            repo.setLoudnessBoost(next)
            updateBoostUI()
        }

        // Languages
        updateLangUI(textAudioLangValue, repo.getPreferredAudioLang())
        itemAudioLang.setOnClickListener {
            val current = repo.getPreferredAudioLang()
            val next = getNextLanguage(current)
            repo.setPreferredAudioLang(next)
            updateLangUI(textAudioLangValue, next)
        }

        updateLangUI(textSubLangValue, repo.getPreferredSubLang())
        itemSubLang.setOnClickListener {
            val current = repo.getPreferredSubLang()
            val next = getNextLanguage(current)
            repo.setPreferredSubLang(next)
            updateLangUI(textSubLangValue, next)
        }

        // Calibration
        itemCalibrateVr.setOnClickListener {
            Toast.makeText(this, "Мастер настройки VR: Скоро будет", Toast.LENGTH_SHORT).show()
        }

        itemCalibrateAnaglyph.setOnClickListener {
            Toast.makeText(this, "Мастер настройки Анаглифа: Скоро будет", Toast.LENGTH_SHORT).show()
        }

        var crashCounter = 0
        findViewById<TextView>(R.id.settings_header).setOnClickListener {
            crashCounter++
            if (crashCounter >= 5) {
                throw RuntimeException("Test Crash: This is a simulated exception!")
            }
        }
    }

    private fun showAudioMixDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_audio_mix)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        // --- UI Elements ---
        val spinner = dialog.findViewById<Spinner>(R.id.spinner_preset)
        val slidersContainer = dialog.findViewById<LinearLayout>(R.id.sliders_container)

        val seekFront = dialog.findViewById<SeekBar>(R.id.seek_front)
        val valFront = dialog.findViewById<TextView>(R.id.val_front)
        val seekCenter = dialog.findViewById<SeekBar>(R.id.seek_center)
        val valCenter = dialog.findViewById<TextView>(R.id.val_center)
        val seekRear = dialog.findViewById<SeekBar>(R.id.seek_rear)
        val valRear = dialog.findViewById<TextView>(R.id.val_rear)
        val seekMiddle = dialog.findViewById<SeekBar>(R.id.seek_middle)
        val valMiddle = dialog.findViewById<TextView>(R.id.val_middle)
        val seekLfe = dialog.findViewById<SeekBar>(R.id.seek_lfe)
        val valLfe = dialog.findViewById<TextView>(R.id.val_lfe)

        val btnReset = dialog.findViewById<Button>(R.id.btn_reset)
        val btnClose = dialog.findViewById<Button>(R.id.btn_close)

        // --- Helpers ---

        fun updateText(view: TextView, progress: Int) {
            view.text = "${progress}%"
        }

        var isUpdatingUiFromCode = false

        fun updateSeekBarsWithoutTriggering(params: AudioMixerLogic.MixParams) {
            isUpdatingUiFromCode = true
            seekFront.progress = (params.front * 100).toInt()
            updateText(valFront, seekFront.progress)

            seekCenter.progress = (params.center * 100).toInt()
            updateText(valCenter, seekCenter.progress)

            seekRear.progress = (params.rear * 100).toInt()
            updateText(valRear, seekRear.progress)

            seekMiddle.progress = (params.middle * 100).toInt()
            updateText(valMiddle, seekMiddle.progress)

            seekLfe.progress = (params.lfe * 100).toInt()
            updateText(valLfe, seekLfe.progress)
            isUpdatingUiFromCode = false
        }

        fun updateSlidersState(preset: AudioMixerLogic.MixPreset) {
            val isCustom = preset == AudioMixerLogic.MixPreset.CUSTOM

            slidersContainer.alpha = if (isCustom) 1.0f else 0.5f
            seekFront.isEnabled = isCustom
            seekCenter.isEnabled = isCustom
            seekRear.isEnabled = isCustom
            seekMiddle.isEnabled = isCustom
            seekLfe.isEnabled = isCustom
            btnReset.isEnabled = isCustom

            val params = AudioMixerLogic.getParamsForPreset(preset, repo)
            updateSeekBarsWithoutTriggering(params)
        }

        fun initSeek(seekBar: SeekBar, textView: TextView, setter: (Float) -> Unit) {
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateText(textView, progress)
                    if (fromUser && !isUpdatingUiFromCode && repo.getMixPreset() == AudioMixerLogic.MixPreset.CUSTOM.id) {
                        setter(progress / 100f)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        // --- Init Logic ---

        val presets = AudioMixerLogic.MixPreset.values()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presets.map { it.title })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val currentPresetId = repo.getMixPreset()
        val currentPresetIndex = presets.indexOfFirst { it.id == currentPresetId }.coerceAtLeast(0)
        spinner.setSelection(currentPresetIndex, false) // false - не вызывать onItemSelected при инициализации

        initSeek(seekFront, valFront) { repo.setMixFront(it) }
        initSeek(seekCenter, valCenter) { repo.setMixCenter(it) }
        initSeek(seekRear, valRear) { repo.setMixRear(it) }
        initSeek(seekMiddle, valMiddle) { repo.setMixMiddle(it) }
        initSeek(seekLfe, valLfe) { repo.setMixLfe(it) }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = presets[position]
                if (repo.getMixPreset() != selected.id) {
                    repo.setMixPreset(selected.id)
                }
                updateSlidersState(selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateSlidersState(presets[currentPresetIndex])

        btnReset.setOnClickListener {
            if (repo.getMixPreset() == AudioMixerLogic.MixPreset.CUSTOM.id) {
                repo.setMixFront(1.0f)
                repo.setMixCenter(1.0f)
                repo.setMixRear(1.0f)
                repo.setMixMiddle(1.0f)
                repo.setMixLfe(0.0f)
                updateSeekBarsWithoutTriggering(AudioMixerLogic.getParamsForPreset(AudioMixerLogic.MixPreset.CUSTOM, repo))
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        spinner.requestFocus()
    }

    private fun updateDecoderUI() {
        val mode = repo.getDecoderPriority()
        when (mode) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> {
                textDecoderValue.text = "HW"
                textDecoderDesc.setText(R.string.pref_decoder_priority_only_device)
            }
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> {
                textDecoderValue.text = "HW+"
                textDecoderDesc.setText(R.string.pref_decoder_priority_prefer_device)
            }
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> {
                textDecoderValue.text = "SW"
                textDecoderDesc.setText(R.string.pref_decoder_priority_prefer_app)
            }
        }
    }

    private fun updateAfrDesc(enabled: Boolean) {
        textAfrDesc.setText(if (enabled) R.string.pref_framerate_matching_on else R.string.pref_framerate_matching_off)
    }

    private fun updateSkipSilenceDesc(enabled: Boolean) {
        textSkipSilenceDesc.setText(if (enabled) R.string.pref_skip_silence_on else R.string.pref_skip_silence_off)
    }

    private fun updateBoostUI() {
        val boost = repo.getLoudnessBoost()
        if (boost == 0) {
            textBoostValue.text = "Выкл"
        } else {
            textBoostValue.text = "+${boost / 100} dB"
        }
    }

    private fun getNextLanguage(current: String): String {
        val idx = languages.indexOf(current)
        val nextIdx = (idx + 1) % languages.size
        return languages[nextIdx]
    }

    private fun updateLangUI(textView: TextView, langCode: String) {
        when (langCode) {
            SettingsRepository.TRACK_DEVICE -> textView.text = getString(R.string.pref_language_track_device)
            SettingsRepository.TRACK_DEFAULT -> textView.text = getString(R.string.pref_language_track_default)
            else -> {
                val loc = Locale(langCode)
                val name = loc.displayLanguage.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
                textView.text = "$name ($langCode)"
            }
        }
    }
}