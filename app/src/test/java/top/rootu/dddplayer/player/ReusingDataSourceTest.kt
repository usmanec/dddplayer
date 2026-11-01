package top.rootu.dddplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ReusingDataSourceTest {

    private val file = ByteArray(3 * 1024 * 1024) { (it * 31 + it / 7).toByte() }
    private val uri = Uri.parse("https://store.example/dld/film.mp4")
    private val opens = mutableListOf<Long>() // позиции запросов в "сеть"
    private var failReads = false
    private var limit = Int.MAX_VALUE // соединений сразу, как у IPTV с одним на аккаунт
    private var live = 0

    /** "Сеть": каждый open - запрос с Range, чтение отдает файл с этой позиции. */
    private inner class Network : DataSource {
        private var position = 0
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            if (live == limit) throw HttpDataSource.HttpDataSourceException(dataSpec,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            live++
            opens += dataSpec.position
            position = dataSpec.position.toInt()
            return file.size - dataSpec.position
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (failReads) throw IOException("connection reset")
            if (position == file.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, file.size - position)
            System.arraycopy(file, position, buffer, offset, count)
            position += count
            return count
        }
        override fun getUri(): Uri = uri
        override fun close() {
            closes++
            live--
        }
    }

    private var closes = 0

    private val source = ReusingDataSource { Network() }

    @After
    fun closeParked() {
        ReusingDataSource.closeParked() // общие для всех экземпляров
    }

    private fun readAt(position: Long, count: Int): ByteArray {
        assertEquals(file.size - position, source.open(DataSpec.Builder().setUri(uri).setPosition(position).build()))
        val out = ByteArray(count)
        var done = 0
        while (done < count) done += source.read(out, done, count - done)
        source.close()
        return out
    }

    private fun assertReads(position: Long, count: Int) =
        assertArrayEquals(file.copyOfRange(position.toInt(), position.toInt() + count), readAt(position, count))

    @Test
    fun `steps back by 2 bytes, as the MP4 extractor reads out-of-order subtitles, need one request`() {
        for (position in 1_215_000L downTo 1_214_960L step 2) assertReads(position, 2)
        assertEquals(listOf(1_215_000L - ReusingDataSource.BACK), opens)
    }

    @Test
    fun `head, index at the end, back to the head reuses the head connection`() {
        val index = file.size - 300_000L
        assertReads(0, 40)
        assertReads(index, 300_000) // через границу кольца окна
        assertReads(117, 1000)
        assertReads(file.size - 5000L, 1000) // назад в окно индекса
        assertEquals(listOf(0L, index - ReusingDataSource.BACK), opens)
    }

    @Test
    fun `another address closes the parked connection at once, also from the next item's source`() {
        assertReads(0, 10)
        assertEquals(0, closes)
        val next = ReusingDataSource { Network() } // Media3: свой источник у каждого элемента плейлиста
        next.open(DataSpec(Uri.parse("https://iptv.example/channel/2.ts")))
        assertEquals(1, closes)
        next.close()
    }

    @Test
    fun `a server allowing one connection gets the parked one closed and the request again`() {
        limit = 1
        assertReads(0, 10)
        assertReads(file.size - 1000L, 1000) // индекс в конце: голова еще на парковке
        assertEquals(listOf(0L, file.size - 1000L - ReusingDataSource.BACK), opens)
        assertEquals(1, live)
    }

    @Test
    fun `a far jump is a new request, a broken parked connection is replaced`() {
        val far = ReusingDataSource.SKIP_AHEAD + 100_000
        assertReads(0, 10)
        assertReads(far, 10)
        failReads = true
        try {
            readAt(500, 10)
            fail("expected the reset to reach the player")
        } catch (_: IOException) {
            source.close() // так делает Media3 после ошибки open
        }
        failReads = false
        assertReads(500, 10)
        // 0: голова; far: прыжок; 0: попытка после сброса припаркованного; 0: повтор плеера
        assertEquals(listOf(0L, far - ReusingDataSource.BACK, 0L, 0L), opens)
    }
}
