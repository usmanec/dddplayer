package top.rootu.dddplayer.utils

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class IntentUtilsTest {

    private val context = RuntimeEnvironment.getApplication()

    private val videos = arrayOf(
        Uri.parse("http://example.com/e1.mp4"),
        Uri.parse("http://example.com/e2.mp4"),
        Uri.parse("http://example.com/e3.mp4")
    )

    @Test
    fun `single file with title, position as string, poster and headers`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
            putExtra("title", "Big Buck Bunny")
            putExtra("position", "60000")
            putExtra("thumbnail", "http://example.com/poster.jpg")
            putExtra("headers", arrayOf("Referer", "http://example.com/", "X-Token", "abc"))
        }

        val (items, startIndex) = IntentUtils.parseIntent(context, intent)
        val item = items.single()
        assertEquals(0, startIndex)
        assertEquals("Big Buck Bunny", item.title)
        assertEquals(60_000L, item.startPositionMs)
        assertEquals("http://example.com/poster.jpg", item.posterUri.toString())
        assertEquals(mapOf("Referer" to "http://example.com/", "X-Token" to "abc"), item.headers)
    }

    @Test
    fun `single file without title uses the file name`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com/path/My%20Movie.mkv"))
        val (items, _) = IntentUtils.parseIntent(context, intent)
        assertEquals("My Movie.mkv", items.single().title)
        assertEquals(0L, items.single().startPositionMs)
    }

    @Test
    fun `playlist starts from the item matching the intent data`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videos[2], "video/*")
            putExtra("video_list", videos)
            putExtra("video_list.name", arrayOf("One", "Two", "Three"))
            putExtra("position", 5000L)
            // Data совпадает с элементом - start_index не используется
            putExtra("start_index", 1)
        }

        val (items, startIndex) = IntentUtils.parseIntent(context, intent)
        assertEquals(listOf("One", "Two", "Three"), items.map { it.title })
        assertEquals(2, startIndex)
        // Позиция применяется только к элементу из data
        assertEquals(listOf(0L, 0L, 5000L), items.map { it.startPositionMs })
    }

    @Test
    fun `start_index is a fallback and is clamped`() {
        val withIndex = Intent(Intent.ACTION_VIEW).apply {
            putExtra("video_list", videos)
            putExtra("start_index", 1)
        }
        assertEquals(1, IntentUtils.parseIntent(context, withIndex).second)

        val outOfRange = Intent(Intent.ACTION_VIEW).apply {
            putExtra("video_list", videos)
            putExtra("start_index", 10)
        }
        assertEquals(2, IntentUtils.parseIntent(context, outOfRange).second)

        val none = Intent(Intent.ACTION_VIEW).apply { putExtra("video_list", videos) }
        assertEquals(0, IntentUtils.parseIntent(context, none).second)
    }

    @Test
    fun `playlist titles fall back to file names`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            putExtra("video_list", videos)
            putExtra("video_list.filename", arrayOf("first.mp4", "second.mp4", "third.mp4"))
        }
        val (items, _) = IntentUtils.parseIntent(context, intent)
        assertEquals(listOf("first.mp4", "second.mp4", "third.mp4"), items.map { it.title })
    }

    @Test
    fun `audio languages for this launch`() {
        fun langs(value: String?) = IntentUtils.parseAudioLanguages(
            Intent(Intent.ACTION_VIEW).apply { if (value != null) putExtra("audio_lang", value) })?.toList()
        assertEquals(listOf("ru", "uk"), langs("ru, uk"))
        assertEquals(null, langs(null))
        assertEquals(null, langs(" , "))
    }

    @Test
    fun `title tracks from the app`() {
        fun tracks(block: Intent.() -> Unit) = IntentUtils.parseTitleTracks(Intent(Intent.ACTION_VIEW).apply(block))
        assertEquals(null, tracks {})
        assertEquals(IntentUtils.TitleTracks("Dub|ru|audio/ac3|6", "off", 1.25f), tracks {
            putExtra("audio_track", "Dub|ru|audio/ac3|6")
            putExtra("subtitle_track", "off")
            putExtra("speed", 1.25f)
        })
        // Пустой ключ включает память, но дорожки еще нет; скорость строкой или Double тоже подходит
        assertEquals(IntentUtils.TitleTracks(null, null, null), tracks { putExtra("audio_track", "") })
        assertEquals(1.5f, tracks { putExtra("speed", "1.5") }?.speed)
        assertEquals(0.75f, tracks { putExtra("speed", 0.75) }?.speed)
        assertEquals(null, tracks { putExtra("speed", 0f) }?.speed)
    }
}
