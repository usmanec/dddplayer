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

// Одна сессия парсинга = свои пайпы и своя корутина.
// Корутина работает только со своими потоками, поэтому завершение старой
// сессии не может закрыть пайпы новой (при переоткрытии источника).
private class ParseSession(
    val pipedOut: PipedOutputStream,
    val pipedIn: PipedInputStream,
    val scope: CoroutineScope
) {
    var totalBytesWritten = 0L

    fun close() {
        scope.cancel()
        try { pipedIn.close() } catch (_: Exception) {}
        try { pipedOut.close() } catch (_: Exception) {}
    }
}

class ParsingDataSource(
    private val upstream: DataSource,
    // Метаданные хранятся по URI файла: у каждого элемента плейлиста они свои
    private val onMetadataParsed: (Uri, Map<Int, UnifiedMetadataReader.TrackInfo>) -> Unit,
    private val isMetadataParsed: (Uri) -> Boolean
) : DataSource {

    private var teeDataSource: TeeDataSource? = null

    private val pipeSink = object : DataSink {
        // Если null, мы не пишем в пайп.
        @Volatile private var session: ParseSession? = null

        override fun open(dataSpec: DataSpec) {
            // Запускаем парсинг только если это начало файла и метаданные еще не были распарсены
            if (dataSpec.position == 0L && !isMetadataParsed(dataSpec.uri)) {
                stopParsing() // Закрываем старую сессию на всякий случай

                val sourceUri = dataSpec.uri
                val pipedOut = PipedOutputStream()
                val pipedIn = try {
                    PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to create pipes", e)
                    return
                }

                // Создаем новый scope для каждой задачи парсинга
                val newSession = ParseSession(pipedOut, pipedIn, CoroutineScope(Dispatchers.IO + Job()))
                session = newSession
                newSession.scope.launch {
                    try {
                        Log.d(TAG, "Start parsing metadata...")
                        val metadata = UnifiedMetadataReader.parse(pipedIn)
                        Log.d(TAG, "Parsing finished. Found ${metadata.size} tracks.")

                        if (metadata.isNotEmpty()) {
                            metadata.forEach {
                                Log.d(TAG, " - Track: ID=${it.trackId}, Name=${it.name}, Lang=${it.language}")
                            }
                            onMetadataParsed(sourceUri, metadata.associateBy { it.trackId })
                        }
                    } catch (e: Exception) {
                        // Pipe closed, Interrupted, etc. - это нормальные сценарии завершения
                        Log.w(TAG, "Metadata parsing stopped: ${e.message}")
                    } finally {
                        // Закрываем только потоки своей сессии
                        try { pipedIn.close() } catch (_: Exception) {}
                    }
                }
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val current = session ?: return

            if (current.totalBytesWritten >= MAX_BYTES_TO_PARSE) {
                Log.w(TAG, "Max parse limit reached. Stopping parser.")
                stopParsing(current)
                return
            }

            try {
                // Пишем в пайп. Если буфер (64КБ) полон, этот метод заблокируется,
                // пока парсер не прочитает/пропустит данные.
                // Это создает естественное замедление загрузки (backpressure),
                // необходимое для синхронизации.
                current.pipedOut.write(buffer, offset, length)
                current.totalBytesWritten += length
            } catch (e: IOException) {
                // "Pipe closed" или "Pipe broken" - парсер закончил работу.
                stopParsing(current)
            }
        }

        override fun close() {
            stopParsing()
        }

        private fun stopParsing(expected: ParseSession? = session) {
            val current = expected ?: return
            if (session === current) session = null
            current.close()
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        // Если это начало файла и метаданные еще не распарсены, используем TeeDataSource
        if (dataSpec.position == 0L && !isMetadataParsed(dataSpec.uri)) {
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
    // Метаданные хранятся по URI файла: у каждого элемента плейлиста они свои
    private val onMetadataParsed: (Uri, Map<Int, UnifiedMetadataReader.TrackInfo>) -> Unit,
    private val isMetadataParsed: (Uri) -> Boolean
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return ParsingDataSource(upstreamFactory.createDataSource(), onMetadataParsed, isMetadataParsed)
    }
}