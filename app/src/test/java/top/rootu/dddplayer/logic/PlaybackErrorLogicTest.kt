package top.rootu.dddplayer.logic

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

// PlaybackException берет время через Android SystemClock - нужен Robolectric
@RunWith(RobolectricTestRunner::class)
class PlaybackErrorLogicTest {

    private fun contentError(root: IOException) = PlaybackException(
        "Source error",
        ContentDataSource.ContentDataSourceException(root, PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED
    )

    @Test
    fun `cause chain ends with the real reason`() {
        val lines = PlaybackErrorLogic.causeLines(contentError(IOException("read failed: EIO (I/O error)")))
        assertEquals(listOf("IOException: read failed: EIO (I/O error)"), lines)
    }

    @Test
    fun `wrapper without its own message is kept as a type`() {
        val error = PlaybackException(
            "Source error",
            ContentDataSource.ContentDataSourceException(null, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE),
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        )
        assertEquals(listOf("ContentDataSourceException"), PlaybackErrorLogic.causeLines(error))
    }

    @Test
    fun `long chains keep the deepest lines and long messages are cut`() {
        val root = IOException("x".repeat(300))
        val chain = IOException("level 1", IOException("level 2", IOException("level 3", root)))
        val lines = PlaybackErrorLogic.causeLines(PlaybackException("top", chain, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), maxLines = 2)
        assertEquals(2, lines.size)
        assertEquals("IOException: level 3", lines[0])
        assertTrue(lines[1].endsWith("...") && lines[1].length == 120)
    }

    @Test
    fun `content source errors are recognized`() {
        assertTrue(PlaybackErrorLogic.isContentSourceError(contentError(IOException("EIO"))))
        val network = PlaybackException(
            "Source error",
            HttpDataSource.HttpDataSourceException(
                IOException("timeout"),
                androidx.media3.datasource.DataSpec(android.net.Uri.parse("http://example.com/v.mp4")),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            ),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
        assertFalse(PlaybackErrorLogic.isContentSourceError(network))
    }

    @Test
    fun `unparsed formats can be opened in another player`() {
        fun error(code: Int) = PlaybackException("Source error", null, code)
        assertTrue(PlaybackErrorLogic.canOpenInOtherPlayer(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
        assertTrue(PlaybackErrorLogic.canOpenInOtherPlayer(error(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)))
        assertFalse(PlaybackErrorLogic.canOpenInOtherPlayer(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertFalse(PlaybackErrorLogic.canOpenInOtherPlayer(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
    }
}
