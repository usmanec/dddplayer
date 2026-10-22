package top.rootu.dddplayer.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Возврат видео из окна PiP, когда приложение открывают снаружи (лаунчер, "Недавние").
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
        if (startedScreens == 0) PlayerActivity.restorePictureInPicture()
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
