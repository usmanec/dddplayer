package top.rootu.dddplayer.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Переподключение HTTP после простоя. Пока буфер полный (пауза), загрузчик не читает данные,
 * но держит соединение открытым. За несколько минут сервер (TorrServer) или роутер может его молча
 * закрыть, и после паузы плеер ждал бы данных до тайм-аута чтения (120 с).
 *
 * Если между чтениями прошло больше [idleReconnectMs], соединение перед следующим чтением
 * открывается заново с того же байта (Range). Уже загруженный буфер плеера не теряется.
 * Все методы вызывает один поток загрузчика, поэтому переоткрытие безопасно.
 */
class IdleReconnectDataSource(
    private val upstream: DataSource,
    private val idleReconnectMs: Long = IDLE_RECONNECT_MS
) : DataSource {

    private var dataSpec: DataSpec? = null
    private var bytesRead = 0L
    private var lastReadMs = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        val length = upstream.open(dataSpec)
        lastReadMs = SystemClock.elapsedRealtime()
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        maybeReconnect()
        val count = upstream.read(buffer, offset, length)
        if (count > 0) bytesRead += count
        lastReadMs = SystemClock.elapsedRealtime()
        return count
    }

    private fun maybeReconnect() {
        val spec = dataSpec ?: return
        val idleMs = SystemClock.elapsedRealtime() - lastReadMs
        if (idleMs < idleReconnectMs || !isHttp(spec.uri)) return
        // Все уже прочитано - переподключаться незачем
        if (spec.length != C.LENGTH_UNSET.toLong() && bytesRead >= spec.length) return
        Log.i(TAG, "Idle for ${idleMs / 1000} s, reconnecting at +$bytesRead bytes")
        upstream.close()
        // Ошибка открытия уходит в плеер как обычная ошибка загрузки (повтор с той же позиции)
        upstream.open(spec.subrange(bytesRead))
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        dataSpec = null
        upstream.close()
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = IdleReconnectDataSource(upstreamFactory.createDataSource())
    }

    private companion object {
        const val TAG = "IdleReconnect"
        // Дольше обычных пауз загрузчика между порциями буфера, но меньше времени, за которое рвут соединение
        const val IDLE_RECONNECT_MS = 60_000L

        fun isHttp(uri: Uri): Boolean = uri.scheme.let { it == "http" || it == "https" }
    }
}
