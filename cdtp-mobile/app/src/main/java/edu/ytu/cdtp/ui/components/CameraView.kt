package edu.ytu.cdtp.ui.components

import android.graphics.Paint
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ytu.cdtp.camera.CameraController
import edu.ytu.cdtp.vision.FrameState
import edu.ytu.cdtp.vision.MarkerIds
import edu.ytu.cdtp.vision.Vec2
import edu.ytu.cdtp.ui.theme.Coral
import edu.ytu.cdtp.ui.theme.Teal
import edu.ytu.cdtp.ui.theme.WarnAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kamera önizlemesi + marker/iz/zemin overlay'i. Bu bileşen ekrandayken
 * kamera AppContainer.camera olarak kayıtlıdır (TestController video için kullanır).
 */
@Composable
fun CameraView(modifier: Modifier = Modifier, withVideo: Boolean, showTrail: Boolean = true) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val c = appContainer()
    val frame by c.vision.frame.collectAsStateWithLifecycle()
    val cal by c.settings.calibration.collectAsStateWithLifecycle()
    val cfg by c.settings.evalConfig.collectAsStateWithLifecycle()
    var camMsg by remember { mutableStateOf("Kamera başlatılıyor…") }
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(owner, withVideo) {
        val controller = CameraController(ctx)
        c.camera = controller
        controller.bind(owner, previewView, c.vision.analyzer(), c.vision.executor, withVideo) { camMsg = it }
        onDispose {
            controller.unbind()
            if (c.camera === controller) c.camera = null
        }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        val fs = frame
        if (fs != null) {
            val hInv = remember(cal) { cal?.let { runCatching { it.homography().inverse() }.getOrNull() } }
            Canvas(Modifier.fillMaxSize()) {
                val s = minOf(size.width / fs.imgW, size.height / fs.imgH)
                val ox = (size.width - fs.imgW * s) / 2
                val oy = (size.height - fs.imgH * s) / 2
                fun v(p: Vec2) = Offset(ox + p.x.toFloat() * s, oy + p.y.toFloat() * s)

                // Zemin alanı + 0,5 m ızgara + istasyon yerleşme dairesi
                val calOk = cal != null && cal!!.imgW == fs.imgW && cal!!.imgH == fs.imgH
                if (hInv != null && calOk) {
                    val cl = cal!!
                    fun w(x: Double, y: Double) = v(hInv.map(Vec2(x, y)))
                    var gx = 0.0
                    while (gx <= cl.floorW + 1e-6) { drawLine(Teal.copy(alpha = 0.35f), w(gx, 0.0), w(gx, cl.floorH), 1.5f); gx += 0.5 }
                    var gy = 0.0
                    while (gy <= cl.floorH + 1e-6) { drawLine(Teal.copy(alpha = 0.35f), w(0.0, gy), w(cl.floorW, gy), 1.5f); gy += 0.5 }
                    val corners = listOf(w(0.0, 0.0), w(cl.floorW, 0.0), w(cl.floorW, cl.floorH), w(0.0, cl.floorH))
                    polygon(corners, Teal, 3f)
                    fs.stationPos?.let { st ->
                        val circle = (0 until 36).map { k ->
                            val a = 2 * PI * k / 36
                            w(st.x + cfg.dockDistM * cos(a), st.y + cfg.dockDistM * sin(a))
                        }
                        polygon(circle, Color(0xFF9C27B0), 2.5f)
                    }
                }

                if (showTrail && fs.trail.size > 1) {
                    val path = Path()
                    fs.trail.forEachIndexed { i, p -> val o = v(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                    drawPath(path, Coral.copy(alpha = 0.85f), style = Stroke(width = 4f))
                }

                for (m in fs.markers) {
                    val col = when (m.id) {
                        MarkerIds.ROBOT -> Coral
                        MarkerIds.BOX -> WarnAmber
                        in MarkerIds.FLOOR -> Teal
                        MarkerIds.STATION -> Color(0xFF9C27B0)
                        else -> Color.LightGray
                    }
                    polygon(m.corners.map { v(it) }, col, 4f)
                    label(v(m.center), "${m.id}", col)
                }
                fs.robotImg?.let { m ->
                    val c0 = v(m.center); val f0 = v(m.front)
                    val tip = Offset(c0.x + (f0.x - c0.x) * 2.2f, c0.y + (f0.y - c0.y) * 2.2f)
                    drawLine(Coral, c0, tip, 6f)
                    drawCircle(Coral, 8f, tip)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            val fs2 = frame
            Text(
                if (fs2 == null) camMsg else "${fmt(fs2.fps, 0)} fps · ${fs2.markers.size} marker · ${fs2.imgW}×${fs2.imgH}" +
                    if (fs2.calibrated) "" else " · KALİBRASYON YOK",
                color = Color.White, fontSize = 12.sp,
            )
            val shift = fs2?.cameraShiftPx
            if (shift != null && shift > cfg.cameraMovedPx) {
                Text("⚠ KAMERA OYNADI (${fmt(shift, 0)} px) — yeniden kalibre edin", color = WarnAmber, fontSize = 13.sp)
            }
        }
    }
}

private fun DrawScope.polygon(pts: List<Offset>, color: Color, width: Float) {
    if (pts.size < 2) return
    val p = Path()
    pts.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
    p.close()
    drawPath(p, color, style = Stroke(width = width))
}

private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 34f
    isFakeBoldText = true
}

private fun DrawScope.label(at: Offset, text: String, color: Color) {
    labelPaint.color = android.graphics.Color.BLACK
    labelPaint.style = Paint.Style.STROKE; labelPaint.strokeWidth = 6f
    drawContext.canvas.nativeCanvas.drawText(text, at.x - 10f, at.y + 12f, labelPaint)
    labelPaint.style = Paint.Style.FILL
    labelPaint.color = color.toArgb()
    drawContext.canvas.nativeCanvas.drawText(text, at.x - 10f, at.y + 12f, labelPaint)
}
