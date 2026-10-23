package top.rootu.dddplayer.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Возврат видео из окна PiP или свернутого плеера, когда приложение открывают снаружи (лаунчер, "Недавние").
 * Лаунчер поднимает прежнюю задачу приложения (стартовый экран, настройки), а плеер в PiP живет
 * в отдельной задаче - без этого видео осталось бы в углу. Переходы между своими экранами не считаются:
 * новый экран запускается раньше, чем останавливается прежний.
 */
@OptIn(UnstableApi::class)
class PipReturnCallbacks : Application.ActivityLifecycleCallbacks {

    // Запущенные экраны приложения, кроме плеера
    private var startedScreens = 0

    override fun onActivityStarted(activity: Activity) {
        if (activity is PlayerActivity) return
        // Плеер, свернутый кнопкой "Домой", тоже возвращаем. Если экран открыт из плеера (настройки),
        // плеер в этот момент еще запущен (останавливается после старта нового экрана) - его не трогаем
        if (startedScreens == 0) PlayerActivity.restorePlayer(activity)
        startedScreens++
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is PlayerActivity) return
        startedScreens = (startedScreens - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
