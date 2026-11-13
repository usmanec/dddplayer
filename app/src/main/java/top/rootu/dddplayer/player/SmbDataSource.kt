package top.rootu.dddplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.smbj.share.File
import java.io.IOException
import kotlin.math.min

/**
 * Чтение файла с SMB-сервера для плеера (smb://host/share/path). Перемотка - чтение с нужного места файла.
 */
class SmbDataSource : BaseDataSource(/* isNetwork= */ true) {

    private var file: File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val address = SmbConnections.addressOf(dataSpec.uri)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val opened = try {
            SmbConnections.openFile(address)
        } catch (e: SMBApiException) {
            val code = when (e.status) {
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_LOGON_FAILURE -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                else -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            }
            throw DataSourceException(e, code)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = opened
        val size = opened.getFileInformation(FileStandardInformation::class.java).endOfFile
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        bytesRemaining = size - position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) bytesRemaining = min(bytesRemaining, dataSpec.length)
        this.opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val current = file ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val toRead = min(length.toLong(), bytesRemaining).toInt()
        val read = try {
            current.read(buffer, position, offset, toRead)
        } catch (e: Exception) {
            throw DataSourceException(e as? IOException ?: IOException(e), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } catch (_: Exception) {
            // Файл на сервере закроется вместе с сессией
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/**
 * Источник, который отдает smb:// в [SmbDataSource], а остальное - обычной фабрике (file, content, http).
 */
class SmbRoutingDataSource(private val upstream: DataSource) : DataSource {

    private val smb = SmbDataSource()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        smb.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme.equals("smb", ignoreCase = true)) smb else upstream
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbRoutingDataSource(upstream.createDataSource())
    }
}
