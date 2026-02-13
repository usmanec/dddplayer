package top.rootu.dddplayer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.utils.IntentUtils
import top.rootu.dddplayer.viewmodel.PlayerViewModel

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private var playerFragment: PlayerFragment? = null
    private val viewModel: PlayerViewModel by viewModels()
    private var shouldReturnResult = false
    private var isCompleted = false
    // Сохраняем Intent, чтобы обработать его после получения разрешения
    private var pendingIntent: Intent? = null

    // Регистрация коллбека для запроса разрешений
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Разрешение получено, обрабатываем отложенный интент
            pendingIntent?.let { handleIntent(it) }
        } else {
            // Разрешение не дано.
            // Для content:// это может быть не критично, пробуем открыть так.
            // Для file:// это фатально, но ExoPlayer сам выдаст ошибку, которую мы покажем.
            pendingIntent?.let { handleIntent(it) }
            Toast.makeText(this, "Storage permission denied. Some files may not play.", Toast.LENGTH_LONG).show()
        }
        pendingIntent = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверяем, как запущено приложение
        if (!BuildConfig.DEBUG && isLaunchedFromLauncher(intent)) {
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
            checkPermissionsAndHandleIntent(intent)

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
        // Также проверяем разрешения при новом интенте
        checkPermissionsAndHandleIntent(intent)
    }

    private fun checkPermissionsAndHandleIntent(intent: Intent) {
        val uri = intent.data

        // Если URI нет или это http/https, разрешение на файлы не нужно
        if (uri == null || uri.scheme == "http" || uri.scheme == "https") {
            handleIntent(intent)
            return
        }

        // Определяем нужное разрешение в зависимости от версии Android
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+): Запрашиваем доступ к видео
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            // Android 6-12: Старое разрешение
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, permissionToRequest) == PackageManager.PERMISSION_GRANTED) {
                handleIntent(intent)
            } else {
                pendingIntent = intent
                requestPermissionLauncher.launch(permissionToRequest)
            }
        } else {
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        shouldReturnResult = intent.getBooleanExtra("return_result", false)

        val (playlist, startIndex) = IntentUtils.parseIntent(this, intent)
        when {
            playlist.isNotEmpty() -> {
                viewModel.loadPlaylist(playlist, startIndex)
            }
            BuildConfig.DEBUG -> {
                // Дефолтное видео для теста (только в DEBUG)
                val defaultUri = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
//                val defaultUri = "http://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
                viewModel.loadPlaylist(
                    listOf(top.rootu.dddplayer.model.MediaItem(defaultUri.toUri())),
                    0
                )
            }
            else -> {
                // В релизе, если нет данных, ничего не делаем.
            }
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
            resultIntent.data = viewModel.player?.currentMediaItem?.localConfiguration?.uri

            val duration = viewModel.player?.duration
            val position = if (isCompleted) duration else viewModel.player?.currentPosition

            resultIntent.putExtra("position", position)
            resultIntent.putExtra("duration", duration)

            // Сообщаем, закончилось ли видео само ("completion") или закрыл юзер ("user")
            resultIntent.putExtra("end_by", if (isCompleted) "completion" else "user")

            setResult(RESULT_OK, resultIntent)
        }
        super.finish()
    }
}