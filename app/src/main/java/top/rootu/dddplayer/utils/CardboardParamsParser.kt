package top.rootu.dddplayer.utils

import android.net.Uri
import android.util.Base64
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

data class CardboardProfile(
    val k1: Float,
    val k2: Float,
    val screenToLensDistance: Float,
    val interLensDistance: Float
)

object CardboardParamsParser {

    fun parse(uri: Uri): CardboardProfile? {
        val paramP = uri.getQueryParameter("p") ?: return null

        return try {
            // 1. Base64 Decode (URL Safe)
            val compressed = Base64.decode(paramP, Base64.URL_SAFE or Base64.NO_WRAP)

            // 2. GZIP Decompress
            val inputStream = GZIPInputStream(ByteArrayInputStream(compressed))
            val protoBytes = inputStream.readBytes()

            // 3. Parse Protobuf manually
            parseProtobuf(protoBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseProtobuf(data: ByteArray): CardboardProfile {
        // Дефолтные значения (Cardboard v2)
        var k1 = 0.34f
        var k2 = 0.10f
        var screenToLens = 0.039f
        var interLens = 0.064f

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val coeffs = mutableListOf<Float>()

        while (buffer.hasRemaining()) {
            val tag = buffer.get().toInt() and 0xFF
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                4 -> { // Inter-lens distance (float)
                    if (wireType == 5) interLens = buffer.float
                    else skip(buffer, wireType)
                }
                7 -> { // Screen-to-lens distance (float)
                    if (wireType == 5) screenToLens = buffer.float
                    else skip(buffer, wireType)
                }
                8 -> { // Distortion coefficients (repeated float)
                    if (wireType == 2) { // Packed repeated
                        val length = readVarInt(buffer)
                        val end = buffer.position() + length
                        while (buffer.position() < end) {
                            coeffs.add(buffer.float)
                        }
                    } else if (wireType == 5) { // Non-packed
                        coeffs.add(buffer.float)
                    } else {
                        skip(buffer, wireType)
                    }
                }
                else -> skip(buffer, wireType)
            }
        }

        if (coeffs.size >= 1) k1 = coeffs[0]
        if (coeffs.size >= 2) k2 = coeffs[1]

        return CardboardProfile(k1, k2, screenToLens, interLens)
    }

    private fun readVarInt(buffer: ByteBuffer): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buffer.get().toInt()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result
    }

    private fun skip(buffer: ByteBuffer, wireType: Int) {
        when (wireType) {
            0 -> readVarInt(buffer) // Varint
            1 -> buffer.position(buffer.position() + 8) // 64-bit
            2 -> { // Length delimited
                val len = readVarInt(buffer)
                buffer.position(buffer.position() + len)
            }
            5 -> buffer.position(buffer.position() + 4) // 32-bit
            else -> throw IllegalArgumentException("Unknown wire type: $wireType")
        }
    }
}