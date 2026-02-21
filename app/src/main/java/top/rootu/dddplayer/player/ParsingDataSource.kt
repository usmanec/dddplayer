package top.rootu.dddplayer.player

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TeeDataSource
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.rootu.dddplayer.logic.UnifiedMetadataReader
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

private const val TAG = "ParsingDataSource"

// 1. Размер буфера в оперативной памяти (RAM).
// 64 КБ - стандартный размер чанка IO. Этого достаточно для потока.
// Это решает проблему OutOfMemoryError.
private const val PIPE_BUFFER_SIZE = 64 * 1024

// 2. Максимальный объем данных, который мы готовы скормить парсеру.
// Если метаданные не найдены в первых 50 МБ, мы прекращаем попытки,
// чтобы не блокировать загрузку видео слишком долго.
private const val MAX_BYTES_TO_PARSE = 50 * 1024 * 1024 // 50 MB

class ParsingDataSource(
    private val upstream: DataSource,
    private val onMetadataParsed: (Map<Int, UnifiedMetadataReader.TrackInfo>) -> Unit,
    private val isMetadataParsed: () -> Boolean
) : DataSource {

    private var teeDataSource: TeeDataSource? = null
    private var parsingScope: CoroutineScope? = null

    private val pipeSink = object : DataSink {
        private var pipedOut: PipedOutputStream? = null
        private var pipedIn: PipedInputStream? = null
        private var totalBytesWritten = 0L

        // Флаг активности парсера. Если false, мы перестаем писать в пайп.
        @Volatile private var isParsingActive = false

        override fun open(dataSpec: DataSpec) {
            // Запускаем парсинг только если это начало файла и метаданные еще не были распарсены
            if (dataSpec.position == 0L && !isMetadataParsed()) {
                closePipes() // Закрываем старые потоки на всякий случай
                totalBytesWritten = 0
                isParsingActive = true

                try {
                    pipedOut = PipedOutputStream()
                    pipedIn = PipedInputStream(pipedOut!!, PIPE_BUFFER_SIZE)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to create pipes", e)
                    isParsingActive = false
                    return
                }

                // Создаем новый scope для каждой задачи парсинга
                parsingScope = CoroutineScope(Dispatchers.IO + Job())
                parsingScope?.launch {
                    try {
                        Log.d(TAG, "Start parsing metadata...")
                        val metadata = UnifiedMetadataReader.parse(pipedIn!!)
                        Log.d(TAG, "Parsing finished. Found ${metadata.size} tracks.")

                        if (metadata.isNotEmpty()) {
                            metadata.forEach {
                                Log.d(TAG, " - Track: ID=${it.trackId}, Name=${it.name}, Lang=${it.language}")
                            }
                            onMetadataParsed(metadata.associateBy { it.trackId })
                        }
                    } catch (e: Exception) {
                        // Pipe closed, Interrupted, etc. - это нормальные сценарии завершения
                        Log.w(TAG, "Metadata parsing stopped: ${e.message}")
                    } finally {
                        // Гарантированно закрываем потоки в конце
                        isParsingActive = false
                        closePipes()
                    }
                }
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (!isParsingActive) return

            if (totalBytesWritten >= MAX_BYTES_TO_PARSE) {
                Log.w(TAG, "Max parse limit reached. Stopping parser.")
                stopParsing()
                return
            }

            try {
                // Пишем в пайп. Если буфер (64КБ) полон, этот метод заблокируется,
                // пока парсер не прочитает/пропустит данные.
                // Это создает естественное замедление загрузки (backpressure),
                // необходимое для синхронизации.
                pipedOut?.write(buffer, offset, length)
                totalBytesWritten += length
            } catch (e: IOException) {
                // "Pipe closed" или "Pipe broken" - парсер закончил работу.
                stopParsing()
            }
        }

        override fun close() {
            stopParsing()
        }

        private fun stopParsing() {
            if (isParsingActive) {
                isParsingActive = false
                parsingScope?.cancel() // Отменяем корутину
                closePipes()
            }
        }

        private fun closePipes() {
            try { pipedIn?.close() } catch (_: Exception) {}
            try { pipedOut?.close() } catch (_: Exception) {}
            pipedIn = null
            pipedOut = null
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        // Если это начало файла и метаданные еще не распарсены, используем TeeDataSource
        if (dataSpec.position == 0L && !isMetadataParsed()) {
            teeDataSource = TeeDataSource(upstream, pipeSink)
            return teeDataSource!!.open(dataSpec)
        }
        // В остальных случаях (перемотка, следующий трек с уже известными метаданными) используем напрямую
        teeDataSource = null
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return teeDataSource?.read(buffer, offset, length) ?: upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        try {
            teeDataSource?.close() ?: upstream.close()
        } finally {
            // Гарантированно отменяем корутину и закрываем потоки при закрытии DataSource
            pipeSink.close()
            teeDataSource = null
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // Делегируем вызов upstream, так как он является основным источником данных
        upstream.addTransferListener(transferListener)
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        // Делегируем вызов upstream
        return upstream.responseHeaders
    }
}

class ParsingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val onMetadataParsed: (Map<Int, UnifiedMetadataReader.TrackInfo>) -> Unit,
    private val isMetadataParsed: () -> Boolean
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return ParsingDataSource(upstreamFactory.createDataSource(), onMetadataParsed, isMetadataParsed)
    }
}