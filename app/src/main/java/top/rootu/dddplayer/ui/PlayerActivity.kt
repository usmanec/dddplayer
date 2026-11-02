package top.rootu.dddplayer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.utils.IntentUtils
import top.rootu.dddplayer.viewmodel.PlayerViewModel
import java.lang.ref.WeakReference

private const val ACTION_PIP_PLAY_PAUSE = "top.rootu.dddplayer.action.PIP_PLAY_PAUSE"

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        // Лог жизненного цикла PiP (adb logcat -s PlayerPip): задачи, служебные запуски, кто закрывает плеер
        private const val PIP_TAG = "PlayerPip"

        // Пометка интента, которым разворачиваем окно PiP (не новое видео)
        private const val EXTRA_RESTORE_PIP = "top.rootu.dddplayer.extra.RESTORE_PIP"

        // Плеер в окне PiP. При входе в PiP система переносит его в отдельную задачу,
        // поэтому новый запуск видео создает второй плеер, а открытие приложения не возвращает в видео
        private var pipActivity: WeakReference<PlayerActivity>? = null

        private fun activePipPlayer(): PlayerActivity? = pipActivity?.get()?.takeIf {
            !it.isFinishing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && it.isInPictureInPictureMode
        }

        /**
         * Разворачивает окно PiP на весь экран: повторный запуск активити в PiP система (SystemUI)
         * обрабатывает как возврат из PiP. @return false - окна PiP нет
         */
        fun restorePictureInPicture(): Boolean {
            val pip = activePipPlayer()
            Log.i(PIP_TAG, "restore: pip=${pip?.hashCode()} task=${pip?.taskId}")
            pip ?: return false
            pip.startActivity(
                Intent(pip, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(EXTRA_RESTORE_PIP, true)
            )
            return true
        }
    }

    private var playerFragment: PlayerFragment? = null
    private val viewModel: PlayerViewModel by viewModels()
    private var shouldReturnResult = false
    // Служебный запуск (иконка лаунчера, разворачивание PiP): сразу закрывается, плеер не создается
    private var isTrampoline = false
    private var isCompleted = false
    // Сохраняем Intent, чтобы обработать его после получения разрешения
    private var pendingIntent: Intent? = null
    private var pipPlayer: Player? = null
    private val pipListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // Параметры PiP зависят только от состояния воспроизведения и размера видео
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_VIDEO_SIZE_CHANGED,
                    Player.EVENT_PLAYER_ERROR,
                    Player.EVENT_MEDIA_ITEM_TRANSITION
                )
            ) {
                updatePictureInPictureParams()
            }
        }
    }

    // Последняя область видео, переданная в PiP (чтобы не обновлять параметры на каждый layout)
    private val lastPipSourceRect = Rect()

    private var pipReceiverRegistered = false

    // Кнопка "Пауза/Воспроизведение" в окне PiP
    private val pipControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_PLAY_PAUSE) viewModel.togglePlayPause()
        }
    }

    val supportsPictureInPicture: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun canEnterPictureInPicture(): Boolean {
        val player = viewModel.player ?: return false
        return supportsPictureInPicture && !isFinishing && player.currentMediaItem != null &&
            player.playerError == null && player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildPictureInPictureParams(): PictureInPictureParams {
        val stereoView = findViewById<View>(R.id.gl_surface_view)
        val frame = if (stereoView?.visibility == View.VISIBLE) stereoView
            else findViewById<View>(R.id.aspect_ratio_frame)
        val rect = Rect()
        val builder = PictureInPictureParams.Builder()
        if (frame != null && frame.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
            val ratio = (rect.width().toFloat() / rect.height()).coerceIn(0.42f, 2.39f)
            builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            builder.setSourceRectHint(rect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(shouldAutoEnterPictureInPicture())
        }
        builder.setActions(buildPictureInPictureActions())
        return builder.build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildPictureInPictureActions(): List<RemoteAction> {
        val isPlaying = viewModel.player?.playWhenReady == true
        val title = getString(if (isPlaying) R.string.pip_action_pause else R.string.pip_action_play)
        val icon = Icon.createWithResource(this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        val intent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return listOf(RemoteAction(icon, title, title, intent))
    }

    private fun updatePictureInPictureParams() {
        // У служебного запуска нет плеера: не создаем ради параметров PiP ViewModel (а с ней и плеер)
        if (isTrampoline) return
        if (!supportsPictureInPicture || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInPictureInPictureMode) {
            // В окне PiP обновляем только кнопки (параметры объединяются с прежними)
            setPictureInPictureParams(
                PictureInPictureParams.Builder().setActions(buildPictureInPictureActions()).build()
            )
        } else {
            setPictureInPictureParams(buildPictureInPictureParams())
        }
    }

    private fun onVideoLayoutChanged() {
        val stereoView = findViewById<View>(R.id.gl_surface_view)
        val frame = if (stereoView?.visibility == View.VISIBLE) stereoView
            else findViewById<View>(R.id.aspect_ratio_frame)
        val rect = Rect()
        if (frame == null || !frame.getGlobalVisibleRect(rect) || rect == lastPipSourceRect) return
        lastPipSourceRect.set(rect)
        updatePictureInPictureParams()
    }

    fun enterPlayerPictureInPicture(showError: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode) return
        val entered = if (canEnterPictureInPicture()) {
            try {
                enterPictureInPictureMode(buildPictureInPictureParams())
            } catch (_: IllegalStateException) {
                false
            }
        } else false
        if (!entered && showError) {
            Toast.makeText(applicationContext, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && shouldAutoEnterPictureInPicture()) {
            enterPlayerPictureInPicture()
        }
    }

    private fun shouldAutoEnterPictureInPicture(): Boolean =
        SettingsRepository.getInstance(applicationContext).isAutoPipEnabled() &&
            canEnterPictureInPicture() && viewModel.player?.playWhenReady == true

    override fun onResume() {
        super.onResume()
        updatePictureInPictureParams()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Размер окна при выходе из PiP (видео могло остаться маленьким)
        Log.i(PIP_TAG, "configChanged ${describe()} screen=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp")
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        Log.i(PIP_TAG, "pipModeChanged=$inPip ${describe()} screen=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp")
        if (isTrampoline) return
        if (inPip) pipActivity = WeakReference(this)
        else if (pipActivity?.get() === this) pipActivity = null
        playerFragment?.setPictureInPictureMode(inPip)
        if (!inPip) updatePictureInPictureParams()
    }

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
            Toast.makeText(applicationContext, "Storage permission denied. Some files may not play.", Toast.LENGTH_LONG).show()
        }
        pendingIntent = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(PIP_TAG, "onCreate ${describe()} restored=${savedInstanceState != null} intent=$intent")

        // Разворачивание PiP создало новый экземпляр вместо подъема прежнего: прежний плеер не трогаем
        if (intent.getBooleanExtra(EXTRA_RESTORE_PIP, false)) {
            isTrampoline = true
            finish()
            return
        }

        // Запуск из лаунчера -> стартовый экран (история, плейлисты, хранилища, вход в настройки)
        if (isLaunchedFromLauncher(intent)) {
            isTrampoline = true
            // Видео в окне PiP: возвращаем его на весь экран вместо стартового экрана
            if (!restorePictureInPicture()) startActivity(Intent(this, FileBrowserActivity::class.java))
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

        viewModel.closeRequested.observe(this) { event ->
            if (event.getContentIfNotHandled() == null) return@observe
            // В окне PiP закрываем и его задачу, иначе просто выходим из плеера
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) closeFromPictureInPicture(-1)
            else finish()
        }

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
        viewModel.playerRecreatedEvent.observe(this) { player ->
            pipPlayer?.removeListener(pipListener)
            pipPlayer = player
            player.addListener(pipListener)
            updatePictureInPictureParams()
        }
        pipPlayer = viewModel.player
        pipPlayer?.addListener(pipListener)
        if (supportsPictureInPicture) {
            ContextCompat.registerReceiver(
                this, pipControlReceiver, IntentFilter(ACTION_PIP_PLAY_PAUSE), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            pipReceiverRegistered = true
        }
        listOf(R.id.aspect_ratio_frame, R.id.gl_surface_view).forEach { id ->
            findViewById<View>(id)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                onVideoLayoutChanged()
            }
        }
        updatePictureInPictureParams()
    }

    override fun onDestroy() {
        Log.i(PIP_TAG, "onDestroy ${describe()} finishing=$isFinishing")
        if (pipActivity?.get() === this) pipActivity = null
        if (pipReceiverRegistered) {
            unregisterReceiver(pipControlReceiver)
            pipReceiverRegistered = false
        }
        pipPlayer?.removeListener(pipListener)
        pipPlayer = null
        super.onDestroy()
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
        Log.i(PIP_TAG, "onNewIntent ${describe()} intent=$intent")
        // Разворачивание окна PiP: видео то же, интент не меняем
        if (intent.getBooleanExtra(EXTRA_RESTORE_PIP, false)) return
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

        // История "Продолжить просмотр" ведется только для видео со стартового экрана
        viewModel.setHistorySource(IntentUtils.parseHistorySource(intent))

        if (playlist.isNotEmpty()) {
            // Новое видео при открытом окне PiP: старый плеер закрываем, а не оставляем висеть вторым.
            // Только когда видео действительно пришло: служебный запуск окно PiP не закрывает
            activePipPlayer()?.takeIf { it !== this }?.closeFromPictureInPicture(taskId)
            viewModel.setAudioLanguages(IntentUtils.parseAudioLanguages(intent))
            viewModel.setTitleTracks(IntentUtils.parseTitleTracks(intent))
            viewModel.loadMedia(playlist, startIndex, forceM3uProbe = IntentUtils.isForceM3u(intent))
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            return super.dispatchKeyEvent(event)
        }
        // Передаем событие во фрагмент для обработки.
        // Если фрагмент его обработал (вернул true), то мы прекращаем дальнейшее распространение.
        if (playerFragment?.handleKeyEvent(event) == true) {
            return true
        }
        // В противном случае, используем стандартное поведение.
        return super.dispatchKeyEvent(event)
    }

    override fun finish() {
        // Стек показывает, кто закрыл плеер
        Log.i(PIP_TAG, "finish ${describe()}", Throwable("finish caller"))
        // У служебного запуска нет плеера: не создаем ViewModel ради сохранения
        if (!isTrampoline) prepareFinish()
        super.finish()
    }

    /**
     * Закрывает плеер в окне PiP вместе с его задачей (иначе в "Недавних" остается пустая).
     * @param newPlayerTaskId задача нового плеера: если она та же, закрываем только старый плеер
     */
    private fun closeFromPictureInPicture(newPlayerTaskId: Int) {
        if (isFinishing) return
        Log.i(PIP_TAG, "closeFromPictureInPicture ${describe()} newTask=$newPlayerTaskId", Throwable("close caller"))
        if (newPlayerTaskId == taskId) {
            finish()
        } else {
            prepareFinish()
            finishAndRemoveTask()
        }
    }

    private fun describe(): String {
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        return "act=${hashCode()} task=$taskId pip=$inPip trampoline=$isTrampoline"
    }

    /** Сохраняет настройки видео и готовит результат для вызвавшего приложения. */
    private fun prepareFinish() {
        viewModel.saveCurrentSettings()
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

            // Дорожки и скорость: приложение может запомнить их для фильма/сериала и передать в следующий раз
            val tracks = viewModel.currentTitleTracks()
            tracks.audio?.let { resultIntent.putExtra(IntentUtils.EXTRA_AUDIO_TRACK, it) }
            tracks.subtitle?.let { resultIntent.putExtra(IntentUtils.EXTRA_SUBTITLE_TRACK, it) }
            tracks.speed?.let { resultIntent.putExtra(IntentUtils.EXTRA_SPEED, it) }

            setResult(RESULT_OK, resultIntent)
        }
    }
}