package top.rootu.dddplayer.utils.afr

import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.round

class RuntimeFpsDetector {

    private var isDetectionRunning = AtomicBoolean(false)
    private var currentMetadataListener: VideoFrameMetadataListener? = null

    // Храним успешно найденный FPS для текущего видео
    var detectedFrameRate: Float? = null
        private set

    /**
     * Запускает процесс детекции.
     * @param player Экземпляр ExoPlayer
     * @param handler Handler главного потока для возврата результата
     * @param onSuccess Колбек при успешном нахождении CFR (постоянного FPS)
     * @param onVfrDetected Колбек, если обнаружен VFR (переменный FPS)
     */
    fun start(
        player: ExoPlayer,
        handler: Handler,
        onSuccess: (fps: Float, format: Format) -> Unit,
        onVfrDetected: () -> Unit
    ) {
        if (isDetectionRunning.getAndSet(true)) return

        val listener = object : VideoFrameMetadataListener {
            var isDone = false
            var warmup = 0
            val WARMUP_FRAMES = 20

            val TARGET_DURATION_US = 2_000_000L
            val MIN_SAMPLES = 30
            val ptsList = ArrayList<Long>(120)

            override fun onVideoFrameAboutToBeRendered(
                presentationTimeUs: Long,
                releaseTimeNs: Long,
                format: Format,
                mediaFormat: android.media.MediaFormat?
            ) {
                if (isDone) return

                if (warmup < WARMUP_FRAMES) {
                    warmup++
                    return
                }

                if (ptsList.isNotEmpty()) {
                    val delta = presentationTimeUs - ptsList.last()
                    if (delta <= 0 || delta > 250_000L) {
                        ptsList.clear()
                        warmup = 0
                        return
                    }
                }

                ptsList.add(presentationTimeUs)

                val elapsed = ptsList.last() - ptsList.first()
                if (elapsed >= TARGET_DURATION_US && ptsList.size >= MIN_SAMPLES) {
                    isDone = true

                    val fps = computeFpsByRobustRegression(ptsList)

                    handler.post {
                        stop(player)
                        if (fps > 0.0) {
                            val finalFps = snapToExactRate(fps)
                            detectedFrameRate = finalFps.toFloat()
                            onSuccess(finalFps.toFloat(), format)
                        } else {
                            onVfrDetected()
                        }
                    }
                }
            }
        }

        currentMetadataListener = listener
        player.setVideoFrameMetadataListener(listener)
    }

    /**
     * Останавливает детекцию и отписывает слушатель.
     */
    fun stop(player: ExoPlayer?) {
        isDetectionRunning.set(false)
        currentMetadataListener?.let {
            player?.clearVideoFrameMetadataListener(it)
        }
        currentMetadataListener = null
    }

    /**
     * Сбрасывает сохраненное состояние (вызывать при смене видео).
     */
    fun reset() {
        detectedFrameRate = null
        // stop() должен вызываться отдельно с передачей player
    }

    /**
     * Вычисляет FPS с использованием центрированной линейной регрессии (OLS)
     * и восстанавливает ось X для защиты от пропущенных кадров.
     * Возвращает FPS, либо 0.0, если видео имеет переменный фреймрейт (VFR) или повреждено.
     */
    private fun computeFpsByRobustRegression(pts: List<Long>): Double {
        val n = pts.size
        if (n < 2) return 0.0

        // Находим медианную дельту
        val deltas = LongArray(n - 1)
        for (i in 0 until n - 1) {
            deltas[i] = pts[i + 1] - pts[i]
        }
        deltas.sort()
        val medianDelta = if (deltas.size % 2 == 0) {
            (deltas[deltas.size / 2 - 1] + deltas[deltas.size / 2]) / 2.0
        } else {
            deltas[deltas.size / 2].toDouble()
        }

        if (medianDelta <= 0) return 0.0

        // Восстанавливаем ось X (номера кадров) и находим средние значения (Mean)
        val xValues = DoubleArray(n)
        val yValues = DoubleArray(n)
        var currentFrameIndex = 0.0
        var sumX = 0.0
        var sumY = 0.0
        val firstPts = pts.first()

        for (i in 0 until n) {
            if (i > 0) {
                val delta = pts[i] - pts[i - 1]
                val framesPassed = round(delta.toDouble() / medianDelta)
                currentFrameIndex += framesPassed
            }
            xValues[i] = currentFrameIndex
            yValues[i] = (pts[i] - firstPts).toDouble()

            sumX += xValues[i]
            sumY += yValues[i]
        }

        val meanX = sumX / n
        val meanY = sumY / n

        // Центрированная линейная регрессия (Centered OLS)
        var num = 0.0
        var den = 0.0
        var sst = 0.0 // Total sum of squares (для вычисления R^2)

        for (i in 0 until n) {
            val dx = xValues[i] - meanX
            val dy = yValues[i] - meanY

            num += dx * dy
            den += dx * dx
            sst += dy * dy
        }

        if (den == 0.0) return 0.0

        val slope = num / den // Длительность кадра в микросекундах

        // Защита от отрицательного или нулевого slope (поврежденные PTS)
        if (slope <= 0.0) return 0.0

        // Вычисляем R^2 (Коэффициент детерминации) для проверки на VFR
        // Математически для центрированной OLS: SSR = slope^2 * Σ(x - meanX)^2
        val ssr = slope * slope * den
        val rSquared = if (sst != 0.0) ssr / sst else 1.0

        // Если R^2 меньше 0.99, значит точки сильно отклоняются от прямой линии.
        // Это признак Variable Frame Rate (VFR). Для VFR мы не должны применять AFR.
        if (rSquared < 0.99) {
            return 0.0
        }

        return 1_000_000.0 / slope
    }

    private fun snapToExactRate(measuredFps: Double): Double {
        val knownRates = listOf(15.0, 23.976, 24.0, 25.0, 29.970, 30.0, 48.0, 50.0, 59.940, 60.0)
        var bestMatch = measuredFps
        var minError = Double.MAX_VALUE

        for (rate in knownRates) {
            val error = abs(measuredFps - rate)
            if (error < minError) {
                minError = error
                bestMatch = rate
            }
        }

        return if (minError < 0.05) bestMatch else measuredFps
    }
}