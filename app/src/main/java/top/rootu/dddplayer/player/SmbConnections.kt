package top.rootu.dddplayer.player

import android.content.Context
import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.SmbAddress
import top.rootu.dddplayer.logic.SmbLogic
import top.rootu.dddplayer.model.SmbServer
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Элемент сетевой папки SMB. */
data class SmbEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * Подключения к SMB-серверам (smbj). Сессии и общие ресурсы переиспользуются: перемотка видео открывает
 * файл заново, и новое подключение на каждую перемотку было бы заметно медленнее.
 * Учетные данные берутся из сохраненных сетевых папок по серверу и ресурсу, иначе вход гостем.
 */
object SmbConnections {

    private val client by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build()
        )
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val shares = ConcurrentHashMap<String, DiskShare>()
    // Блокировка своя у каждого сервера: подключение к недоступному серверу (до 30 с)
    // не задерживает чтение с остальных
    private val locks = ConcurrentHashMap<String, Any>()
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    /** Сохраненная сетевая папка, к которой относится адрес (тот же сервер и ресурс). */
    fun serverFor(address: SmbAddress): SmbServer? {
        if (!::appContext.isInitialized) return null
        return SettingsRepository.getInstance(appContext).getSmbServers().firstOrNull { server ->
            val saved = SmbLogic.parseAddress(server.address) ?: return@firstOrNull false
            saved.host.equals(address.host, ignoreCase = true) && saved.share.equals(address.share, ignoreCase = true)
        }
    }

    fun share(address: SmbAddress, server: SmbServer? = serverFor(address)): DiskShare {
        val user = server?.user.orEmpty()
        val shareKey = "${address.host}:${address.port}|$user|${address.share}".lowercase()
        val sessionKey = "${address.host}:${address.port}|$user".lowercase()
        return synchronized(locks.getOrPut(sessionKey) { Any() }) {
            shares[shareKey]?.takeIf { it.isConnected }?.let { return@synchronized it }

            val session = sessions[sessionKey]?.takeIf { it.connection.isConnected } ?: run {
                // Оборванное подключение закрываем, иначе оно остается висеть до выхода из приложения
                sessions.remove(sessionKey)?.let { stale ->
                    try { stale.connection.close(true) } catch (_: Exception) {}
                }
                val connection = if (address.port != null) client.connect(address.host, address.port) else client.connect(address.host)
                val auth = if (server == null || server.user.isEmpty()) {
                    AuthenticationContext.guest()
                } else {
                    AuthenticationContext(server.user, server.password.toCharArray(), server.domain.ifEmpty { null })
                }
                connection.authenticate(auth).also { sessions[sessionKey] = it }
            }
            val share = session.connectShare(address.share) as DiskShare
            shares[shareKey] = share
            share
        }
    }

    /** Содержимое папки (без "." и ".."). */
    fun list(address: SmbAddress, server: SmbServer? = serverFor(address)): List<SmbEntry> =
        share(address, server).list(address.path).filter { it.fileName != "." && it.fileName != ".." }.map {
            val isDirectory = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            SmbEntry(it.fileName, isDirectory, it.endOfFile)
        }

    /** Файл только для чтения, другие программы могут читать и писать его одновременно. */
    fun openFile(address: SmbAddress): File =
        share(address).openFile(
            address.path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )

    /** Адрес из ссылки smb://host[:port]/share/path (имена в ссылке закодированы). */
    fun addressOf(uri: Uri): SmbAddress? {
        val host = uri.host ?: return null
        val segments = uri.pathSegments
        val share = segments.firstOrNull() ?: return null
        return SmbAddress(host, uri.port.takeIf { it > 0 }, share, segments.drop(1).joinToString("/"))
    }

    /** Ссылка на файл или папку для плеера и истории. */
    fun uriOf(address: SmbAddress): Uri = Uri.Builder()
        .scheme("smb")
        .encodedAuthority(address.host + (address.port?.let { ":$it" }.orEmpty()))
        .appendPath(address.share)
        .apply { address.path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }
        .build()
}
