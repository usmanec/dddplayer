package top.rootu.dddplayer.utils

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.pow

object DuboisMath {

    // Базисные матрицы
    private val mXYZ = arrayOf(
        floatArrayOf(0.4124f, 0.3576f, 0.1805f),
        floatArrayOf(0.2126f, 0.7152f, 0.0722f),
        floatArrayOf(0.0193f, 0.1192f, 0.9505f)
    )

    private val mLMS = arrayOf(
        floatArrayOf(0.3811f, 0.5783f, 0.0402f),
        floatArrayOf(0.1967f, 0.7244f, 0.0782f),
        floatArrayOf(0.0241f, 0.1288f, 0.8444f)
    )

    data class DuboisParams(
        val colorLeft: Int,
        val colorRight: Int,
        val leakL: Float, // 0.0 to 0.5 (0% - 50%)
        val leakR: Float,
        val useLms: Boolean
    )

    data class ResultMatrices(
        val left: FloatArray,
        val right: FloatArray,
        val isValid: Boolean = true
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ResultMatrices

            if (isValid != other.isValid) return false
            if (!left.contentEquals(other.left)) return false
            if (!right.contentEquals(other.right)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = isValid.hashCode()
            result = 31 * result + left.contentHashCode()
            result = 31 * result + right.contentHashCode()
            return result
        }
    }

    fun calculate(params: DuboisParams): ResultMatrices {
        val cL = colorToLinear(params.colorLeft)
        val cR = colorToLinear(params.colorRight)

        val mBasis = if (params.useLms) mLMS else mXYZ

        // Моделирование фильтров с раздельной утечкой
        val gray = floatArrayOf(0.299f, 0.587f, 0.114f)
        val effL = FloatArray(3) { i -> cL[i] + cR[i] * params.leakL * gray[i] }
        val effR = FloatArray(3) { i -> cR[i] + cL[i] * params.leakR * gray[i] }

        // Составляем матрицу Q (6x3)
        // Q = [ Basis * effL ]
        //     [ Basis * effR ]
        val q = Array(6) { FloatArray(3) }
        for (row in 0 until 3) {
            q[row][0] = mBasis[row][0] * effL[0]
            q[row][1] = mBasis[row][1] * effL[1]
            q[row][2] = mBasis[row][2] * effL[2]
        }
        for (row in 0 until 3) {
            q[row + 3][0] = mBasis[row][0] * effR[0]
            q[row + 3][1] = mBasis[row][1] * effR[1]
            q[row + 3][2] = mBasis[row][2] * effR[2]
        }

        // Least Squares: (Q^T * Q)^-1 * Q^T
        val qt = transpose(q) // 3x6
        val qtq = multiply(qt, q) // 3x3

        // Если инверсия не удалась, возвращаем цвета и помечаем как Invalid
        val qtqInv = invert3x3(qtq) ?: return ResultMatrices(
            floatArrayOf(cL[0], 0f, 0f, 0f, cL[1], 0f, 0f, 0f, cL[2]),
            floatArrayOf(cR[0], 0f, 0f, 0f, cR[1], 0f, 0f, 0f, cR[2]),
            isValid = false
        )

        val mPInv = multiply(qtqInv, qt) // 3x6

        // Extract pLeft (3x3) and pRight (3x3) from pseudo-inverse
        val pLeft = Array(3) { i -> floatArrayOf(mPInv[i][0], mPInv[i][1], mPInv[i][2]) }
        val pRight = Array(3) { i -> floatArrayOf(mPInv[i][3], mPInv[i][4], mPInv[i][5]) }

        // Final = P * Basis
        val finalL = multiply3x3(pLeft, mBasis)
        val finalR = multiply3x3(pRight, mBasis)


        // Normalize (White balance fix)
        normalize(finalL, finalR)

        return ResultMatrices(flatten(finalL), flatten(finalR), true)
    }

    private fun sRGBtoLinear(x: Float): Float {
        return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun colorToLinear(color: Int): FloatArray {
        return floatArrayOf(
            sRGBtoLinear(Color.red(color) / 255f),
            sRGBtoLinear(Color.green(color) / 255f),
            sRGBtoLinear(Color.blue(color) / 255f)
        )
    }

    // --- Linear Algebra Utils ---

    private fun transpose(m: Array<FloatArray>): Array<FloatArray> {
        val rows = m.size
        val cols = m[0].size
        val res = Array(cols) { FloatArray(rows) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                res[j][i] = m[i][j]
            }
        }
        return res
    }

    // Multiply (m x n) by (n x p) -> (m x p)
    private fun multiply(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val m = a.size
        val n = a[0].size
        val p = b[0].size
        val res = Array(m) { FloatArray(p) }
        for (i in 0 until m) {
            for (j in 0 until p) {
                var sum = 0f
                for (k in 0 until n) sum += a[i][k] * b[k][j]
                res[i][j] = sum
            }
        }
        return res
    }

    private fun multiply3x3(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        return multiply(a, b)
    }

    private fun invert3x3(m: Array<FloatArray>): Array<FloatArray>? {
        val det = m[0][0] * (m[1][1] * m[2][2] - m[2][1] * m[1][2]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

        if (abs(det) < 1e-9) return null
        val invDet = 1.0f / det

        val res = Array(3) { FloatArray(3) }
        res[0][0] = (m[1][1] * m[2][2] - m[2][1] * m[1][2]) * invDet
        res[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * invDet
        res[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * invDet
        res[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) * invDet
        res[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * invDet
        res[1][2] = (m[1][0] * m[0][2] - m[0][0] * m[1][2]) * invDet
        res[2][0] = (m[1][0] * m[2][1] - m[2][0] * m[1][1]) * invDet
        res[2][1] = (m[2][0] * m[0][1] - m[0][0] * m[2][1]) * invDet
        res[2][2] = (m[0][0] * m[1][1] - m[1][0] * m[0][1]) * invDet
        return res
    }

    private fun normalize(mL: Array<FloatArray>, mR: Array<FloatArray>) {
        for (row in 0 until 3) {
            var sum = 0f
            for (col in 0 until 3) sum += mL[row][col]
            for (col in 0 until 3) sum += mR[row][col]
            if (abs(sum) > 0.0001f) {
                val scale = 1.0f / sum
                for (col in 0 until 3) {
                    mL[row][col] *= scale
                    mR[row][col] *= scale
                }
            }
        }
    }

    private fun flatten(m: Array<FloatArray>): FloatArray {
        return floatArrayOf(
            m[0][0], m[0][1], m[0][2],
            m[1][0], m[1][1], m[1][2],
            m[2][0], m[2][1], m[2][2]
        )
    }
}