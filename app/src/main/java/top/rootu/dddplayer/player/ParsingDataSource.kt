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
private const val PIPE_BUFFER_SIZE = 64 * 1024

// 2. Максимальный объем данных, который мы готовы скормить парсеру.
// Если метаданные не найдены в первых 32 МБ, мы прекращаем попытки,
// чтобы не блокировать загрузку видео слишком долго.
private const val MAX_BYTES_TO_PARSE = 32 * 1024 * 1024

class ParsingDataSource(
    private val upstream: DataSource,
    private val onMetadataParsed: (Map<Int, UnifiedMetadataReader.TrackInfo>) -> Unit,
    private val isMetadataParsed: () -> Boolean
) : DataSource {

    private var teeDataSource: TeeDataSource? = null
    private var parsingJob: Job? = null // Используем Job напрямую для контроля

    private val pipeSink = object : DataSink {
        private var pipedOut: PipedOutputStream? = null
        private var pipedIn: PipedInputStream? = null
        private var totalBytesWritten = 0L

        // Флаг активности парсера. Если false, мы перестаем писать в пайп.
        @Volatile private var isParsingActive = false

        override fun open(dataSpec: DataSpec) {
            // Запускаем парсинг только если это начало файла и метаданные еще не были распарсены
            if (dataSpec.position == 0L && !isMetadataParsed()) {
                stopParsing() // Чистим всё старое
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

                // Запускаем парсинг в отдельном Job
                parsingJob = CoroutineScope(Dispatchers.IO).launch {
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
                        // Ошибка или закрытие потока - норма
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
            // Не вызываем stopParsing здесь, так как ExoPlayer может закрыть Sink,
            // но DataSource еще должен жить.
        }

        fun stopParsing() {
            isParsingActive = false
            parsingJob?.cancel()
            parsingJob = null
            closePipes()
        }

        private fun closePipes() {
            try { pipedOut?.close() } catch (_: Exception) {}
            try { pipedIn?.close() } catch (_: Exception) {}
            pipedOut = null
            pipedIn = null
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
            // ВАЖНО: При закрытии DataSource мы ОБЯЗАНЫ убить парсер
            pipeSink.stopParsing()
            teeDataSource?.close()
        } finally {
            upstream.close()
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