package top.rootu.dddplayer.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Кастомный TextView с поддержкой четкой обводки (stroke) текста.
 */
class OutlineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var strokeColor = Color.BLACK
    private var strokeWidth = 0f

    init {
        // Переводим 1dp в пиксели для толщины контура
        strokeWidth = 1.0f * context.resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val states = textColors

        // Рисуем контур (Stroke)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeMiter = 10f
        this.setTextColor(strokeColor)
        paint.strokeWidth = strokeWidth
        super.onDraw(canvas)

        // Рисуем сам текст (Fill)
        paint.style = Paint.Style.FILL
        setTextColor(states)
        super.onDraw(canvas)
    }
}