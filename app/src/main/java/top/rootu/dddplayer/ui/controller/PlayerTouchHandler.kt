package top.rootu.dddplayer.ui.controller

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

class PlayerTouchHandler(
    private val context: Context,
    private val screenWidth: Int,
    private val onSingleTap: () -> Unit,
    private val onDoubleTapSeek: (forward: Boolean) -> Unit,
    private val onVolumeChange: (deltaPercent: Float) -> Unit,
    private val onBrightnessChange: (deltaPercent: Float) -> Unit,
    private val onHorizontalScroll: (deltaX: Float) -> Unit,
    private val onGestureEnd: () -> Unit
) : GestureDetector.SimpleOnGestureListener() {

    private enum class ScrollMode { NONE, VOLUME, BRIGHTNESS, HORIZONTAL }
    private var scrollMode = ScrollMode.NONE

    // Порог срабатывания (в пикселях).
    // Должен быть достаточно большим, чтобы отличить скролл от тапа,
    // но достаточно маленьким, чтобы интерфейс был отзывчивым.
    private val scrollThreshold = 30f

    override fun onDown(e: MotionEvent): Boolean {
        scrollMode = ScrollMode.NONE
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        onSingleTap()
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        val isForward = e.x > screenWidth / 2
        onDoubleTapSeek(isForward)
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (e1 == null) return false

        // 1. Определяем режим, если он еще не выбран
        if (scrollMode == ScrollMode.NONE) {
            // Считаем ОБЩЕЕ смещение от точки нажатия
            val totalDx = e2.x - e1.x
            val totalDy = e2.y - e1.y

            // Проверяем, превышен ли порог по любой из осей
            if (abs(totalDx) > scrollThreshold || abs(totalDy) > scrollThreshold) {

                // Определяем доминирующее направление
                scrollMode = if (abs(totalDx) > abs(totalDy)) {
                    // Горизонтальный свайп
                    ScrollMode.HORIZONTAL
                } else {
                    // Вертикальный свайп
                    // Определяем сторону экрана (Лево/Право) по точке НАЧАЛА жеста (e1)
                    if (e1.x > screenWidth / 2) {
                        ScrollMode.VOLUME
                    } else {
                        ScrollMode.BRIGHTNESS
                    }
                }
            }
        }

        // 2. Выполняем действие в зависимости от выбранного режима
        val screenHeight = context.resources.displayMetrics.heightPixels

        // distanceY > 0 при свайпе вверх (GestureDetector инвертирует Y для скролла)
        // Нам нужно: вверх -> плюс, вниз -> минус.
        val deltaYPercent = (distanceY / screenHeight) * 1.5f

        when (scrollMode) {
            ScrollMode.VOLUME -> onVolumeChange(deltaYPercent)
            ScrollMode.BRIGHTNESS -> onBrightnessChange(deltaYPercent)
            ScrollMode.HORIZONTAL -> {
                // distanceX > 0 когда палец движется влево.
                // Нам удобнее: палец влево -> минус, палец вправо -> плюс.
                onHorizontalScroll(-distanceX)
            }
            else -> {
                // Режим еще не определен (палец сдвинулся меньше чем на threshold)
                // Ничего не делаем, ждем следующего события
            }
        }

        return true
    }

    fun onActionUp() {
        if (scrollMode != ScrollMode.NONE) {
            onGestureEnd()
            scrollMode = ScrollMode.NONE
        }
    }
}