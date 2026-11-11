package top.rootu.dddplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class IdleReconnectDataSourceTest {

    private val data = ByteArray(100) { it.toByte() }

    /** "Сервер": отдает [data] с позиции запроса и запоминает открытия. */
    private inner class FakeUpstream : DataSource {
        val opened = mutableListOf<DataSpec>()
        var closed = 0
        private var position = 0

        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            position = dataSpec.position.toInt()
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            return count
        }
        override fun getUri(): Uri? = opened.lastOrNull()?.uri
        override fun close() { closed++ }
    }

    private fun readBytes(source: DataSource, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var done = 0
        while (done < count) done += source.read(buffer, done, count - done)
        return buffer
    }

    @Test
    fun `reads without idle keep one connection`() {
        val upstream = FakeUpstream()
        val source = IdleReconnectDataSource(upstream, idleReconnectMs = 60_000)
        source.open(DataSpec(Uri.parse("http://host/video.mkv")))
        readBytes(source, 30)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        readBytes(source, 30)
        assertEquals(1, upstream.opened.size)
    }

    @Test
    fun `after idle the connection reopens at the same byte`() {
        val upstream = FakeUpstream()
        val source = IdleReconnectDataSource(upstream, idleReconnectMs = 60_000)
        source.open(DataSpec(Uri.parse("http://host/video.mkv")))
        readBytes(source, 40)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))

        val next = readBytes(source, 10)

        assertEquals(2, upstream.opened.size)
        assertEquals(40L, upstream.opened[1].position)
        assertEquals(1, upstream.closed)
        // Поток продолжается без пропусков и повторов
        assertEquals(data.copyOfRange(40, 50).toList(), next.toList())
    }

    @Test
    fun `reopen keeps the original start offset`() {
        val upstream = FakeUpstream()
        val source = IdleReconnectDataSource(upstream, idleReconnectMs = 60_000)
        source.open(DataSpec.Builder().setUri(Uri.parse("https://host/video.mp4")).setPosition(20).build())
        readBytes(source, 10)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        readBytes(source, 1)
        assertEquals(30L, upstream.opened[1].position)
    }

    @Test
    fun `local files are not reopened`() {
        val upstream = FakeUpstream()
        val source = IdleReconnectDataSource(upstream, idleReconnectMs = 60_000)
        source.open(DataSpec(Uri.parse("file:///sdcard/video.mkv")))
        readBytes(source, 10)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        readBytes(source, 10)
        assertEquals(1, upstream.opened.size)
    }
}
