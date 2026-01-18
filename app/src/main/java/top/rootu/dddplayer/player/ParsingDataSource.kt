package top.rootu.dddplayer.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TeeDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.rootu.dddplayer.logic.UnifiedMetadataReader
import java.io.PipedInputStream
import java.io.PipedOutputStream

private const val TAG = "ParsingDataSource"
// Устанавливаем размер с запасом, чтобы покрыть самые "тяжелые" случаи.
// 32 МБ должно хватить для подавляющего большинства файлов.
private const val PARSE_BUFFER_SIZE = 32 * 1024 * 1024 // 32 MB

@UnstableApi
class ParsingDataSource(
    private val upstream: DataSource,
    private val onMetadataParsed: (Map<Int, UnifiedMetadataReader.TrackInfo>) -> Unit,
    private val isMetadataParsed: () -> Boolean
) : DataSource by upstream {

    private var teeDataSource: TeeDataSource? = null
    private var parsingJob: Job? = null

    private val pipeSink = object : DataSink {
        private var pipedOut: PipedOutputStream? = null
        private var pipedIn: PipedInputStream? = null
        private var totalBytesWritten = 0L

        override fun open(dataSpec: DataSpec) {
            if (dataSpec.position == 0L) {
                totalBytesWritten = 0
                pipedOut = PipedOutputStream()
                // Важно: PipedInputStream должен иметь буфер не меньше, чем мы планируем писать,
                // иначе write() заблокируется, если reader (парсинг) будет медленным.
                pipedIn = PipedInputStream(pipedOut!!, PARSE_BUFFER_SIZE)

                parsingJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Log.d(TAG, "Start parsing metadata...")
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
                        closePipes()
                    }
                }
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            // Если мы уже записали больше лимита, просто игнорируем, чтобы не грузить память
            if (totalBytesWritten >= PARSE_BUFFER_SIZE) return

            try {
                // Пишем в пайп. Если пайп переполнен, это может заблокировать поток загрузки.
                // Но так как мы задали буфер пайпа равным лимиту (32МБ), блокировки не будет,
                // пока мы не заполним его целиком.
                pipedOut?.write(buffer, offset, length)
                totalBytesWritten += length
            } catch (e: Exception) {
                // Парсер закрыл поток (нашел что искал), это нормально.
            }
        }

        override fun close() {
            closePipes()
        }

        private fun closePipes() {
            try { pipedIn?.close() } catch (e: Exception) {}
            try { pipedOut?.close() } catch (e: Exception) {}
            pipedIn = null
            pipedOut = null
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        // Запускаем парсинг ТОЛЬКО если:
        // 1. Это начало файла (position == 0)
        // 2. Метаданные ЕЩЕ НЕ были распарсены
        if (dataSpec.position == 0L && !isMetadataParsed()) {
            teeDataSource = TeeDataSource(upstream, pipeSink)
            return teeDataSource!!.open(dataSpec)
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return teeDataSource?.read(buffer, offset, length) ?: upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        teeDataSource?.close() ?: upstream.close()
        parsingJob?.cancel()
    }
}

@UnstableApi
class ParsingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val onMetadataParsed: (Map<Int, UnifiedMetadataReader.TrackInfo>) -> Unit,
    private val isMetadataParsed: () -> Boolean
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return ParsingDataSource(upstreamFactory.createDataSource(), onMetadataParsed, isMetadataParsed)
    }
}