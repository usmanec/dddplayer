package top.rootu.dddplayer.ui

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.os.LocaleListCompat
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AudioMixerLogic
import top.rootu.dddplayer.logic.OpenSourceLicenses
import top.rootu.dddplayer.logic.UpdateInfo
import top.rootu.dddplayer.model.MenuItem
import top.rootu.dddplayer.model.PlaybackSpeed
import top.rootu.dddplayer.player.DoviBridge
import top.rootu.dddplayer.ui.adapter.SideMenuAdapter
import top.rootu.dddplayer.ui.calibration.CalibrationWizardActivity
import top.rootu.dddplayer.utils.LocaleUtils
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.viewmodel.GlobalSettingsViewModel
import top.rootu.dddplayer.viewmodel.UpdateViewModel

class GlobalSettingsActivity : AppCompatActivity() {

    // Категория настроек: секция в разметке, название и иконка в списке слева
    private data class SettingsCategory(val sectionId: Int, val titleRes: Int, val iconRes: Int)

    private val categories = listOf(
        SettingsCategory(R.id.section_playback, R.string.pref_general_header, R.drawable.ic_settings_play),
        SettingsCategory(R.id.section_ui, R.string.settings_cat_ui, R.drawable.ic_settings_ui),
        SettingsCategory(R.id.section_video, R.string.settings_cat_video, R.drawable.ic_settings_video),
        SettingsCategory(R.id.section_audio, R.string.settings_cat_audio, R.drawable.ic_settings_audio),
        SettingsCategory(R.id.section_subtitles, R.string.settings_cat_subtitles, R.drawable.ic_settings_subtitles),
        SettingsCategory(R.id.section_3d, R.string.settings_cat_3d, R.drawable.ic_settings_3d),
        SettingsCategory(R.id.section_control, R.string.settings_cat_control, R.drawable.ic_settings_remote),
        SettingsCategory(R.id.section_app, R.string.pref_sec_app, R.drawable.ic_settings_info)
    )
    private val categoryViews = mutableListOf<View>()
    private var selectedCategory = -1
    // Последний пункт с фокусом в каждой категории: при возврате в категорию фокус встает на него
    private val lastFocusedInCategory = mutableMapOf<Int, View>()

    private val updateViewModel: UpdateViewModel by viewModels()
    private val settingsViewModel: GlobalSettingsViewModel by viewModels()

    private var updateDialog: Dialog? = null

    // UI Elements (lateinit, так как они инициализируются в bindViews)
    private lateinit var scrollView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var itemSubtitleStyle: LinearLayout
    private lateinit var btnClose: ImageButton
    private lateinit var itemExit: LinearLayout
    private lateinit var itemDecoder: LinearLayout
    private lateinit var itemAutoPip: LinearLayout
    private lateinit var switchAutoPip: SwitchCompat
    private lateinit var itemAutoSkipIntro: LinearLayout
    private lateinit var switchAutoSkipIntro: SwitchCompat
    private lateinit var textDecoderDesc: TextView
    private lateinit var textDecoderValue: TextView
    private lateinit var itemHdr10Plus: LinearLayout
    private lateinit var switchHdr10Plus: SwitchCompat
    private lateinit var itemTunneling: LinearLayout
    private lateinit var switchTunneling: SwitchCompat
    private lateinit var itemHdAudioIec: LinearLayout
    private lateinit var switchHdAudioIec: SwitchCompat
    private lateinit var itemDv: LinearLayout
    private lateinit var textDvValue: TextView
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
    private lateinit var itemSubtitleAuto: LinearLayout
    private lateinit var textSubtitleAutoValue: TextView
    private lateinit var itemDefault3dMode: LinearLayout
    private lateinit var textDefault3dModeValue: TextView
    private lateinit var itemCalibrateVr: LinearLayout
    private lateinit var itemCalibrateAnaglyph: LinearLayout
    private lateinit var itemDownmix: LinearLayout
    private lateinit var switchDownmix: SwitchCompat
    private lateinit var itemDownmixConfig: LinearLayout
    private lateinit var itemUpAction: LinearLayout
    private lateinit var textUpActionValue: TextView
    private lateinit var itemOkAction: LinearLayout
    private lateinit var textOkActionValue: TextView
    private lateinit var itemDefaultSpeed: LinearLayout
    private lateinit var textDefaultSpeedValue: TextView
    private lateinit var itemResumeAction: LinearLayout
    private lateinit var textResumeActionValue: TextView
    private lateinit var itemExternalPosition: LinearLayout
    private lateinit var textExternalPositionValue: TextView
    private lateinit var itemGestureVolume: LinearLayout
    private lateinit var switchGestureVolume: SwitchCompat
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
    private lateinit var itemConfirmExit: LinearLayout
    private lateinit var switchConfirmExit: SwitchCompat
    private lateinit var itemBackButton: LinearLayout
    private lateinit var switchBackButton: SwitchCompat
    private lateinit var itemControlButtons: LinearLayout
    private lateinit var textControlButtonsValue: TextView
    private lateinit var itemShowClock: LinearLayout
    private lateinit var textShowClockValue: TextView
    private lateinit var itemPauseDim: LinearLayout
    private lateinit var textPauseDimValue: TextView
    private lateinit var itemLiveMode: LinearLayout
    private lateinit var switchLiveMode: SwitchCompat
    private lateinit var itemRememberAudio: LinearLayout
    private lateinit var textRememberAudioValue: TextView
    private lateinit var itemAudioPriority: LinearLayout
    private lateinit var textAudioPriorityValue: TextView
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
        setupCategories(savedInstanceState?.getInt(STATE_CATEGORY) ?: 0)
        setupListeners()
        observeViewModel()
        // После пересоздания (смена языка) пункт из интента повторно не открываем
        handleInitialFocus(if (savedInstanceState == null) intent.getIntExtra("FOCUS_ITEM_ID", 0) else 0)

        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (isInSettingsContent(newFocus)) lastFocusedInCategory[selectedCategory] = newFocus
        }

        // "Назад" из настроек категории возвращает к списку категорий, из списка - закрывает экран
        onBackPressedDispatcher.addCallback(this) {
            if (isInSettingsContent(currentFocus)) {
                categoryViews[selectedCategory].requestFocus()
            } else {
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CATEGORY, selectedCategory)
    }

    private fun setupCategories(initialCategory: Int) {
        val container = findViewById<LinearLayout>(R.id.settings_categories)
        categories.forEachIndexed { index, category ->
            val view = layoutInflater.inflate(R.layout.item_settings_category, container, false)
            view.findViewById<ImageView>(R.id.category_icon).setImageResource(category.iconRes)
            view.findViewById<TextView>(R.id.category_title).setText(category.titleRes)
            // Пульт: фокус на категории сразу показывает ее настройки
            view.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) selectCategory(index) }
            view.setOnClickListener { selectCategory(index) }
            // Вправо - к настройкам категории
            view.setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN && focusFirstSetting()
            }
            container.addView(view)
            categoryViews.add(view)
        }
        selectCategory(initialCategory.coerceIn(categories.indices))
    }

    private fun selectCategory(index: Int) {
        if (index == selectedCategory) return
        selectedCategory = index
        categories.forEachIndexed { i, category -> findViewById<View>(category.sectionId).isVisible = i == index }
        categoryViews.forEachIndexed { i, view -> view.isSelected = i == index }
        scrollView.scrollTo(0, 0)
    }

    /** Фокус на настройки выбранной категории: последний выбранный пункт или первый видимый. */
    private fun focusFirstSetting(): Boolean {
        lastFocusedInCategory[selectedCategory]?.takeIf { it.isShown && it.isFocusable }?.let {
            return it.requestFocus()
        }
        val section = findViewById<ViewGroup>(categories[selectedCategory].sectionId)
        val first = section.children.firstOrNull { it.isVisible && it.isFocusable } ?: return false
        return first.requestFocus()
    }

    /** Переход к соседней категории (CH+/CH-, PgUp/PgDn), фокус остается в той же колонке. */
    private fun switchCategory(direction: Int) {
        val index = (selectedCategory + direction).coerceIn(categories.indices)
        if (index == selectedCategory) return
        val inContent = isInSettingsContent(currentFocus)
        selectCategory(index)
        if (!inContent || !focusFirstSetting()) categoryViews[index].requestFocus()
    }

    private fun isInSettingsContent(view: View?): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === contentContainer) return true
            current = current.parent as? View
        }
        return false
    }

    // Пункт, с которого перешли на кнопку закрытия (туда возвращаемся Вниз/Влево)
    private var focusBeforeClose: View? = null

    /** Верхний пункт своей колонки: первая категория или первая видимая настройка категории. */
    private fun isTopItem(view: View?): Boolean {
        if (view == null) return false
        if (view === categoryViews.firstOrNull()) return true
        val section = findViewById<ViewGroup>(categories[selectedCategory].sectionId)
        return view === section.children.firstOrNull { it.isVisible && it.isFocusable }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            when (event.keyCode) {
                // Вверх с верхнего пункта - на кнопку закрытия
                KeyEvent.KEYCODE_DPAD_UP -> if (isTopItem(focused)) {
                    focusBeforeClose = focused
                    btnClose.requestFocus()
                    return true
                }
                // С кнопки закрытия - обратно к пункту, с которого пришли
                KeyEvent.KEYCODE_DPAD_DOWN -> if (focused === btnClose) {
                    (focusBeforeClose?.takeIf { it.isShown } ?: categoryViews[selectedCategory]).requestFocus()
                    return true
                }
                // Влево из настроек - обратно к списку категорий
                KeyEvent.KEYCODE_DPAD_LEFT -> if (focused === btnClose) {
                    (focusBeforeClose?.takeIf { it.isShown } ?: categoryViews[selectedCategory]).requestFocus()
                    return true
                } else if (isInSettingsContent(focused)) {
                    categoryViews[selectedCategory].requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    switchCategory(-1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    switchCategory(1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun bindViews() {
        scrollView = findViewById(R.id.settings_scrollview)
        contentContainer = findViewById(R.id.settings_content)
        itemSubtitleStyle = findViewById(R.id.item_subtitle_style)
        btnClose = findViewById(R.id.btn_close)
        itemExit = findViewById(R.id.item_exit)
        itemDecoder = findViewById(R.id.item_decoder)
        itemAutoPip = findViewById(R.id.item_auto_pip)
        switchAutoPip = findViewById(R.id.switch_auto_pip)
        itemAutoSkipIntro = findViewById(R.id.item_auto_skip_intro)
        switchAutoSkipIntro = findViewById(R.id.switch_auto_skip_intro)
        textDecoderDesc = findViewById(R.id.text_decoder_desc)
        textDecoderValue = findViewById(R.id.text_decoder_value)
        itemHdr10Plus = findViewById(R.id.item_hdr10plus)
        switchHdr10Plus = findViewById(R.id.switch_hdr10plus)
        itemTunneling = findViewById(R.id.item_tunneling)
        switchTunneling = findViewById(R.id.switch_tunneling)
        itemHdAudioIec = findViewById(R.id.item_hd_audio_iec)
        switchHdAudioIec = findViewById(R.id.switch_hd_audio_iec)
        itemDv = findViewById(R.id.item_dv)
        textDvValue = findViewById(R.id.text_dv_value)
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
        itemSubtitleAuto = findViewById(R.id.item_subtitle_auto)
        textSubtitleAutoValue = findViewById(R.id.text_subtitle_auto_value)
        itemDefault3dMode = findViewById(R.id.item_default_3d_mode)
        textDefault3dModeValue = findViewById(R.id.text_default_3d_mode_value)
        itemCalibrateVr = findViewById(R.id.item_calibrate_vr)
        itemCalibrateAnaglyph = findViewById(R.id.item_calibrate_anaglyph)
        itemDownmix = findViewById(R.id.item_downmix)
        switchDownmix = findViewById(R.id.switch_downmix)
        itemDownmixConfig = findViewById(R.id.item_downmix_config)
        itemUpAction = findViewById(R.id.item_up_action)
        textUpActionValue = findViewById(R.id.text_up_action_value)
        itemOkAction = findViewById(R.id.item_ok_action)
        textOkActionValue = findViewById(R.id.text_ok_action_value)
        itemDefaultSpeed = findViewById(R.id.item_default_speed)
        textDefaultSpeedValue = findViewById(R.id.text_default_speed_value)
        itemResumeAction = findViewById(R.id.item_resume_mode)
        textResumeActionValue = findViewById(R.id.text_resume_mode_value)
        itemExternalPosition = findViewById(R.id.item_external_position)
        textExternalPositionValue = findViewById(R.id.text_external_position_value)
        itemGestureVolume = findViewById(R.id.item_gesture_volume)
        switchGestureVolume = findViewById(R.id.switch_gesture_volume)
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
        itemConfirmExit = findViewById(R.id.item_confirm_exit)
        switchConfirmExit = findViewById(R.id.switch_confirm_exit)
        itemBackButton = findViewById(R.id.item_back_button)
        switchBackButton = findViewById(R.id.switch_back_button)
        itemControlButtons = findViewById(R.id.item_control_buttons)
        textControlButtonsValue = findViewById(R.id.text_control_buttons_value)
        itemShowClock = findViewById(R.id.item_show_clock)
        textShowClockValue = findViewById(R.id.text_show_clock_value)
        itemPauseDim = findViewById(R.id.item_pause_dim)
        textPauseDimValue = findViewById(R.id.text_pause_dim_value)
        itemLiveMode = findViewById(R.id.item_live_mode)
        switchLiveMode = findViewById(R.id.switch_live_mode)
        itemRememberAudio = findViewById(R.id.item_remember_audio)
        textRememberAudioValue = findViewById(R.id.text_remember_audio_value)
        itemAudioPriority = findViewById(R.id.item_audio_priority)
        textAudioPriorityValue = findViewById(R.id.text_audio_priority_value)
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

        findViewById<View>(R.id.item_reset_settings).setOnClickListener { showResetSettingsDialog() }
        findViewById<View>(R.id.item_licenses).setOnClickListener { showLicensesDialog() }
        findViewById<View>(R.id.item_diagnostics).setOnClickListener {
            startActivity(Intent(this, DeviceInfoActivity::class.java))
        }
        itemSubtitleStyle.setOnClickListener {
            startActivity(Intent(this, SubtitleStyleActivity::class.java))
        }

        // Decoder
        itemDecoder.setOnClickListener { settingsViewModel.toggleVideoDecoderPriority() }
        findViewById<View>(R.id.item_audio_decoder).setOnClickListener { settingsViewModel.toggleAudioDecoderPriority() }
        itemAutoPip.setOnClickListener {
            settingsViewModel.toggleAutoPip(!switchAutoPip.isChecked)
        }
        itemAutoSkipIntro.setOnClickListener {
            settingsViewModel.toggleAutoSkipIntro(!switchAutoSkipIntro.isChecked)
        }

        // Downmix
        itemDownmix.setOnClickListener {
            settingsViewModel.toggleStereoDownmix(!switchDownmix.isChecked)
        }
        itemDownmixConfig.setOnClickListener {
            settingsViewModel.refreshCustomMixParams()
            showAudioMixDialog()
        }

        itemHdr10Plus.setOnClickListener {
            settingsViewModel.toggleHdr10Plus(!switchHdr10Plus.isChecked)
        }

        // Tunneling
        itemTunneling.setOnClickListener {
            settingsViewModel.toggleTunneling(!switchTunneling.isChecked)
        }

        // HD-звук через IEC 61937
        itemHdAudioIec.setOnClickListener {
            settingsViewModel.toggleHdAudioIec(!switchHdAudioIec.isChecked)
        }

        // Dolby Vision
        itemDv.setOnClickListener {
            // Порядок пунктов совпадает с SettingsRepository.DV_MODE_*
            val options = listOf(getString(R.string.dv_mode_off), getString(R.string.dv_mode_auto), getString(R.string.dv_mode_always)) +
                // Конвертация P7 -> 8.1 только при собранной нативной части (lib-dovi-release.aar в app/libs)
                listOfNotNull(getString(R.string.dv_mode_convert).takeIf { DoviBridge.isAvailable() })
            // Конвертация выбрана, но библиотеки нет - режим работает как Авто, его и отмечаем
            val current = (settingsViewModel.dvMode.value ?: SettingsRepository.DV_MODE_AUTO)
                .let { if (it > options.lastIndex) SettingsRepository.DV_MODE_AUTO else it }
            showSingleChoiceDialog(getString(R.string.pref_map_dv), options, current) {
                settingsViewModel.setDvMode(it)
            }
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

        // Визарды калибровки
        itemDefault3dMode.setOnClickListener {
            val current = default3dModes.indexOf(settingsViewModel.default3dMode.value).coerceAtLeast(0)
            showSingleChoiceDialog(getString(R.string.pref_default_3d_mode), default3dModes.map { default3dModeLabel(it) }, current) {
                settingsViewModel.setDefault3dMode(default3dModes[it])
            }
        }
        itemCalibrateVr.setOnClickListener { startCalibration(CalibrationWizardActivity.MODE_VR) }
        itemCalibrateAnaglyph.setOnClickListener { startCalibration(CalibrationWizardActivity.MODE_ANAGLYPH) }
        findViewById<View>(R.id.item_reset_calibration).setOnClickListener { showResetCalibrationDialog() }

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

        itemConfirmExit.setOnClickListener {
            settingsViewModel.toggleConfirmExit(!switchConfirmExit.isChecked)
        }

        itemShowIndex.setOnClickListener {
            settingsViewModel.toggleShowPlaylistIndex(!switchShowIndex.isChecked)
        }

        itemShowClock.setOnClickListener { settingsViewModel.cycleClockTransparency() }
        itemDefaultSpeed.setOnClickListener {
            // Список скоростей и своя скорость из меню плеера
            val custom = settingsViewModel.getCustomPlaybackSpeed()
            val speeds = PlaybackSpeed.entries.map { it.value } + custom
            val options = PlaybackSpeed.entries.map { it.label } +
                getString(R.string.playback_speed_custom_value, PlaybackSpeed.label(custom))
            val current = settingsViewModel.defaultPlaybackSpeed.value ?: 1.0f
            val currentIndex = PlaybackSpeed.preset(current)?.ordinal
                ?: options.lastIndex.takeIf { kotlin.math.abs(custom - current) < 0.001f } ?: -1
            showSingleChoiceDialog(getString(R.string.pref_default_speed), options, currentIndex) {
                settingsViewModel.setDefaultPlaybackSpeed(speeds[it])
            }
        }

        itemResumeAction.setOnClickListener {
            val options = listOf(getString(R.string.resume_mode_ask), getString(R.string.resume_mode_always), getString(R.string.resume_mode_never))
            showSingleChoiceDialog(getString(R.string.pref_resume_mode), options, settingsViewModel.resumeModeAction.value ?: 0) {
                settingsViewModel.setResumeModeAction(it)
            }
        }
        itemExternalPosition.setOnClickListener {
            val options = listOf(getString(R.string.external_position_apply), getString(R.string.external_position_ask))
            showSingleChoiceDialog(getString(R.string.pref_external_position), options, settingsViewModel.externalPositionMode.value ?: SettingsRepository.EXTERNAL_POSITION_APPLY) {
                settingsViewModel.setExternalPositionMode(it)
            }
        }
        itemUpAction.setOnClickListener {
            val options = listOf(getString(R.string.pref_up_action_none), getString(R.string.pref_up_action_osd), getString(R.string.pref_up_action_menu), getString(R.string.pref_up_action_info))
            showSingleChoiceDialog(getString(R.string.pref_up_action), options, settingsViewModel.upButtonAction.value ?: SettingsRepository.UP_ACTION_INFO) {
                settingsViewModel.setUpButtonAction(it)
            }
        }
        itemOkAction.setOnClickListener {
            val options = listOf(getString(R.string.pref_ok_action_pause), getString(R.string.pref_ok_action_pause_panel), getString(R.string.pref_ok_action_panel))
            showSingleChoiceDialog(getString(R.string.pref_ok_action), options, settingsViewModel.okButtonAction.value ?: SettingsRepository.OK_ACTION_PAUSE_PANEL) {
                settingsViewModel.setOkButtonAction(it)
            }
        }
        itemGestureVolume.setOnClickListener {
            settingsViewModel.toggleVolumeBrightnessGestures(!switchGestureVolume.isChecked)
        }

        itemSwipeAction.setOnClickListener {
            val options = listOf(
                getString(R.string.swipe_action_none),
                getString(R.string.swipe_action_seek),
                getString(R.string.swipe_action_playlist),
                getString(R.string.swipe_action_exit_next)
            )
            showSingleChoiceDialog(getString(R.string.pref_swipe_horizontal), options, settingsViewModel.horizontalSwipeAction.value ?: SettingsRepository.SWIPE_PLAYLIST) {
                settingsViewModel.setHorizontalSwipeAction(it)
            }
        }
        itemBackButton.setOnClickListener {
            settingsViewModel.toggleBackButton(!switchBackButton.isChecked)
        }
        itemControlButtons.setOnClickListener {
            val keys = SettingsRepository.HIDEABLE_BUTTONS
            val hidden = settingsViewModel.hiddenControlButtons.value ?: emptySet()
            val checked = BooleanArray(keys.size) { keys[it] !in hidden }
            ChoiceDialog.showMulti(this, getString(R.string.pref_control_buttons), keys.map { getControlButtonLabel(it) }, checked) { visible ->
                settingsViewModel.setHiddenControlButtons(keys.filterIndexed { i, _ -> !visible[i] }.toSet())
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

        itemLiveMode.setOnClickListener { settingsViewModel.toggleLiveMode(!switchLiveMode.isChecked) }
        itemSubtitleAuto.setOnClickListener {
            // Порядок пунктов совпадает с SettingsRepository.SUB_AUTO_*
            val options = listOf(
                getString(R.string.sub_auto_default), getString(R.string.sub_auto_off),
                getString(R.string.sub_auto_forced), getString(R.string.sub_auto_full)
            )
            showSingleChoiceDialog(getString(R.string.pref_subtitle_auto), options, settingsViewModel.subtitleAutoMode.value ?: SettingsRepository.SUB_AUTO_DEFAULT) {
                settingsViewModel.setSubtitleAutoMode(it)
            }
        }
        itemRememberAudio.setOnClickListener {
            // Порядок пунктов совпадает с SettingsRepository.AUDIO_MEMORY_*
            val options = listOf(getString(R.string.audio_memory_off), getString(R.string.audio_memory_playlist), getString(R.string.audio_memory_all))
            showSingleChoiceDialog(getString(R.string.pref_remember_audio), options, settingsViewModel.audioTrackMemoryMode.value ?: SettingsRepository.AUDIO_MEMORY_OFF) {
                settingsViewModel.setAudioTrackMemoryMode(it)
            }
        }
        itemAudioPriority.setOnClickListener { showAudioPriorityDialog() }
        itemTrackChangeInfo.setOnClickListener { settingsViewModel.toggleShowInfoOnTrackChange(!switchTrackChangeInfo.isChecked) }

        itemBufferDisplay.setOnClickListener {
            val options = listOf(getString(R.string.buffer_off), getString(R.string.buffer_panel), getString(R.string.buffer_clock))
            showSingleChoiceDialog(getString(R.string.pref_buffer_display), options, settingsViewModel.bufferDisplayMode.value ?: SettingsRepository.BUFFER_DISPLAY_OFF) {
                settingsViewModel.setBufferDisplayMode(it)
            }
        }

        itemBufferSize.setOnClickListener {
            val availableOptions = settingsViewModel.targetBufferOptions
            val optionsLabels = availableOptions.map {
                if (it == SettingsRepository.BUFFER_SIZE_AUTO) getString(R.string.buffer_size_auto) else getString(R.string.buffer_size_mb, it)
            }
            val currentVal = settingsViewModel.targetBufferMB.value ?: SettingsRepository.BUFFER_SIZE_AUTO
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
        settingsViewModel.isAutoPipEnabled.observe(this) { switchAutoPip.isChecked = it }
        settingsViewModel.isAutoSkipIntroEnabled.observe(this) { switchAutoSkipIntro.isChecked = it }
        settingsViewModel.videoDecoderPriority.observe(this) { mode ->
            textDecoderValue.text = settingsViewModel.getDecoderValueString(mode)
            textDecoderDesc.setText(settingsViewModel.getDecoderDescResId(mode, video = true))
        }
        settingsViewModel.audioDecoderPriority.observe(this) { mode ->
            findViewById<TextView>(R.id.text_audio_decoder_value).text = settingsViewModel.getDecoderValueString(mode)
            findViewById<TextView>(R.id.text_audio_decoder_desc).setText(settingsViewModel.getDecoderDescResId(mode, video = false))
        }
        settingsViewModel.isTunnelingEnabled.observe(this) { switchTunneling.isChecked = it }
        settingsViewModel.isHdr10PlusEnabled.observe(this) { switchHdr10Plus.isChecked = it }
        settingsViewModel.isHdAudioIecEnabled.observe(this) { switchHdAudioIec.isChecked = it }
        settingsViewModel.dvMode.observe(this) { mode ->
            textDvValue.text = when (mode) {
                SettingsRepository.DV_MODE_OFF -> getString(R.string.dv_mode_off)
                SettingsRepository.DV_MODE_AUTO -> getString(R.string.dv_mode_auto)
                SettingsRepository.DV_MODE_CONVERT -> getString(R.string.dv_mode_convert)
                else -> getString(R.string.dv_mode_always)
            }
        }
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
        settingsViewModel.defaultPlaybackSpeed.observe(this) { textDefaultSpeedValue.text = PlaybackSpeed.label(it) }
        settingsViewModel.resumeModeAction.observe(this) { action ->
            textResumeActionValue.text = when (action) {
                SettingsRepository.RESUME_NEVER -> getString(R.string.resume_mode_never)
                SettingsRepository.RESUME_ALWAYS -> getString(R.string.resume_mode_always)
                else -> getString(R.string.resume_mode_ask)
            }
        }

        settingsViewModel.externalPositionMode.observe(this) { mode ->
            textExternalPositionValue.text = if (mode == SettingsRepository.EXTERNAL_POSITION_ASK)
                getString(R.string.external_position_ask) else getString(R.string.external_position_apply)
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

        settingsViewModel.isVolumeBrightnessGestures.observe(this) { switchGestureVolume.isChecked = it }
        settingsViewModel.horizontalSwipeAction.observe(this) { action ->
            textSwipeActionValue.text = when (action) {
                SettingsRepository.SWIPE_SEEK -> getString(R.string.swipe_action_seek)
                SettingsRepository.SWIPE_PLAYLIST -> getString(R.string.swipe_action_playlist)
                SettingsRepository.SWIPE_EXIT_NEXT -> getString(R.string.swipe_action_exit_next)
                else -> getString(R.string.swipe_action_none)
            }
        }
        settingsViewModel.isBackButtonEnabled.observe(this) { switchBackButton.isChecked = it }
        settingsViewModel.hiddenControlButtons.observe(this) { hidden ->
            val total = SettingsRepository.HIDEABLE_BUTTONS.size
            val visible = SettingsRepository.HIDEABLE_BUTTONS.count { it !in hidden }
            textControlButtonsValue.text = getString(R.string.pref_control_buttons_value, visible, total)
        }

        settingsViewModel.isRememberZoomEnabled.observe(this) { switchRememberZoom.isChecked = it }
        settingsViewModel.isShowPlaylistIndexEnabled.observe(this) { switchShowIndex.isChecked = it }
        settingsViewModel.isConfirmExitEnabled.observe(this) { switchConfirmExit.isChecked = it }
        settingsViewModel.clockTransparency.observe(this) { transparency ->
            textShowClockValue.text = if (transparency == SettingsRepository.CLOCK_OFF) getString(R.string.track_off) else getString(R.string.percentage_int_format, transparency)
        }
        settingsViewModel.pauseDimLevel.observe(this) { level ->
            textPauseDimValue.text = if (level == 0) getString(R.string.track_off) else "$level%"
        }


        settingsViewModel.isLiveModeEnabled.observe(this) { switchLiveMode.isChecked = it }
        settingsViewModel.default3dMode.observe(this) { textDefault3dModeValue.text = default3dModeLabel(it) }
        settingsViewModel.subtitleAutoMode.observe(this) { mode ->
            textSubtitleAutoValue.text = when (mode) {
                SettingsRepository.SUB_AUTO_OFF -> getString(R.string.sub_auto_off)
                SettingsRepository.SUB_AUTO_FORCED -> getString(R.string.sub_auto_forced)
                SettingsRepository.SUB_AUTO_FULL -> getString(R.string.sub_auto_full)
                else -> getString(R.string.sub_auto_default)
            }
        }
        settingsViewModel.audioTrackMemoryMode.observe(this) { mode ->
            textRememberAudioValue.text = when (mode) {
                SettingsRepository.AUDIO_MEMORY_PLAYLIST -> getString(R.string.audio_memory_playlist)
                SettingsRepository.AUDIO_MEMORY_ALL -> getString(R.string.audio_memory_all)
                else -> getString(R.string.audio_memory_off)
            }
            // Список приоритетов нужен только для запоминания между разными видео
            itemAudioPriority.isVisible = mode == SettingsRepository.AUDIO_MEMORY_ALL
        }
        settingsViewModel.audioTrackStats.observe(this) { stats ->
            textAudioPriorityValue.text = stats.size.toString()
        }
        settingsViewModel.isShowInfoOnTrackChange.observe(this) { switchTrackChangeInfo.isChecked = it }
        settingsViewModel.bufferDisplayMode.observe(this) { mode ->
            textBufferDisplayValue.text = when (mode) {
                SettingsRepository.BUFFER_DISPLAY_PANEL -> getString(R.string.buffer_panel)
                SettingsRepository.BUFFER_DISPLAY_CLOCK -> getString(R.string.buffer_clock)
                else -> getString(R.string.buffer_off)
            }
        }
        settingsViewModel.targetBufferMB.observe(this) { size ->
            textBufferSizeValue.text = if (size == SettingsRepository.BUFFER_SIZE_AUTO) getString(R.string.buffer_size_auto) else getString(R.string.buffer_size_mb, size)
        }

        updateViewModel.updateInfo.observe(this) { updateUpdateUI() }
        updateViewModel.isCheckingUpdates.observe(this) { checking ->
            if (checking) textUpdateDesc.text = getString(R.string.update_btn_checking) else updateUpdateUI()
        }
        updateViewModel.downloadProgress.observe(this) { updateDownloadProgress(it) }
    }

    /**
     * Список приоритетов аудиодорожек: выбор дорожки открывает действия (выше, ниже, в начало, удалить).
     */
    private fun showAudioPriorityDialog() {
        val stats = settingsViewModel.audioTrackStats.value.orEmpty()
        if (stats.isEmpty()) {
            Toast.makeText(this, R.string.audio_priority_empty, Toast.LENGTH_LONG).show()
            return
        }
        val items = stats.mapIndexed { i, stat -> getString(R.string.audio_priority_item, i + 1, stat.label, stat.count) }
        showSingleChoiceDialog(getString(R.string.pref_audio_priority), items, -1) { index ->
            val actions = listOf(
                getString(R.string.audio_priority_top),
                getString(R.string.audio_priority_up),
                getString(R.string.audio_priority_down),
                getString(R.string.audio_priority_remove)
            )
            showSingleChoiceDialog(stats[index].label, actions, -1) { action ->
                when (action) {
                    0 -> settingsViewModel.moveAudioTrackStat(index, 0)
                    1 -> settingsViewModel.moveAudioTrackStat(index, index - 1)
                    2 -> settingsViewModel.moveAudioTrackStat(index, index + 1)
                    3 -> settingsViewModel.removeAudioTrackStat(index)
                }
                // Возвращаемся к обновленному списку
                showAudioPriorityDialog()
            }
        }
    }

    private fun startCalibration(mode: String) {
        startActivity(Intent(this, CalibrationWizardActivity::class.java).putExtra(CalibrationWizardActivity.EXTRA_MODE, mode))
    }

    /** Открытые компоненты и их лицензии. Пункт открывает страницу проекта (если есть браузер). */
    private fun showLicensesDialog() {
        val dialog = Dialog(this, R.style.Theme_App_Dialog)
        dialog.setContentView(R.layout.dialog_list_choice)
        dialog.findViewById<TextView>(R.id.dialog_title).text = getString(R.string.pref_licenses)
        val recycler = dialog.findViewById<RecyclerView>(R.id.dialog_recycler_view)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = SideMenuAdapter { item -> openLink(item.data as String) }
        recycler.adapter = adapter
        dialog.show()
        adapter.submitList(OpenSourceLicenses.components.mapIndexed { i, c ->
            // Что используется (если не весь компонент), затем лицензия и ссылка
            val license = "${c.license} · ${c.url}"
            MenuItem(id = i.toString(), title = c.name, description = c.parts?.let { "$it\n$license" } ?: license, data = c.url)
        }) {
            recycler.post { recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            // На ТВ браузера часто нет: ссылка и так видна в описании пункта
            Toast.makeText(this, getString(R.string.link_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    /** Сброс всех настроек. Фокус в подтверждении стоит на "Отмена" (первый пункт). */
    private fun showResetSettingsDialog() {
        val options = listOf(getString(R.string.cancel), getString(R.string.reset_settings_confirm))
        showSingleChoiceDialog(getString(R.string.reset_settings_title), options, -1) { index ->
            if (index != 1) return@showSingleChoiceDialog
            val repository = SettingsRepository.getInstance(this)
            val customLanguage = repository.getAppLanguage() != SettingsRepository.LANG_SYSTEM_DEFAULT
            repository.resetAllSettings()
            Toast.makeText(this, R.string.reset_settings_done, Toast.LENGTH_SHORT).show()
            // Открываем экран заново: пункты и ViewModel читают уже сброшенные значения
            startActivity(Intent(this, GlobalSettingsActivity::class.java).putExtra("FOCUS_ITEM_ID", R.id.item_reset_settings))
            finish()
            if (customLanguage) applyAppLocale(SettingsRepository.LANG_SYSTEM_DEFAULT)
        }
    }

    // Сброс калибровки к значениям приложения: VR, анаглиф или все
    private fun showResetCalibrationDialog() {
        val options = listOf(
            getString(R.string.reset_calibration_vr),
            getString(R.string.reset_calibration_anaglyph),
            getString(R.string.reset_calibration_all)
        )
        showSingleChoiceDialog(getString(R.string.pref_reset_calibration), options, -1) { index ->
            val repository = SettingsRepository.getInstance(this)
            if (index == 0 || index == 2) repository.resetVrCalibration()
            if (index == 1 || index == 2) repository.resetAnaglyphCalibration()
            Toast.makeText(this, R.string.reset_calibration_done, Toast.LENGTH_SHORT).show()
        }
    }

    // Варианты 3D режима по умолчанию: null - "Не обрабатывать"
    private val default3dModes = listOf(
        null, StereoOutputMode.ANAGLYPH, StereoOutputMode.CARDBOARD_VR,
        StereoOutputMode.LEFT_ONLY, StereoOutputMode.RIGHT_ONLY
    )

    private fun default3dModeLabel(mode: StereoOutputMode?): String = getString(
        when (mode) {
            null -> R.string.default_3d_none
            StereoOutputMode.ANAGLYPH -> R.string.output_mode_anaglyph
            StereoOutputMode.CARDBOARD_VR -> R.string.output_mode_vr
            StereoOutputMode.LEFT_ONLY -> R.string.default_3d_left
            StereoOutputMode.RIGHT_ONLY -> R.string.default_3d_right
        }
    )

    private fun getControlButtonLabel(key: String): String = when (key) {
        SettingsRepository.BUTTON_SPEED -> getString(R.string.playback_speed)
        SettingsRepository.BUTTON_RESIZE -> getString(R.string.playback_zoom)
        SettingsRepository.BUTTON_PIP -> getString(R.string.picture_in_picture)
        SettingsRepository.BUTTON_QUALITY -> getString(R.string.button_label_quality)
        SettingsRepository.BUTTON_AUDIO -> getString(R.string.button_label_audio)
        SettingsRepository.BUTTON_SUBTITLES -> getString(R.string.button_label_subtitles)
        else -> key
    }

    private fun showSingleChoiceDialog(title: String, items: List<String>, currentIndex: Int, onSelected: (Int) -> Unit) {
        ChoiceDialog.show(this, title, items, currentIndex, onSelected)
    }

    private fun handleInitialFocus(focusItemId: Int) {
        val viewToFocus = if (focusItemId != 0) findViewById<View>(focusItemId) else null
        if (viewToFocus != null) {
            // Открываем категорию, в которой находится пункт
            val categoryIndex = categories.indexOfFirst { isDescendant(viewToFocus, findViewById(it.sectionId)) }
            if (categoryIndex != -1) selectCategory(categoryIndex)
            viewToFocus.post {
                // requestFocus сам прокручивает список к пункту
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
            categoryViews[selectedCategory].post { categoryViews[selectedCategory].requestFocus() }
        }
    }

    private fun isDescendant(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
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

    private companion object {
        const val STATE_CATEGORY = "settings_category"
    }

    override fun onDestroy() {
        super.onDestroy()
        updateDialog?.dismiss()
    }
}