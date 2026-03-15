package top.rootu.dddplayer.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AudioMixerLogic
import top.rootu.dddplayer.logic.UpdateInfo
import top.rootu.dddplayer.ui.adapter.ChoiceAdapter
import top.rootu.dddplayer.utils.LocaleUtils
import top.rootu.dddplayer.viewmodel.GlobalSettingsViewModel
import top.rootu.dddplayer.viewmodel.UpdateViewModel

class GlobalSettingsActivity : AppCompatActivity() {

    private val updateViewModel: UpdateViewModel by viewModels()
    private val settingsViewModel: GlobalSettingsViewModel by viewModels()

    private var updateDialog: Dialog? = null

    // UI Elements (lateinit, так как они инициализируются в bindViews)
    private lateinit var scrollView: ScrollView
    private lateinit var btnClose: ImageButton
    private lateinit var itemExit: LinearLayout
    private lateinit var itemDecoder: LinearLayout
    private lateinit var textDecoderDesc: TextView
    private lateinit var textDecoderValue: TextView
    private lateinit var itemTunneling: LinearLayout
    private lateinit var switchTunneling: SwitchCompat
    private lateinit var itemDv: LinearLayout
    private lateinit var switchDv: SwitchCompat
    private lateinit var itemAfr: LinearLayout
    private lateinit var switchAfr: SwitchCompat
    private lateinit var textAfrDesc: TextView
    private lateinit var itemAfrResolution: LinearLayout
    private lateinit var switchAfrResolution: SwitchCompat
    private lateinit var itemAfrFpsCorrection: LinearLayout
    private lateinit var switchAfrFpsCorrection: SwitchCompat
    private lateinit var itemAfrDoubleRefresh: LinearLayout
    private lateinit var switchAfrDoubleRefresh: SwitchCompat
    private lateinit var itemAfrSkip24: LinearLayout
    private lateinit var switchAfrSkip24: SwitchCompat
    private lateinit var itemAfrPause: LinearLayout
    private lateinit var textAfrPauseValue: TextView
    private lateinit var itemAfrSkipShorts: LinearLayout
    private lateinit var switchAfrSkipShorts: SwitchCompat
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
    private lateinit var itemOkAction: LinearLayout
    private lateinit var textOkActionValue: TextView
    private lateinit var itemResumeAction: LinearLayout
    private lateinit var textResumeActionValue: TextView
    private lateinit var itemSwipeAction: LinearLayout
    private lateinit var textSwipeActionValue: TextView
    private lateinit var itemUpdate: LinearLayout
    private lateinit var textUpdate: TextView
    private lateinit var textUpdateDesc: TextView
    private lateinit var itemAppLanguage: LinearLayout
    private lateinit var textAppLanguageValue: TextView
    private lateinit var itemRememberZoom: LinearLayout
    private lateinit var switchRememberZoom: SwitchCompat
    private lateinit var itemShowIndex: LinearLayout
    private lateinit var switchShowIndex: SwitchCompat
    private lateinit var itemShowClock: LinearLayout
    private lateinit var textShowClockValue: TextView
    private lateinit var itemPauseDim: LinearLayout
    private lateinit var textPauseDimValue: TextView

    private lateinit var itemStartupPlaylist: LinearLayout
    private lateinit var textStartupPlaylistValue: TextView
    private lateinit var itemLiveMode: LinearLayout
    private lateinit var switchLiveMode: SwitchCompat
    private lateinit var itemTrackChangeInfo: LinearLayout
    private lateinit var switchTrackChangeInfo: SwitchCompat
    private lateinit var itemBufferDisplay: LinearLayout
    private lateinit var textBufferDisplayValue: TextView
    private lateinit var itemBufferSize: LinearLayout
    private lateinit var textBufferSizeValue: TextView

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

        bindViews()
        setupListeners()
        observeViewModel()
        handleInitialFocus(intent.getIntExtra("FOCUS_ITEM_ID", 0))
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
        itemDv = findViewById(R.id.item_dv)
        switchDv = findViewById(R.id.switch_dv)
        itemAfr = findViewById(R.id.item_afr)
        switchAfr = findViewById(R.id.switch_afr)
        textAfrDesc = findViewById(R.id.text_afr_desc)
        itemAfrResolution = findViewById(R.id.item_afr_resolution)
        switchAfrResolution = findViewById(R.id.switch_afr_resolution)
        itemAfrFpsCorrection = findViewById(R.id.item_afr_fps_correction)
        switchAfrFpsCorrection = findViewById(R.id.switch_afr_fps_correction)
        itemAfrDoubleRefresh = findViewById(R.id.item_afr_double_refresh)
        switchAfrDoubleRefresh = findViewById(R.id.switch_afr_double_refresh)
        itemAfrSkip24 = findViewById(R.id.item_afr_skip_24)
        switchAfrSkip24 = findViewById(R.id.switch_afr_skip_24)
        itemAfrPause = findViewById(R.id.item_afr_pause)
        textAfrPauseValue = findViewById(R.id.text_afr_pause_value)
        itemAfrSkipShorts = findViewById(R.id.item_afr_skip_shorts)
        switchAfrSkipShorts = findViewById(R.id.switch_afr_skip_shorts)
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
        itemOkAction = findViewById(R.id.item_ok_action)
        textOkActionValue = findViewById(R.id.text_ok_action_value)
        itemResumeAction = findViewById(R.id.item_resume_mode)
        textResumeActionValue = findViewById(R.id.text_resume_mode_value)
        itemSwipeAction = findViewById(R.id.item_swipe_action)
        textSwipeActionValue = findViewById(R.id.text_swipe_action_value)
        itemUpdate = findViewById(R.id.item_update)
        textUpdate = findViewById(R.id.text_update)
        textUpdateDesc = findViewById(R.id.text_update_desc)
        itemAppLanguage = findViewById(R.id.item_app_language)
        textAppLanguageValue = findViewById(R.id.text_app_language_value)
        itemRememberZoom = findViewById(R.id.item_remember_zoom)
        switchRememberZoom = findViewById(R.id.switch_remember_zoom)
        itemShowIndex = findViewById(R.id.item_show_index)
        switchShowIndex = findViewById(R.id.switch_show_index)
        itemShowClock = findViewById(R.id.item_show_clock)
        textShowClockValue = findViewById(R.id.text_show_clock_value)
        itemPauseDim = findViewById(R.id.item_pause_dim)
        textPauseDimValue = findViewById(R.id.text_pause_dim_value)

        itemStartupPlaylist = findViewById(R.id.item_startup_playlist)
        textStartupPlaylistValue = findViewById(R.id.text_startup_playlist_value)
        itemLiveMode = findViewById(R.id.item_live_mode)
        switchLiveMode = findViewById(R.id.switch_live_mode)
        itemTrackChangeInfo = findViewById(R.id.item_track_change_info)
        switchTrackChangeInfo = findViewById(R.id.switch_track_change_info)
        itemBufferDisplay = findViewById(R.id.item_buffer_display)
        textBufferDisplayValue = findViewById(R.id.text_buffer_display_value)
        itemBufferSize = findViewById(R.id.item_buffer_size)
        textBufferSizeValue = findViewById(R.id.text_buffer_size_value)
    }

    private fun setupListeners() {
        btnClose.setOnClickListener { finish() }
        itemExit.setOnClickListener { finish() }

        // Decoder
        itemDecoder.setOnClickListener { settingsViewModel.toggleDecoderPriority() }

        // Downmix
        itemDownmix.setOnClickListener {
            settingsViewModel.toggleStereoDownmix(!switchDownmix.isChecked)
        }
        itemDownmixConfig.setOnClickListener {
            settingsViewModel.refreshCustomMixParams()
            showAudioMixDialog()
        }

        // Tunneling
        itemTunneling.setOnClickListener {
            settingsViewModel.toggleTunneling(!switchTunneling.isChecked)
        }

        // Dolby Vision
        itemDv.setOnClickListener {
            settingsViewModel.toggleMapDvToHevc(!switchDv.isChecked)
        }

        // AFR
        itemAfr.setOnClickListener {
            settingsViewModel.toggleFrameRateMatching(!switchAfr.isChecked)
        }
        itemAfrResolution.setOnClickListener {
            settingsViewModel.toggleAfrResolutionSwitch(!switchAfrResolution.isChecked)
        }
        itemAfrFpsCorrection.setOnClickListener {
            settingsViewModel.toggleAfrFpsCorrection(!switchAfrFpsCorrection.isChecked)
        }
        itemAfrDoubleRefresh.setOnClickListener {
            settingsViewModel.toggleAfrDoubleRefreshRate(!switchAfrDoubleRefresh.isChecked)
        }
        itemAfrSkip24.setOnClickListener {
            settingsViewModel.toggleAfrSkip24Rate(!switchAfrSkip24.isChecked)
        }
        itemAfrPause.setOnClickListener {
            settingsViewModel.cycleAfrPause()
        }
        itemAfrSkipShorts.setOnClickListener {
            settingsViewModel.toggleAfrSkipShorts(!switchAfrSkipShorts.isChecked)
        }

        // Skip Silence
        itemSkipSilence.setOnClickListener {
            settingsViewModel.toggleSkipSilence(!switchSkipSilence.isChecked)
        }

        // Boost
        itemBoost.setOnClickListener { settingsViewModel.cycleLoudnessBoost() }

        // Calibration (Placeholder)
        itemCalibrateVr.setOnClickListener {
            Toast.makeText(applicationContext, getString(R.string.develop_tost), Toast.LENGTH_SHORT).show()
        }
        itemCalibrateAnaglyph.setOnClickListener {
            Toast.makeText(applicationContext, getString(R.string.develop_tost), Toast.LENGTH_SHORT).show()
        }

        // Crash Test
        var crashCounter = 0
        findViewById<TextView>(R.id.settings_header).setOnClickListener {
            crashCounter++
            if (crashCounter >= 5) {
                throw RuntimeException("Test Crash: This is a simulated exception!")
            }
        }

        itemRememberZoom.setOnClickListener {
            settingsViewModel.toggleRememberZoom(!switchRememberZoom.isChecked)
        }

        itemShowIndex.setOnClickListener {
            settingsViewModel.toggleShowPlaylistIndex(!switchShowIndex.isChecked)
        }

        itemShowClock.setOnClickListener { settingsViewModel.cycleClockTransparency() }
        itemResumeAction.setOnClickListener {
            val options = listOf(getString(R.string.resume_mode_ask), getString(R.string.resume_mode_always), getString(R.string.resume_mode_never))
            showSingleChoiceDialog(getString(R.string.pref_resume_mode), options, settingsViewModel.resumeModeAction.value ?: 0) {
                settingsViewModel.setResumeModeAction(it)
            }
        }
        itemUpAction.setOnClickListener {
            val options = listOf(getString(R.string.pref_up_action_none), getString(R.string.pref_up_action_osd), getString(R.string.pref_up_action_menu), getString(R.string.pref_up_action_info))
            showSingleChoiceDialog(getString(R.string.pref_up_action), options, settingsViewModel.upButtonAction.value ?: 1) {
                settingsViewModel.setUpButtonAction(it)
            }
        }
        itemOkAction.setOnClickListener {
            val options = listOf(getString(R.string.pref_ok_action_pause), getString(R.string.pref_ok_action_pause_panel), getString(R.string.pref_ok_action_panel))
            showSingleChoiceDialog(getString(R.string.pref_ok_action), options, settingsViewModel.okButtonAction.value ?: 1) {
                settingsViewModel.setOkButtonAction(it)
            }
        }
        itemSwipeAction.setOnClickListener {
            val options = listOf(getString(R.string.swipe_action_none), getString(R.string.swipe_action_seek), getString(R.string.swipe_action_playlist))
            showSingleChoiceDialog(getString(R.string.pref_swipe_horizontal), options, settingsViewModel.horizontalSwipeAction.value ?: 2) {
                settingsViewModel.setHorizontalSwipeAction(it)
            }
        }

        // Update
        itemUpdate.setOnClickListener {
            val info = updateViewModel.updateInfo.value
            if (info != null) {
                showUpdateDialog(info)
            } else {
                updateViewModel.forceCheckUpdates()
            }
        }

        itemStartupPlaylist.setOnClickListener { showUrlInputDialog() }
        itemLiveMode.setOnClickListener { settingsViewModel.toggleLiveMode(!switchLiveMode.isChecked) }
        itemTrackChangeInfo.setOnClickListener { settingsViewModel.toggleShowInfoOnTrackChange(!switchTrackChangeInfo.isChecked) }





        itemBufferDisplay.setOnClickListener {
            val options = listOf(getString(R.string.buffer_off), getString(R.string.buffer_panel), getString(R.string.buffer_clock))
            showSingleChoiceDialog(getString(R.string.pref_buffer_display), options, settingsViewModel.bufferDisplayMode.value ?: 0) {
                settingsViewModel.setBufferDisplayMode(it)
            }
        }

        itemBufferSize.setOnClickListener {
            val availableOptions = settingsViewModel.targetBufferOptions
            val optionsLabels = availableOptions.map {
                if (it == -1) getString(R.string.buffer_size_auto) else getString(R.string.buffer_size_mb, it)
            }

            val currentVal = settingsViewModel.targetBufferMB.value ?: -1
            val currentIndex = availableOptions.indexOf(currentVal).coerceAtLeast(0)

            showSingleChoiceDialog(getString(R.string.pref_buffer_size), optionsLabels, currentIndex) {
                settingsViewModel.setTargetBufferMB(availableOptions[it])
            }
        }

        // Languages
        itemAudioLang.setOnClickListener {
            showLanguageSelectionDialog(getString(R.string.pref_language_audio), trackLanguages, settingsViewModel.preferredAudioLang.value ?: SettingsRepository.TRACK_DEFAULT) {
                settingsViewModel.setPreferredAudioLang(it); itemAudioLang.requestFocus()
            }
        }

        itemSubLang.setOnClickListener {
            showLanguageSelectionDialog(getString(R.string.pref_sub_lang_default), trackLanguages, settingsViewModel.preferredSubLang.value ?: SettingsRepository.TRACK_DEFAULT) {
                settingsViewModel.setPreferredSubLang(it); itemSubLang.requestFocus()
            }
        }

        itemAppLanguage.setOnClickListener {
            showLanguageSelectionDialog(getString(R.string.pref_app_language), appLanguageCodes, settingsViewModel.appLanguage.value ?: SettingsRepository.LANG_SYSTEM_DEFAULT) {
                settingsViewModel.setAppLanguage(it); applyAppLocale(it)
            }
        }

        itemPauseDim.setOnClickListener { settingsViewModel.cyclePauseDimLevel() }
    }

    private fun observeViewModel() {
        // Playback
        settingsViewModel.decoderPriority.observe(this) { mode ->
            textDecoderValue.text = settingsViewModel.getDecoderValueString(mode)
            textDecoderDesc.setText(settingsViewModel.getDecoderDescResId(mode))
        }
        settingsViewModel.isTunnelingEnabled.observe(this) { switchTunneling.isChecked = it }
        settingsViewModel.isMapDvToHevcEnabled.observe(this) { switchDv.isChecked = it }
        // AFR
        settingsViewModel.isFrameRateMatchingEnabled.observe(this) { enabled ->
            switchAfr.isChecked = enabled
            textAfrDesc.setText(settingsViewModel.getAfrDescResId(enabled))

            // Показываем под-настройки только если AFR включен
            val visibility = if (enabled) View.VISIBLE else View.GONE
            itemAfrResolution.visibility = visibility
            itemAfrFpsCorrection.visibility = visibility
            itemAfrDoubleRefresh.visibility = visibility
            itemAfrSkip24.visibility = visibility
            itemAfrPause.visibility = visibility
            itemAfrSkipShorts.visibility = visibility
        }
        settingsViewModel.isAfrResolutionSwitchEnabled.observe(this) { switchAfrResolution.isChecked = it }
        settingsViewModel.isAfrFpsCorrectionEnabled.observe(this) { switchAfrFpsCorrection.isChecked = it }
        settingsViewModel.isAfrDoubleRefreshRateEnabled.observe(this) { switchAfrDoubleRefresh.isChecked = it }
        settingsViewModel.isAfrSkip24RateEnabled.observe(this) { switchAfrSkip24.isChecked = it }
        settingsViewModel.isAfrSkipShortsEnabled.observe(this) { switchAfrSkipShorts.isChecked = it }
        settingsViewModel.afrPauseMs.observe(this) { ms ->
            textAfrPauseValue.text = if (ms == 0)
                getString(R.string.afr_pause_none)
            else
                getString(R.string.afr_pause_format, ms / 1000f)
        }

        settingsViewModel.isSkipSilenceEnabled.observe(this) { enabled ->
            switchSkipSilence.isChecked = enabled
            textSkipSilenceDesc.setText(settingsViewModel.getSkipSilenceDescResId(enabled))
        }
        settingsViewModel.loudnessBoost.observe(this) { boost ->
            textBoostValue.text = if (boost == 0)
                getString(R.string.track_off)
            else
                getString(R.string.loudness_boost_format, boost / 100)
        }

        // Downmix
        settingsViewModel.isStereoDownmixEnabled.observe(this) { enabled ->
            switchDownmix.isChecked = enabled
            itemDownmixConfig.visibility = if (enabled) View.VISIBLE else View.GONE
        }

        // Languages
        settingsViewModel.preferredAudioLang.observe(this) { langCode ->
            updateLangUI(textAudioLangValue, langCode)
        }
        settingsViewModel.preferredSubLang.observe(this) { langCode ->
            updateLangUI(textSubLangValue, langCode)
        }
        settingsViewModel.appLanguage.observe(this) { langCode ->
            updateLangUI(textAppLanguageValue, langCode)
        }

        // UI
        settingsViewModel.resumeModeAction.observe(this) { action ->
            textResumeActionValue.text = when (action) {
                SettingsRepository.RESUME_NEVER -> getString(R.string.resume_mode_never)
                SettingsRepository.RESUME_ALWAYS -> getString(R.string.resume_mode_always)
                else -> getString(R.string.resume_mode_ask)
            }
        }

        settingsViewModel.upButtonAction.observe(this) { action ->
            textUpActionValue.text = when (action) {
                1 -> getString(R.string.pref_up_action_osd)
                2 -> getString(R.string.pref_up_action_menu)
                3 -> getString(R.string.pref_up_action_info)
                else -> getString(R.string.pref_up_action_none)
            }
        }

        settingsViewModel.okButtonAction.observe(this) { action ->
            textOkActionValue.text = when (action) {
                0 -> getString(R.string.pref_ok_action_pause)
                1 -> getString(R.string.pref_ok_action_pause_panel)
                else -> getString(R.string.pref_ok_action_panel)
            }
        }

        settingsViewModel.horizontalSwipeAction.observe(this) { action ->
            textSwipeActionValue.text = when (action) {
                1 -> getString(R.string.swipe_action_seek)
                2 -> getString(R.string.swipe_action_playlist)
                else -> getString(R.string.swipe_action_none)
            }
        }

        settingsViewModel.isRememberZoomEnabled.observe(this) { switchRememberZoom.isChecked = it }
        settingsViewModel.isShowPlaylistIndexEnabled.observe(this) { switchShowIndex.isChecked = it }
        settingsViewModel.clockTransparency.observe(this) { transparency ->
            textShowClockValue.text = if (transparency == -1) getString(R.string.track_off) else getString(R.string.percentage_int_format, transparency)
        }
        settingsViewModel.pauseDimLevel.observe(this) { level ->
            textPauseDimValue.text = if (level == 0) getString(R.string.track_off) else "$level%"
        }

        settingsViewModel.startupPlaylistUri.observe(this) { uri ->
            textStartupPlaylistValue.text = if (uri.isNullOrEmpty()) getString(R.string.pref_startup_playlist_not_set) else uri
        }
        settingsViewModel.isLiveModeEnabled.observe(this) { switchLiveMode.isChecked = it }
        settingsViewModel.isShowInfoOnTrackChange.observe(this) { switchTrackChangeInfo.isChecked = it }
        settingsViewModel.bufferDisplayMode.observe(this) { mode ->
            textBufferDisplayValue.text = when(mode) {
                1 -> getString(R.string.buffer_panel)
                2 -> getString(R.string.buffer_clock)
                else -> getString(R.string.buffer_off)
            }
        }
        settingsViewModel.targetBufferMB.observe(this) { size ->
            textBufferSizeValue.text = if (size == -1) getString(R.string.buffer_size_auto) else getString(R.string.buffer_size_mb, size)
        }

        updateViewModel.updateInfo.observe(this) { updateUpdateUI() }
        updateViewModel.isCheckingUpdates.observe(this) { checking ->
            if (checking) textUpdateDesc.text = getString(R.string.update_btn_checking) else updateUpdateUI()
        }
        updateViewModel.downloadProgress.observe(this) { updateDownloadProgress(it) }
    }

    private fun showUrlInputDialog() {
        val dialog = Dialog(this, R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_input_url)

        val editUrl = dialog.findViewById<EditText>(R.id.edit_url)
        val btnSave = dialog.findViewById<Button>(R.id.btn_save)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel)

        editUrl.setText(settingsViewModel.startupPlaylistUri.value ?: "")
        editUrl.setSelection(editUrl.text.length)

        btnSave.setOnClickListener {
            settingsViewModel.setStartupPlaylistUri(editUrl.text.toString())
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        editUrl.requestFocus()
    }

    private fun showSingleChoiceDialog(title: String, items: List<String>, currentIndex: Int, onSelected: (Int) -> Unit) {
        val dialog = Dialog(this, R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_list_choice)
        dialog.findViewById<TextView>(R.id.dialog_title).text = title

        val recycler = dialog.findViewById<RecyclerView>(R.id.dialog_recycler_view)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = ChoiceAdapter(items, currentIndex) { idx ->
            onSelected(idx)
            dialog.dismiss()
        }

        dialog.show()
        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentIndex, 0)
            recycler.postDelayed({ recycler.findViewHolderForAdapterPosition(currentIndex)?.itemView?.requestFocus() }, 50)
        }
    }

    private fun handleInitialFocus(focusItemId: Int) {
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
                    }
                }
            }
        } else {
            itemDecoder.post{
                itemDecoder.requestFocus()
            }
        }
    }

    private fun applyAppLocale(selectedCode: String) {
        val appLocale = if (selectedCode == SettingsRepository.LANG_SYSTEM_DEFAULT) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(selectedCode)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)
        // UI обновится автоматически после пересоздания Activity
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        updateDialog?.dismiss()

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

        fun updateText(view: TextView, progress: Int) {
            view.text = getString(R.string.percentage_int_format, progress)
        }

        var isUpdatingUiFromCode = false

        fun updateSeekBars(params: AudioMixerLogic.MixParams) {
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

            // Всегда берем самые свежие данные напрямую из репозитория, игнорируя кэш LiveData
            val params = AudioMixerLogic.getParamsForPreset(preset, settingsViewModel.repository)

            updateSeekBars(params)
        }

        fun initSeek(seekBar: SeekBar, textView: TextView, type: String) {
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateText(textView, progress)
                    if (fromUser && !isUpdatingUiFromCode) {
                        settingsViewModel.updateCustomMixParam(type, progress / 100f)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        // --- Init Logic ---
        val presets = AudioMixerLogic.MixPreset.entries.toTypedArray()
        // Используем контекст Activity для локализации названий пресетов
        val presetNames = presets.map { getString(it.titleResId) }
        val adapter = ArrayAdapter(this, R.layout.item_spinner_selected, presetNames)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinner.adapter = adapter

        val currentPresetId = settingsViewModel.mixPresetId.value ?: AudioMixerLogic.MixPreset.STANDARD.id
        val currentPresetIndex = presets.indexOfFirst { it.id == currentPresetId }.coerceAtLeast(0)
        spinner.setSelection(currentPresetIndex, false)

        initSeek(seekFront, valFront, "front")
        initSeek(seekCenter, valCenter, "center")
        initSeek(seekRear, valRear, "rear")
        initSeek(seekMiddle, valMiddle, "middle")
        initSeek(seekLfe, valLfe, "lfe")

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = presets[position]
                settingsViewModel.setMixPreset(selected.id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Наблюдаем за кастомными параметрами, чтобы обновить ползунки, если они меняются
        settingsViewModel.mixParams.observe(this) { params ->
            if (settingsViewModel.mixPresetId.value == AudioMixerLogic.MixPreset.CUSTOM.id) {
                updateSeekBars(params)
            }
        }

        // Наблюдаем за пресетом, чтобы обновить состояние ползунков
        settingsViewModel.mixPresetId.observe(this) { presetId ->
            val preset = presets.find { it.id == presetId } ?: AudioMixerLogic.MixPreset.STANDARD
            updateSlidersState(preset)
            // Если пресет изменился, обновляем спиннер, если он не был изменен пользователем
            if (spinner.selectedItemPosition != preset.ordinal) {
                spinner.setSelection(preset.ordinal, false)
            }
        }

        updateSlidersState(presets[currentPresetIndex])

        btnReset.setOnClickListener {
            settingsViewModel.resetCustomMixParams()
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

        showSingleChoiceDialog(title, languageNames, currentIndex) { which ->
            onLanguageSelected(languageItems[which].first)
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