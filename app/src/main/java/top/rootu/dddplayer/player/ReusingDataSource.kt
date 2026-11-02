package top.rootu.dddplayer.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Сетевой источник, который не открывает новое соединение на каждый прыжок экстрактора.
 * Каждое соединение с облаком (TorBox) - это 0.3-0.8 с ожидания первого байта, а экстракторы прыгают часто:
 * MKV и MP4 с индексом в конце читают начало, индекс в конце и возвращаются к началу; MP4 с десятками дорожек
 * субтитров читает их пустые 2-байтовые сэмплы по времени, а лежат они в файле в обратном порядке - каждый шаг
 * назад на 2 байта был новым запросом (East of Eden: 32 запроса подряд, 26 с без видео).
 * Здесь соединение после close() остается открытым (паркуется) на IDLE_MS: возврат к нему, шаг назад в пределах
 * последних WINDOW байт или вперед до SKIP_AHEAD байт обходятся без запроса. Новое соединение открывается
 * на BACK байт раньше запрошенного, чтобы шаги назад сразу попадали в окно.
 * Только GET без длины запроса (так читает файлы ProgressiveMediaPeriod) и только файлы с известным размером;
 * остальное (HLS с диапазонами, POST, эфир без Content-Length) - как раньше.
 * Припаркованные соединения общие для всех экземпляров (Media3 создает источник на каждый элемент плейлиста):
 * другой адрес закрывает их сразу, а если сервер не дал новое соединение (у многих IPTV одно на аккаунт, а наше
 * еще на парковке), они закрываются и запрос повторяется один раз.
 * Параллельные запросы не помогают: TorBox отдает 15 МБ/с в одно соединение, а 2-8 частей ждут самый медленный ответ.
 */
class ReusingDataSource(
    // Настройка reuse_connections (по умолчанию выключена): иначе каждый open - свой запрос, как раньше
    private val enabled: () -> Boolean = { true },
    private val factory: DataSource.Factory
) : DataSource {

    private data class Key(val uri: String, val headers: Map<String, String>)

    private class Connection(val key: Key, val upstream: DataSource, start: Long, val end: Long) {
        private val window = ByteArray(WINDOW)
        private val first = start
        var position = start // следующий байт из сети
        var lastUsed = 0L
        var reusable = true // false: ошибка чтения или конец файла

        fun windowStart() = max(first, position - WINDOW)

        fun pull(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = try {
                upstream.read(buffer, offset, length)
            } catch (e: IOException) {
                reusable = false
                throw e
            }
            if (count == C.RESULT_END_OF_INPUT) {
                reusable = false
                return count
            }
            // Последние байты - в кольцо окна
            var from = offset + max(0, count - WINDOW)
            var at = position + (from - offset)
            while (from < offset + count) {
                val index = (at % WINDOW).toInt()
                val chunk = min(offset + count - from, WINDOW - index)
                System.arraycopy(buffer, from, window, index, chunk)
                from += chunk
                at += chunk
            }
            position += count
            return count
        }

        /** Байты из окна начиная с [at] (windowStart() <= at < position). */
        fun copy(at: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            val index = (at % WINDOW).toInt()
            val count = minOf(length.toLong(), position - at, (WINDOW - index).toLong()).toInt()
            System.arraycopy(window, index, buffer, offset, count)
            return count
        }

        /** Дочитывает до [target], выбрасывая байты. false - соединение не дошло (ошибка или конец). */
        fun skipTo(target: Long): Boolean {
            if (position >= target) return true
            val scratch = ByteArray(64 * 1024)
            while (position < target) {
                if (pull(scratch, 0, min(scratch.size.toLong(), target - position).toInt()) == C.RESULT_END_OF_INPUT) return false
            }
            return true
        }
    }

    private val listeners = mutableListOf<TransferListener>()
    private var current: Connection? = null
    private var direct: DataSource? = null
    private var readAt = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        // FLAG_ALLOW_GZIP - манифесты и плейлисты (DASH, HLS): они меняются, и копия из памяти отдала бы старый
        // (эфирный MPD с Content-Length, который парсер не дочитывает до конца). Файлы видео идут без него
        if (!enabled() || dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP) || dataSpec.length != C.LENGTH_UNSET.toLong() ||
            dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || (scheme != "http" && scheme != "https")
        ) {
            val source = newUpstream()
            direct = source
            return source.open(dataSpec)
        }
        val key = Key(dataSpec.uri.toString(), dataSpec.httpRequestHeaders)
        val target = dataSpec.position
        val now = SystemClock.elapsedRealtime()
        // Другой адрес (следующий канал IPTV, следующая серия, сегмент HLS) - старые соединения сразу закрываются:
        // у многих IPTV одно соединение на аккаунт
        val (found, other) = synchronized(lock) {
            val other = parked.filter { it.key != key }.also { parked.removeAll(it) }
            parked.firstOrNull {
                target >= it.windowStart() && target <= it.position + SKIP_AHEAD && now - it.lastUsed < IDLE_MS
            }?.also { parked.remove(it) } to other
        }
        other.forEach { DataSourceUtil.closeQuietly(it.upstream) }
        val connection = found?.takeIf { reached(it, target) } ?: try {
            connect(dataSpec, key, target)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // Сервер не дал еще одно соединение (ответил кодом ошибки), а наше (голова файла) ждет на парковке:
            // закрываем и еще раз. Отмена или таймаут - не отказ сервера, их не повторяем
            if (!closeParked()) throw e
            connect(dataSpec, key, target)
        }
        current = connection
        readAt = target
        return if (connection.end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else connection.end - target
    }

    /** Припаркованное соединение дошло до [target]; иначе оно закрыто и нужен новый запрос. */
    private fun reached(connection: Connection, target: Long): Boolean {
        try {
            if (connection.skipTo(target)) return true
        } catch (e: IOException) {
            DataSourceUtil.closeQuietly(connection.upstream)
            // OkHttp заворачивает прерывание в HttpDataSourceException
            if (e is InterruptedIOException || e.cause is InterruptedIOException) throw e
            return false
        }
        DataSourceUtil.closeQuietly(connection.upstream)
        return false
    }

    private fun connect(dataSpec: DataSpec, key: Key, target: Long): Connection {
        val start = max(0L, target - BACK)
        val upstream = newUpstream()
        val length = upstream.open(dataSpec.buildUpon().setPosition(start).build())
        val end = if (length == C.LENGTH_UNSET.toLong()) length else start + length
        val connection = Connection(key, upstream, start, end)
        try {
            if (!connection.skipTo(target)) connection.reusable = false
        } catch (e: IOException) {
            DataSourceUtil.closeQuietly(upstream)
            throw e
        }
        return connection
    }

    private fun newUpstream(): DataSource = factory.createDataSource().also { s -> listeners.forEach(s::addTransferListener) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        direct?.let { return it.read(buffer, offset, length) }
        val connection = checkNotNull(current)
        if (length == 0) return 0
        val count = if (readAt < connection.position) connection.copy(readAt, buffer, offset, length)
        else connection.pull(buffer, offset, length)
        if (count != C.RESULT_END_OF_INPUT) readAt += count
        return count
    }

    override fun getUri(): Uri? = direct?.uri ?: current?.upstream?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        direct?.responseHeaders ?: current?.upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        direct?.let {
            direct = null
            it.close()
            return
        }
        val connection = current ?: return
        current = null
        // Без длины - эфир (IPTV): его не держим, данные идут и в паузе
        if (!connection.reusable || connection.end == C.LENGTH_UNSET.toLong()) {
            connection.upstream.close()
            return
        }
        connection.lastUsed = SystemClock.elapsedRealtime()
        val evicted = synchronized(lock) {
            parked.add(0, connection)
            if (parked.size > MAX_PARKED) parked.removeAt(parked.lastIndex) else null
        }
        evicted?.let { DataSourceUtil.closeQuietly(it.upstream) }
        // Source не освобождают явно: соединения, к которым не вернулись, закрывает таймер
        closer.schedule({ closeIdle() }, IDLE_MS + 100, TimeUnit.MILLISECONDS)
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val enabled: () -> Boolean = { true }
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ReusingDataSource(enabled, upstreamFactory)
    }

    companion object {
        const val WINDOW = 256 * 1024
        const val BACK = 64 * 1024L
        // ~0.07 с чтения на 15 МБ/с против 0.3-0.8 с нового запроса; на 2 МБ/с - 0.5 с, как запрос
        const val SKIP_AHEAD = 1L * 1024 * 1024
        const val IDLE_MS = 10_000L // индекс в конце скачивается и разбирается ~3 с, потом возврат к началу
        const val MAX_PARKED = 2

        private val closer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ReusingDataSource").apply { isDaemon = true }
        }

        // Все экземпляры, под lock: лимит соединений у сервера общий, а источник у каждого элемента свой
        private val parked = ArrayList<Connection>()
        private val lock = Any()

        /** Закрывает все припаркованные соединения; false - их не было. */
        internal fun closeParked(): Boolean {
            val all = synchronized(lock) { parked.toList().also { parked.clear() } }
            all.forEach { DataSourceUtil.closeQuietly(it.upstream) }
            return all.isNotEmpty()
        }

        private fun closeIdle() {
            val now = SystemClock.elapsedRealtime()
            val idle = synchronized(lock) {
                parked.filter { now - it.lastUsed >= IDLE_MS }.also { parked.removeAll(it) }
            }
            idle.forEach { DataSourceUtil.closeQuietly(it.upstream) }
        }
    }
}
