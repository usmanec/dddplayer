package top.rootu.dddplayer.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.os.LocaleListCompat
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AudioMixerLogic
import top.rootu.dddplayer.logic.UpdateInfo
import top.rootu.dddplayer.ui.adapter.ChoiceAdapter
import top.rootu.dddplayer.utils.LocaleUtils
import top.rootu.dddplayer.viewmodel.UpdateViewModel

class GlobalSettingsActivity : AppCompatActivity() {

    private val updateViewModel: UpdateViewModel by viewModels()
    private var updateDialog: Dialog? = null
    private lateinit var repo: SettingsRepository

    // UI Elements
    private lateinit var btnClose: ImageButton
    private lateinit var itemExit: LinearLayout

    private lateinit var itemDecoder: LinearLayout
    private lateinit var textDecoderDesc: TextView
    private lateinit var textDecoderValue: TextView

    private lateinit var itemTunneling: LinearLayout
    private lateinit var switchTunneling: SwitchCompat

    private lateinit var itemDv7: LinearLayout
    private lateinit var switchDv7: SwitchCompat

    private lateinit var itemAfr: LinearLayout
    private lateinit var switchAfr: SwitchCompat
    private lateinit var textAfrDesc: TextView

    private lateinit var itemSkipSilence: LinearLayout
    private lateinit var switchSkipSilence: SwitchCompat
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
    private lateinit var switchDownmix: SwitchCompat
    private lateinit var itemDownmixConfig: LinearLayout
    private lateinit var itemUpAction: LinearLayout
    private lateinit var textUpActionValue: TextView
    private lateinit var itemUpdate: LinearLayout
    private lateinit var textUpdate: TextView
    private lateinit var textUpdateDesc: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var itemAppLanguage: LinearLayout
    private lateinit var textAppLanguageValue: TextView

    // Список языков для выбора аудио/субтитров (ISO 639-1)
    private val trackLanguages = listOf(
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

    // Список языков для интерфейса приложения (ISO 639-1)
    private val appLanguageCodes = listOf(
        SettingsRepository.LANG_SYSTEM_DEFAULT,
        "en",
        "ru",
        "be",
        "uk",
        "zh"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_settings)
        repo = SettingsRepository(this)

        bindViews()
        setupLogic()

        val focusItemId = intent.getIntExtra("FOCUS_ITEM_ID", 0)

        if (focusItemId != 0) {
            val viewToFocus = findViewById<View>(focusItemId)
            viewToFocus?.post {
                scrollView.smoothScrollTo(0, viewToFocus.top)
                scrollView.requestChildFocus(viewToFocus.parent as View, viewToFocus)
                viewToFocus.requestFocus()

                if (focusItemId == R.id.item_update) {
                    val info = updateViewModel.updateInfo.value
                    if (info != null) {
                        showUpdateDialog(info)
                    } else {
                        updateViewModel.forceCheckUpdates()
                        updateViewModel.updateInfo.observe(this) { newInfo ->
                            if (newInfo != null) {
                                showUpdateDialog(newInfo)
                                updateViewModel.updateInfo.removeObservers(this)
                            }
                        }
                    }
                }
            }
        } else {
            itemDecoder.post{
                itemDecoder.requestFocus()
            }
        }
    }

    private fun bindViews() {
        scrollView = findViewById(R.id.settings_scrollview)

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

        itemUpAction = findViewById(R.id.item_up_action)
        textUpActionValue = findViewById(R.id.text_up_action_value)
        itemUpdate = findViewById(R.id.item_update)
        textUpdate = findViewById(R.id.text_update)
        textUpdateDesc = findViewById(R.id.text_update_desc)

        itemAppLanguage = findViewById(R.id.item_app_language)
        textAppLanguageValue = findViewById(R.id.text_app_language_value)
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

        // Calibration
        itemCalibrateVr.setOnClickListener {
            Toast.makeText(this, getString(R.string.develop_tost), Toast.LENGTH_SHORT).show()
        }

        itemCalibrateAnaglyph.setOnClickListener {
            Toast.makeText(this, getString(R.string.develop_tost), Toast.LENGTH_SHORT).show()
        }

        var crashCounter = 0
        findViewById<TextView>(R.id.settings_header).setOnClickListener {
            crashCounter++
            if (crashCounter >= 5) {
                throw RuntimeException("Test Crash: This is a simulated exception!")
            }
        }

        // Действие кнопки "Вверх"
        updateUpActionUI()
        itemUpAction.setOnClickListener {
            val current = repo.getUpButtonAction()
            val next = (current + 1) % 3 // 0, 1, 2
            repo.setUpButtonAction(next)
            updateUpActionUI()
        }

        // Обновление
        updateUpdateUI()
        itemUpdate.setOnClickListener {
            val info = updateViewModel.updateInfo.value
            if (info != null) {
                // Обновление доступно -> Показываем диалог
                showUpdateDialog(info)
            } else {
                // Обновлений нет -> Запускаем проверку
                updateViewModel.forceCheckUpdates()
            }
        }
        // Languages
        updateLangUI(textAudioLangValue, repo.getPreferredAudioLang())
        itemAudioLang.setOnClickListener {
            showLanguageSelectionDialog(
                title = getString(R.string.pref_language_audio),
                languageCodes = trackLanguages,
                currentCode = repo.getPreferredAudioLang()
            ) { selectedCode ->
                // В колбэке сохраняем результат и обновляем UI
                repo.setPreferredAudioLang(selectedCode)
                updateLangUI(textAudioLangValue, selectedCode)
                itemAudioLang.requestFocus()
            }
        }

        updateLangUI(textSubLangValue, repo.getPreferredSubLang())
        itemSubLang.setOnClickListener {
            showLanguageSelectionDialog(
                title = getString(R.string.pref_sub_lang_default),
                languageCodes = trackLanguages,
                currentCode = repo.getPreferredSubLang()
            ) { selectedCode ->
                repo.setPreferredSubLang(selectedCode)
                updateLangUI(textSubLangValue, selectedCode)
                itemSubLang.requestFocus()
            }
        }

        // Язык приложения
        updateLangUI(textAppLanguageValue, repo.getAppLanguage())
        itemAppLanguage.setOnClickListener {
            showLanguageSelectionDialog(
                title = getString(R.string.pref_app_language),
                languageCodes = appLanguageCodes,
                currentCode = repo.getAppLanguage()
            ) { selectedCode ->
                repo.setAppLanguage(selectedCode)
                val appLocale = if (selectedCode == SettingsRepository.LANG_SYSTEM_DEFAULT) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(selectedCode)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
                // UI обновится автоматически после пересоздания Activity
            }
        }

        // Наблюдаем за состоянием обновления
        updateViewModel.updateInfo.observe(this) { updateUpdateUI() }
        updateViewModel.isCheckingUpdates.observe(this) { checking ->
            if (checking) {
                textUpdateDesc.text = getString(R.string.update_btn_checking)
            } else {
                updateUpdateUI()
            }
        }

        // Наблюдаем за прогрессом скачивания
        updateViewModel.downloadProgress.observe(this) { progress ->
            updateDownloadProgress(progress)
        }
    }

    private fun updateUpActionUI() {
        val action = repo.getUpButtonAction()
        textUpActionValue.text = when (action) {
            1 -> getString(R.string.pref_up_action_osd)
            2 -> getString(R.string.pref_up_action_menu)
            else -> getString(R.string.pref_up_action_none)
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        updateDialog = Dialog(this, R.style.Theme_App_Dialog)
        updateDialog?.setContentView(R.layout.dialog_update)

        val title = updateDialog?.findViewById<TextView>(R.id.update_title)
        val desc = updateDialog?.findViewById<TextView>(R.id.update_desc)
        val btnUpdate = updateDialog?.findViewById<View>(R.id.btn_update)
        val btnCancel = updateDialog?.findViewById<View>(R.id.btn_cancel)
        val progress = updateDialog?.findViewById<ProgressBar>(R.id.update_progress)
        val status = updateDialog?.findViewById<TextView>(R.id.update_status)

        title?.text = getString(R.string.update_title, info.version)
        desc?.text = info.description

        progress?.visibility = View.GONE
        status?.visibility = View.GONE
        btnUpdate?.visibility = View.VISIBLE
        btnCancel?.visibility = View.VISIBLE

        btnUpdate?.setOnClickListener {
            updateViewModel.startUpdate()
            progress?.visibility = View.VISIBLE
            status?.visibility = View.VISIBLE
            btnUpdate.visibility = View.GONE
            btnCancel?.visibility = View.GONE
        }

        btnCancel?.setOnClickListener {
            updateDialog?.dismiss()
        }

        updateDialog?.show()
    }

    private fun updateDownloadProgress(percent: Int) {
        val progress = updateDialog?.findViewById<ProgressBar>(R.id.update_progress)
        val status = updateDialog?.findViewById<TextView>(R.id.update_status)
        progress?.progress = percent
        status?.text = getString(R.string.update_loading, percent)

        if (percent >= 100) {
            updateDialog?.dismiss()
        }
    }

    private fun updateUpdateUI() {
        val info = updateViewModel.updateInfo.value
        if (info != null) {
            textUpdate.text = getString(R.string.update_btn_update_fmt, info.version)
        } else {
            textUpdate.text = getString(R.string.update_btn_check)
        }
        textUpdateDesc.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
    }

    private fun showAudioMixDialog() {
        val dialog = Dialog(this, R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_audio_mix)

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
            view.text = getString(R.string.percentage_int_format,progress)
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

        val presets = AudioMixerLogic.MixPreset.entries.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presets.map { getString(it.titleResId) })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val currentPresetId = repo.getMixPreset()
        val currentPresetIndex = presets.indexOfFirst { it.id == currentPresetId }.coerceAtLeast(0)
        spinner.setSelection(currentPresetIndex, false)

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

    private fun showLanguageSelectionDialog(
        title: String,
        languageCodes: List<String>,
        currentCode: String,
        onLanguageSelected: (String) -> Unit
    ) {
        val languageItems = languageCodes.map { langCode ->
            langCode to LocaleUtils.getFormattedLanguageName(langCode, this)
        }

        val languageNames = languageItems.map { it.second }
        val currentIndex = languageItems.indexOfFirst { it.first == currentCode }.coerceAtLeast(0)

        val dialog = Dialog(this, R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_list_choice)

        val titleView = dialog.findViewById<TextView>(R.id.dialog_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.dialog_recycler_view)

        titleView.text = title
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ChoiceAdapter(languageNames, currentIndex) { which ->
            val selectedCode = languageItems[which].first
            onLanguageSelected(selectedCode)
            dialog.dismiss()
        }

        recyclerView.post {
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentIndex, 0)
            recyclerView.postDelayed({
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentIndex)
                viewHolder?.itemView?.requestFocus()
            }, 50)
        }

        dialog.show()
    }

    @SuppressLint("SetTextI18n")
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
            textBoostValue.text = getString(R.string.track_off)
        } else {
            textBoostValue.text = getString(R.string.loudness_boost_format, boost / 100)
        }
    }

    private fun updateLangUI(textView: TextView, langCode: String) {
        textView.text = LocaleUtils.getFormattedLanguageName(langCode, this)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateDialog?.dismiss()
    }
}