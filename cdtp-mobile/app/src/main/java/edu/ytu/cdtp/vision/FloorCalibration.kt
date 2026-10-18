package edu.ytu.cdtp.vision

import kotlinx.serialization.Serializable

/**
 * Zemin kalibrasyonu: 4 zemin marker'ının (ID 10–13) merkezleri ile bilinen dikdörtgen
 * köşeleri arasındaki homografi. Plan 7.5 – yöntem (a): zemin marker'ları robot
 * marker'ıyla aynı yükseklikte (takoz üzerinde) olmalı.
 */
@Serializable
data class Calibration(
    /** görüntü → zemin homografisi (satır öncelikli 3×3) */
    val h: List<Double>,
    val imgW: Int,
    val imgH: Int,
    val floorW: Double,
    val floorH: Double,
    /** 10,11,12,13 merkezlerinin görüntü koordinatları (x0,y0,x1,y1,...) */
    val imagePoints: List<Double>,
    val createdWallMs: Long,
    val stationX: Double? = null,
    val stationY: Double? = null,
) {
    fun homography() = Homography(h.toDoubleArray())
    fun imagePoint(i: Int) = Vec2(imagePoints[2 * i], imagePoints[2 * i + 1])
    val station: Vec2? get() = if (stationX != null && stationY != null) Vec2(stationX, stationY) else null

    companion object {
        fun worldCorners(w: Double, hgt: Double) = listOf(Vec2(0.0, 0.0), Vec2(w, 0.0), Vec2(w, hgt), Vec2(0.0, hgt))

        /** Ortalaması alınmış marker merkezlerinden kalibrasyon üretir. */
        fun compute(
            centers: Map<Int, Vec2>,
            imgW: Int,
            imgH: Int,
            floorW: Double,
            floorH: Double,
            stationImg: Vec2?,
            now: Long,
        ): Calibration {
            val src = MarkerIds.FLOOR.map { requireNotNull(centers[it]) { "Marker $it görünmüyor" } }
            val hm = Homography.fromPoints(src, worldCorners(floorW, floorH))
            val st = stationImg?.let { hm.map(it) }
            return Calibration(
                h = hm.m.toList(),
                imgW = imgW, imgH = imgH,
                floorW = floorW, floorH = floorH,
                imagePoints = src.flatMap { listOf(it.x, it.y) },
                createdWallMs = now,
                stationX = st?.x, stationY = st?.y,
            )
        }
    }
}
