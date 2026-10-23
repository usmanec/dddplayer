package top.rootu.dddplayer.player

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Сокеты с TCP keepalive для OkHttp. Пока плеер на паузе, соединение простаивает: роутер может выкинуть
 * его из таблицы NAT, а сервер - пропасть молча. Пробы каждые [KEEPALIVE_IDLE_S] с держат запись NAT живой,
 * а мертвое соединение сокет замечает сам (ошибка вместо ожидания до тайм-аута чтения).
 *
 * У ядра первая проба по умолчанию через 2 часа, поэтому интервалы задаются для каждого сокета.
 * Если система не дала их задать, остается keepalive с интервалами по умолчанию.
 */
class KeepAliveSocketFactory(private val delegate: SocketFactory = getDefault()) : SocketFactory() {

    override fun createSocket(): Socket = delegate.createSocket().configured()

    override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port).configured()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).configured()

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port).configured()

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).configured()

    private fun Socket.configured(): Socket {
        runCatching { keepAlive = true }.onFailure { return this }
        runCatching {
            // Копия дескриптора: параметры применяются к тому же сокету, копию сразу закрываем
            ParcelFileDescriptor.fromSocket(this).use { pfd ->
                val fd = pfd.fileDescriptor
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, KEEPALIVE_IDLE_S)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, KEEPALIVE_INTERVAL_S)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, KEEPALIVE_COUNT)
            }
        }.onFailure { Log.w(TAG, "TCP keepalive intervals not set: ${it.message}") }
        return this
    }

    private companion object {
        const val TAG = "KeepAliveSocket"
        // Номера опций Linux (netinet/tcp.h), в OsConstants их нет
        const val TCP_KEEPIDLE = 4
        const val TCP_KEEPINTVL = 5
        const val TCP_KEEPCNT = 6
        const val KEEPALIVE_IDLE_S = 30
        const val KEEPALIVE_INTERVAL_S = 15
        // Соединение считается мертвым после 4 проб без ответа (~1.5 мин тишины)
        const val KEEPALIVE_COUNT = 4
    }
}
