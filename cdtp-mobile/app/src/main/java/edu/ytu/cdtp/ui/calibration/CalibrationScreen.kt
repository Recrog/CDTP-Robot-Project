package edu.ytu.cdtp.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ytu.cdtp.report.PdfExporter
import edu.ytu.cdtp.ui.components.CameraView
import edu.ytu.cdtp.ui.components.CdtpTopBar
import edu.ytu.cdtp.ui.components.KvRow
import edu.ytu.cdtp.ui.components.SectionCard
import edu.ytu.cdtp.ui.components.appContainer
import edu.ytu.cdtp.ui.components.fmt
import edu.ytu.cdtp.ui.theme.FailRed
import edu.ytu.cdtp.ui.theme.PassGreen
import edu.ytu.cdtp.ui.theme.Teal
import edu.ytu.cdtp.ui.theme.WarnAmber
import edu.ytu.cdtp.vision.Angles
import edu.ytu.cdtp.vision.Calibration
import edu.ytu.cdtp.vision.MarkerIds
import edu.ytu.cdtp.vision.Pose
import edu.ytu.cdtp.vision.Vec2
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val frame by c.vision.frame.collectAsStateWithLifecycle()
    val cal by c.settings.calibration.collectAsStateWithLifecycle()
    val cfg by c.settings.evalConfig.collectAsStateWithLifecycle()
    var wText by remember { mutableStateOf((cal?.floorW ?: 1.5).toString()) }
    var hText by remember { mutableStateOf((cal?.floorH ?: 1.0).toString()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pointA by remember { mutableStateOf<Pose?>(null) }

    val visible = frame?.markers?.map { it.id }?.toSet() ?: emptySet()
    val allFloor = MarkerIds.FLOOR.all { it in visible }

    fun calibrate() {
        val w = wText.replace(',', '.').toDoubleOrNull()
        val h = hText.replace(',', '.').toDoubleOrNull()
        if (w == null || h == null || w <= 0 || h <= 0) { msg = "Genişlik/yükseklik geçersiz"; return }
        busy = true; msg = "15 kare toplanıyor…"
        scope.launch {
            val sums = HashMap<Int, MutableList<Vec2>>()
            val station = ArrayList<Vec2>()
            var n = 0
            var lastT = -1L
            var size: Pair<Int, Int>? = null
            withTimeoutOrNull(6000) {
                c.vision.frame.first { fs ->
                    if (fs == null || fs.tMs == lastT) return@first false
                    lastT = fs.tMs
                    val by = fs.markers.associateBy { it.id }
                    if (MarkerIds.FLOOR.all { it in by }) {
                        MarkerIds.FLOOR.forEach { id -> sums.getOrPut(id) { ArrayList() } += by.getValue(id).center }
                        by[MarkerIds.STATION]?.let { station += it.center }
                        size = fs.imgW to fs.imgH
                        n++
                    }
                    n >= 15
                }
            }
            runCatching {
                if (n < 5) error("Yeterli kare yok ($n). 4 zemin marker'ı da görünür olmalı.")
                val centers = sums.mapValues { Vec2.mean(it.value) }
                val (iw, ih) = size!!
                val result = Calibration.compute(centers, iw, ih, w, h, if (station.isEmpty()) null else Vec2.mean(station), System.currentTimeMillis())
                c.settings.saveCalibration(result)
                c.vision.clearTrail()
                "Kalibrasyon OK ($n kare)" + if (result.station != null) " · istasyon konumu kaydedildi" else " · istasyon (ID 20) görülmedi"
            }.fold({ msg = it }, { msg = it.message })
            busy = false
        }
    }

    Scaffold(topBar = { CdtpTopBar("Kamera / Zemin Kalibrasyonu", onBack) }) { pad ->
        Row(Modifier.padding(pad).fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraView(Modifier.weight(1.6f).fillMaxHeight(), withVideo = false)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCard("Zemin dikdörtgeni (marker merkezleri arası)") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(wText, { wText = it }, label = { Text("10→11 (m)") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(hText, { hText = it }, label = { Text("10→13 (m)") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (MarkerIds.FLOOR + MarkerIds.STATION + MarkerIds.ROBOT + MarkerIds.BOX).forEach { id ->
                            val ok = id in visible
                            Box(
                                Modifier.background(if (ok) PassGreen else Color.Gray, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                            ) { Text("$id", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Text("10 yakın-sol · 11 yakın-sağ · 12 uzak-sağ · 13 uzak-sol · 20 istasyon · 0 robot · 1 kutu",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { calibrate() }, enabled = allFloor && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Kalibre ediliyor…" else if (allFloor) "Kalibre et" else "4 zemin marker'ı görünmeli")
                    }
                    msg?.let { Text(it, color = if (it.startsWith("Kalibrasyon OK")) PassGreen else WarnAmber, fontWeight = FontWeight.Bold) }
                }
                SectionCard("Durum") {
                    val cl = cal
                    if (cl == null) KvRow("Kalibrasyon", "YOK", FailRed, true)
                    else {
                        KvRow("Kalibrasyon", "OK · ${cl.floorW} × ${cl.floorH} m", PassGreen, true)
                        KvRow("Tarih", PdfExporter.fmtDate(cl.createdWallMs))
                        KvRow("Çözünürlük", "${cl.imgW}×${cl.imgH}")
                        val shift = frame?.cameraShiftPx
                        KvRow("Kalibrasyondan sapma", shift?.let { "${fmt(it, 1)} px" } ?: "zemin marker'ı görünmüyor",
                            if ((shift ?: 0.0) > cfg.cameraMovedPx) FailRed else PassGreen)
                    }
                    KvRow("İstasyon konumu", frame?.stationPos?.let { "(${fmt(it.x)}, ${fmt(it.y)}) m" } ?: "bilinmiyor")
                }
                SectionCard("Canlı ölçüm (doğruluk testi: şerit metreyle 0,5 / 1,0 / 1,5 m, hata < ±5 cm)") {
                    val p = frame?.robotPose
                    KvRow("Robot (ID 0)", p?.let { "(${fmt(it.pos.x, 3)}, ${fmt(it.pos.y, 3)}) m · ${fmt(it.headingDeg, 0)}°" } ?: "görünmüyor", bold = true)
                    KvRow("Durum / yön", "${frame?.motion?.label ?: "-"} / ${frame?.direction?.label ?: "-"}")
                    KvRow("Hız / açısal hız", "${fmt(frame?.speedMps ?: 0.0, 3)} m/s · ${fmt(frame?.omegaDps ?: 0.0, 0)} °/s")
                    KvRow("Kutu (ID 1)", frame?.boxStatus?.label ?: "-")
                    val a = pointA
                    if (a != null && p != null) {
                        KvRow("A → şimdi mesafe", "${fmt(a.pos.dist(p.pos), 3)} m", Teal, true)
                        KvRow("A → şimdi dönüş", "${fmt(Angles.diff(p.headingDeg, a.headingDeg), 1)}°", Teal, true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pointA = frame?.robotPose }, enabled = p != null) { Text("A noktasını işaretle") }
                        OutlinedButton(onClick = { c.vision.clearTrail() }) { Text("İzi temizle") }
                    }
                }
            }
        }
    }
}

