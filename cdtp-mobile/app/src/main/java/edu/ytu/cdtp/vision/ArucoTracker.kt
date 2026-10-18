package edu.ytu.cdtp.vision

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect

/** Marker atamaları (Plan 7.5). */
object MarkerIds {
    const val ROBOT = 0
    const val BOX = 1
    const val FLOOR_0 = 10 // (0,0)  kameraya yakın sol
    const val FLOOR_1 = 11 // (W,0)  kameraya yakın sağ
    const val FLOOR_2 = 12 // (W,H)  uzak sağ
    const val FLOOR_3 = 13 // (0,H)  uzak sol
    const val STATION = 20
    val FLOOR = listOf(FLOOR_0, FLOOR_1, FLOOR_2, FLOOR_3)
}

/** Köşeler: OpenCV sırası — sol üst, sağ üst, sağ alt, sol alt (marker'ın kendi çerçevesinde). */
data class DetectedMarker(val id: Int, val corners: List<Vec2>) {
    val center: Vec2 get() = Vec2.mean(corners)
    /** Marker'ın "ileri" kenarının (üst kenar) orta noktası. */
    val front: Vec2 get() = (corners[0] + corners[1]) * 0.5
}

/** Dik (ekrana göre döndürülmüş) görüntü koordinatlarında bir karenin marker'ları. */
data class FrameMarkers(val markers: List<DetectedMarker>, val width: Int, val height: Int, val tMs: Long)

class ArucoTracker {
    private val detector: ArucoDetector by lazy {
        val params = DetectorParameters()
        params.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
        ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50), params)
    }

    fun detect(gray: Mat): List<DetectedMarker> {
        val corners = ArrayList<Mat>()
        val ids = Mat()
        try {
            detector.detectMarkers(gray, corners, ids)
            val out = ArrayList<DetectedMarker>(corners.size)
            val arr = FloatArray(8)
            for (i in corners.indices) {
                corners[i].get(0, 0, arr)
                val id = ids.get(i, 0)[0].toInt()
                out += DetectedMarker(id, List(4) { k -> Vec2(arr[2 * k].toDouble(), arr[2 * k + 1].toDouble()) })
            }
            return out
        } finally {
            corners.forEach { it.release() }
            ids.release()
        }
    }
}

/**
 * CameraX ImageAnalysis → gri (Y düzlemi) → ArUco. Köşe koordinatları görüntünün
 * dönüş açısına göre "dik" çerçeveye çevrilir; böylece önizleme ile aynı yönde olur.
 */
class FrameAnalyzer(
    private val tracker: ArucoTracker,
    private val onFrame: (FrameMarkers) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val mat = Mat(h, w, CvType.CV_8UC1, plane.buffer, plane.rowStride.toLong())
            val raw = try { tracker.detect(mat) } finally { mat.release() }
            val rot = image.imageInfo.rotationDegrees
            val swap = rot == 90 || rot == 270
            val markers = raw.map { m -> m.copy(corners = m.corners.map { rotate(it, rot, w, h) }) }
            onFrame(FrameMarkers(markers, if (swap) h else w, if (swap) w else h, SystemClock.elapsedRealtime()))
        } catch (_: Throwable) {
            // Tek karelik hata akışı durdurmasın.
        } finally {
            image.close()
        }
    }

    private fun rotate(p: Vec2, rot: Int, w: Int, h: Int): Vec2 = when (rot) {
        90 -> Vec2(h - p.y, p.x)
        180 -> Vec2(w - p.x, h - p.y)
        270 -> Vec2(p.y, w - p.x)
        else -> p
    }
}
