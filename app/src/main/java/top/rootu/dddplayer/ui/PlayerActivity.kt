package top.rootu.dddplayer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import top.rootu.dddplayer.R
import top.rootu.dddplayer.utils.IntentUtils
import top.rootu.dddplayer.viewmodel.PlayerViewModel

@UnstableApi
class PlayerActivity : FragmentActivity() {

    private var playerFragment: PlayerFragment? = null
    private val viewModel: PlayerViewModel by viewModels()
    private var shouldReturnResult = false
    private var isCompleted = false

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверяем, как запущено приложение
        if (isLaunchedFromLauncher(intent)) {
            // Запуск из лаунчера -> Идем в настройки
            val settingsIntent = Intent(this, GlobalSettingsActivity::class.java)
            startActivity(settingsIntent)
            finish()
            return
        }

        // Держим экран включенным
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Настройка полноэкранного режима (поддержка вырезов/челок)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // --- HDR / WIDE COLOR GAMUT SUPPORT ---
        // Сообщаем системе, что мы хотим использовать расширенный цветовой диапазон.
        // Это заставит экран переключиться в режим HDR, если контент и дисплей это поддерживают.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Используем WIDE_COLOR_GAMUT, так как он покрывает большинство сценариев
            // и меньше ломает UI, чем принудительный HDR.
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        // --------------------------------------

        setContentView(R.layout.player_activity)

        // Скрываем системные панели
        hideSystemUI()

        viewModel.playbackEnded.observe(this) { ended ->
            if (ended && shouldReturnResult) {
                isCompleted = true
                finish() // Закрываем активити
            }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)

            val fragment = PlayerFragment.newInstance()
            playerFragment = fragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commitNow()
        } else {
            playerFragment =
                supportFragmentManager.findFragmentById(R.id.container) as? PlayerFragment
        }
    }

    private fun isLaunchedFromLauncher(intent: Intent): Boolean {
        val action = intent.action
        val categories = intent.categories
        val data = intent.data

        // Если Action MAIN и Category LAUNCHER (или LEANBACK_LAUNCHER) и НЕТ данных (Uri)
        return (Intent.ACTION_MAIN == action) &&
                (categories?.contains(Intent.CATEGORY_LAUNCHER) == true ||
                        categories?.contains(Intent.CATEGORY_LEANBACK_LAUNCHER) == true) &&
                data == null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        shouldReturnResult = intent.getBooleanExtra("return_result", false)

        val (playlist, startIndex) = IntentUtils.parseIntent(intent)
        if (playlist.isNotEmpty()) {
            viewModel.loadPlaylist(playlist, startIndex)
        } else {
            // Дефолтное видео для теста
//            val defaultUri = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
//            val defaultUri = "http://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
            val defaultUri = "http://epg.rootu.top/tmp/3D/bbb_sunflower_1080p_30fps_stereo_abl.mp4"
            viewModel.loadPlaylist(
                listOf(top.rootu.dddplayer.model.MediaItem(defaultUri.toUri())),
                0
            )
        }
    }

    private fun hideSystemUI() {
        // Используем WindowCompat для совместимости
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    /**
     * Центральный обработчик всех нажатий кнопок.
     * Это самый надежный способ управлять UI плеера.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Передаем событие во фрагмент для обработки.
        // Если фрагмент его обработал (вернул true), то мы прекращаем дальнейшее распространение.
        if (playerFragment?.handleKeyEvent(event) == true) {
            return true
        }
        // В противном случае, используем стандартное поведение.
        return super.dispatchKeyEvent(event)
    }

    override fun finish() {
        if (shouldReturnResult) {
            val resultIntent = Intent("top.rootu.dddplayer.intent.result.VIEW")

            // Возвращаем URI текущего видео (полезно, если это был плейлист)
            resultIntent.data = viewModel.player.currentMediaItem?.localConfiguration?.uri

            val duration = viewModel.player.duration
            val position = if (isCompleted) duration else viewModel.player.currentPosition

            resultIntent.putExtra("position", position)
            resultIntent.putExtra("duration", duration)

            // Сообщаем, закончилось ли видео само ("completion") или закрыл юзер ("user")
            resultIntent.putExtra("end_by", if (isCompleted) "completion" else "user")

            setResult(RESULT_OK, resultIntent)
        }
        super.finish()
    }
}