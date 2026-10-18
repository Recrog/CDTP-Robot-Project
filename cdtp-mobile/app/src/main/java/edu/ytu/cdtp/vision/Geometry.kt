package edu.ytu.cdtp.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun norm() = hypot(x, y)
    fun dist(o: Vec2) = (this - o).norm()

    companion object {
        fun unit(deg: Double) = Vec2(cos(Math.toRadians(deg)), sin(Math.toRadians(deg)))
        fun mean(ps: List<Vec2>) = Vec2(ps.sumOf { it.x } / ps.size, ps.sumOf { it.y } / ps.size)
    }
}

/** Zemin koordinatlarında (metre) robot konumu ve yönü (derece, CCW +). */
data class Pose(val pos: Vec2, val headingDeg: Double)

object Angles {
    fun wrap180(d: Double): Double {
        var a = d % 360.0
        if (a > 180) a -= 360
        if (a <= -180) a += 360
        return a
    }

    /** a − b, (−180, 180] aralığında. */
    fun diff(a: Double, b: Double) = wrap180(a - b)

    fun of(v: Vec2) = Math.toDegrees(atan2(v.y, v.x))
}

/** 3×3 projektif dönüşüm (satır öncelikli). Görüntü pikseli → zemin metresi. */
class Homography(val m: DoubleArray) {
    init { require(m.size == 9) }

    fun map(p: Vec2): Vec2 {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        return Vec2((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    fun inverse(): Homography {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = -(a[3] * a[8] - a[5] * a[6])
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(abs(det) > 1e-15) { "Tekil homografi" }
        val inv = doubleArrayOf(
            c00, -(a[1] * a[8] - a[2] * a[7]), a[1] * a[5] - a[2] * a[4],
            c01, a[0] * a[8] - a[2] * a[6], -(a[0] * a[5] - a[2] * a[3]),
            c02, -(a[0] * a[7] - a[1] * a[6]), a[0] * a[4] - a[1] * a[3],
        )
        return Homography(DoubleArray(9) { inv[it] / det })
    }

    companion object {
        /** 4 nokta eşlemesinden tam homografi (h33 = 1), Gauss eliminasyonu. */
        fun fromPoints(src: List<Vec2>, dst: List<Vec2>): Homography {
            require(src.size == 4 && dst.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i]
                val (u, v) = dst[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            for (col in 0 until 8) {
                val piv = (col until 8).maxBy { abs(a[it][col]) }
                require(abs(a[piv][col]) > 1e-12) { "Dejenere nokta seti (marker'lar aynı doğru üzerinde olabilir)" }
                val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (c in col..8) a[r][c] -= f * a[col][c]
                }
            }
            return Homography(DoubleArray(9) { if (it == 8) 1.0 else a[it][8] / a[it][it] })
        }
    }
}
