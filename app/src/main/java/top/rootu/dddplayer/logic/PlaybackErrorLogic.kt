package top.rootu.dddplayer.logic

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.ContentDataSource

/**
 * Разбор ошибок воспроизведения для экрана ошибки: причины под кодом ошибки и подсказки.
 */
object PlaybackErrorLogic {

    private const val MAX_LINE_LENGTH = 120
    private const val MAX_DEPTH = 10

    /**
     * Причины ошибки (без самой ошибки), например "IOException: read failed: EIO".
     * Обертки, чье сообщение повторяет причину глубже, пропускаются. Берутся самые глубокие [maxLines] строк:
     * там обычно настоящая причина.
     */
    fun causeLines(error: Throwable, maxLines: Int = 3): List<String> {
        val chain = mutableListOf<Throwable>()
        var cause = error.cause
        while (cause != null && chain.size < MAX_DEPTH && cause !in chain) {
            chain.add(cause)
            cause = cause.cause
        }
        val lines = chain.filterIndexed { index, item ->
            val message = item.message?.trim().orEmpty()
            // Media3 и Java часто копируют сообщение причины в обертку: оставляем только саму причину
            message.isEmpty() || chain.drop(index + 1).none { deeper ->
                val deeperMessage = deeper.message?.trim().orEmpty()
                deeperMessage.isNotEmpty() && message.contains(deeperMessage)
            }
        }.map { item ->
            val message = item.message?.trim()?.replace('\n', ' ').orEmpty()
            val line = if (message.isEmpty()) item.javaClass.simpleName else "${item.javaClass.simpleName}: $message"
            if (line.length > MAX_LINE_LENGTH) line.take(MAX_LINE_LENGTH - 3) + "..." else line
        }
        return lines.takeLast(maxLines)
    }

    /**
     * Ошибка чтения через провайдер другого приложения (content://, например файловый менеджер или SMB-клиент):
     * это приложение не смогло отдать данные файла.
     */
    fun isContentSourceError(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth++ < MAX_DEPTH) {
            if (cause is ContentDataSource.ContentDataSourceException) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * Формат не разобран (контейнер или манифест, коды 3001–3004): видео можно открыть в другом плеере.
     * Сюда попадает и Blu-ray .m2ts по ссылке без расширения: после ошибки контейнера плеер пробует HLS и получает 3002.
     */
    fun canOpenInOtherPlayer(error: PlaybackException): Boolean =
        error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
}
