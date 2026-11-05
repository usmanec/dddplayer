package top.rootu.dddplayer.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.widget.SeekBar

/**
 * SeekBar that draws a small tick for every chapter start.
 *
 * Media3 does not expose chapters, so the positions come from the chapter
 * loader via the player view model.
 */
class ChapterSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private var chapterStarts = LongArray(0)
    private var totalDurationMs = 0L

    /** Positions are absolute milliseconds; ticks are skipped when duration is unknown. */
    fun setChapterStarts(starts: List<Long>, durationMs: Long) {
        chapterStarts = starts.toLongArray()
        totalDurationMs = durationMs
        Log.d("ChapterLoader", "seekbar: chapters=${starts.size}, duration=$durationMs")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (chapterStarts.isEmpty() || totalDurationMs <= 0L) return

        // Match the thumb centre, not the raw padding: AbsSeekBar positions the
        // thumb at padding + (thumbWidth / 2 - thumbOffset) .. width - padding - inset.
        val thumbWidth = thumb?.intrinsicWidth ?: 0
        val inset = if (thumbWidth > 0) thumbWidth / 2f - thumbOffset else 0f
        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        if (right <= left) return

        val centerY = height / 2f
        val half = 6f * density
        for (start in chapterStarts) {
            if (start <= 0L || start >= totalDurationMs) continue
            val x = left + (right - left) * (start.toDouble() / totalDurationMs).toFloat()
            canvas.drawLine(x, centerY - half, x, centerY + half, tickPaint)
        }
    }
}
