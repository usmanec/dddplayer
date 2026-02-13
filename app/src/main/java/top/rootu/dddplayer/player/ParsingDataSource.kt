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
    private var parsingJob: Job? = null

    private val pipeSink = object : DataSink {
        private var pipedOut: PipedOutputStream? = null
        private var pipedIn: PipedInputStream? = null
        private var totalBytesWritten = 0L

        // Флаг активности парсера. Если false, мы перестаем писать в пайп.
        @Volatile private var isParsingActive = false

        override fun open(dataSpec: DataSpec) {
            if (dataSpec.position == 0L) {
                totalBytesWritten = 0
                isParsingActive = true
                pipedOut = PipedOutputStream()
                // Выделяем всего 64 КБ памяти
                pipedIn = PipedInputStream(pipedOut!!, PIPE_BUFFER_SIZE)

                parsingJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Log.d(TAG, "Start parsing metadata...")
                        // Парсер будет читать из pipedIn.
                        // Когда он делает skip(), данные "вымываются" из буфера 64КБ,
                        // освобождая место для новых данных из write().
                        val metadata = UnifiedMetadataReader.parse(pipedIn!!)
                        Log.d(TAG, "Parsing finished. Found ${metadata.size} tracks.")

                        if (metadata.isNotEmpty()) {
                            metadata.forEach {
                                Log.d(TAG, "Track found: ID=${it.trackId}, Name=${it.name}, Lang=${it.language}")
                            }
                            onMetadataParsed(metadata.associateBy { it.trackId })
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Metadata parsing stopped: ${e.message}")
                    } finally {
                        // Парсер закончил работу (успешно или с ошибкой)
                        isParsingActive = false
                        closePipes()
                    }
                }
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            // Если парсер отключился или мы превысили разумный лимит поиска (50МБ)
            if (!isParsingActive || totalBytesWritten >= MAX_BYTES_TO_PARSE) {
                if (isParsingActive) {
                    // Если мы превысили лимит, но парсер еще ждет, принудительно закрываем
                    isParsingActive = false
                    closePipes()
                    parsingJob?.cancel()
                }
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
                // "Pipe closed" или "Pipe broken".
                // Это означает, что парсер закрыл поток (нашел данные и вышел).
                // Это нормальная ситуация, просто перестаем писать.
                isParsingActive = false
            }
        }

        override fun close() {
            closePipes()
        }

        private fun closePipes() {
            try { pipedIn?.close() } catch (_: Exception) {}
            try { pipedOut?.close() } catch (_: Exception) {}
            pipedIn = null
            pipedOut = null
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position == 0L && !isMetadataParsed()) {
            parsingJob?.cancel()
            teeDataSource = TeeDataSource(upstream, pipeSink)
            return teeDataSource!!.open(dataSpec)
        }
        teeDataSource = null
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return teeDataSource?.read(buffer, offset, length) ?: upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        teeDataSource?.close() ?: upstream.close()
        parsingJob?.cancel()
        pipeSink.close()
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