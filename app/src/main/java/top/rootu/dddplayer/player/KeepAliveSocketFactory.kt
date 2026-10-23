package top.rootu.dddplayer.player

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
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
        runCatching { setKeepAliveIntervals(this) }
            .onFailure { Log.w(TAG, "TCP keepalive intervals not set: ${it.message}") }
        return this
    }

    /**
     * Интервалы keepalive через дескриптор сокета. До Android 10 `ParcelFileDescriptor.fromSocket` не копирует
     * дескриптор, а оборачивает сам дескриптор сокета: закрыть обертку (или дождаться ее финализатора) - закрыть
     * сокет (было "Socket Closed" на каждом соединении). Поэтому там берем дескриптор сокета напрямую и не закрываем,
     * а с Android 10 (API 29) fromSocket делает копию, ее закрываем.
     */
    private fun setKeepAliveIntervals(socket: Socket) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ParcelFileDescriptor.fromSocket(socket)?.use { setIntervals(it.fileDescriptor) }
        } else {
            // Тот же метод, которым пользуется fromSocket; ограничений скрытого API до Android 9 нет
            val fd = Socket::class.java.getMethod("getFileDescriptor\$").invoke(socket) as? FileDescriptor ?: return
            setIntervals(fd)
        }
    }

    private fun setIntervals(fd: FileDescriptor) {
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, KEEPALIVE_IDLE_S)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, KEEPALIVE_INTERVAL_S)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, KEEPALIVE_COUNT)
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
