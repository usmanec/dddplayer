package top.rootu.dddplayer.utils

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Последние строки logcat приложения для диагностики: без root и разрешений приложение видит только свои записи.
 * Так в отчет попадает то, что было перед проблемой (зависание, ошибка источника), даже без падения.
 */
object AppLogCollector {

    private const val TAG = "AppLogCollector"
    const val MAX_LINES = 1000

    fun read(maxLines: Int = MAX_LINES): List<String> = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", maxLines.toString())
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor(2, TimeUnit.SECONDS)
        process.destroy()
        lines.takeLast(maxLines)
    } catch (e: Exception) {
        Log.w(TAG, "logcat failed", e)
        listOf("logcat: ${e.javaClass.simpleName}: ${e.message}")
    }
}
