package top.rootu.dddplayer.utils

import android.graphics.Bitmap
import android.graphics.Color
import io.nayuki.qrcodegen.QrCode

/**
 * QR-код как картинка, без сети: раньше картинку отдавал api.qrserver.com,
 * а на старых Android (устаревшие корневые сертификаты) HTTPS-запрос не проходил и QR не появлялся.
 */
object QrBitmap {

    private const val BORDER = 4 // Поле вокруг кода в модулях (по стандарту)
    private const val MODULE_PX = 10 // Пикселей на модуль: ImageView масштабирует без размытия краев

    fun create(text: String): Bitmap {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val modules = qr.size + BORDER * 2
        val sizePx = modules * MODULE_PX
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val qy = y / MODULE_PX - BORDER
            for (x in 0 until sizePx) {
                val qx = x / MODULE_PX - BORDER
                // getModule вне кода возвращает false (светлый модуль)
                pixels[y * sizePx + x] = if (qr.getModule(qx, qy)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
