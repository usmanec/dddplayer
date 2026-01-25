package top.rootu.dddplayer.utils

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.ui.CrashActivity
import kotlin.system.exitProcess

object CrashHandler {

    fun init(application: Application) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleUncaughtException(application, throwable)
            } catch (e: Exception) {
                // Если не удалось показать экран ошибки, отдаем системе
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun handleUncaughtException(application: Application, throwable: Throwable) {
        val errorReport = StringBuilder()

        errorReport.append("--- Device Info ---\n")
        errorReport.append("Model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        errorReport.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        errorReport.append("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n")

        errorReport.append("--- Crash Log ---\n")
        errorReport.append(throwable.stackTraceToString())

        val intent = Intent(application, CrashActivity::class.java).apply {
            putExtra("errorDetails", errorReport.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        application.startActivity(intent)

        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}