package edu.ytu.cdtp.ui.livetest

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ytu.cdtp.task.BoxStatus
import edu.ytu.cdtp.task.MotionState
import edu.ytu.cdtp.task.TestPhase
import edu.ytu.cdtp.ui.components.CameraView
import edu.ytu.cdtp.ui.components.CdtpTopBar
import edu.ytu.cdtp.ui.components.KvRow
import edu.ytu.cdtp.ui.components.LiveLineRow
import edu.ytu.cdtp.ui.components.SectionCard
import edu.ytu.cdtp.ui.components.appContainer
import edu.ytu.cdtp.ui.components.fmt
import edu.ytu.cdtp.ui.theme.Coral
import edu.ytu.cdtp.ui.theme.FailRed
import edu.ytu.cdtp.ui.theme.Navy
import edu.ytu.cdtp.ui.theme.PassGreen
import edu.ytu.cdtp.ui.theme.Teal
import edu.ytu.cdtp.ui.theme.WarnAmber

@Composable
fun LiveTestScreen(onBack: () -> Unit, onDone: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = appContainer()
    val spec by c.tests.spec.collectAsStateWithLifecycle()
    val st by c.tests.state.collectAsStateWithLifecycle()
    val frame by c.vision.frame.collectAsStateWithLifecycle()
    val tel by c.telemetry.latest.collectAsStateWithLifecycle()
    val alive by c.telemetry.alive.collectAsStateWithLifecycle()
    val recordVideo by c.settings.recordVideo.collectAsStateWithLifecycle()
    val busy = st.phase == TestPhase.RUNNING || st.phase == TestPhase.FINALIZING

    BackHandler(enabled = busy) { Toast.makeText(ctx, "Önce \"Testi Bitir\"e basın", Toast.LENGTH_SHORT).show() }
    LaunchedEffect(st.phase, st.recordId) {
        if (st.phase == TestPhase.DONE && st.recordId != null) {
            val id = st.recordId!!
            c.tests.reset()
            onDone(id)
        }
    }

    Scaffold(topBar = { CdtpTopBar(spec?.title ?: "Canlı Test", if (busy) null else onBack) }) { pad ->
        Row(Modifier.padding(pad).fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1.6f).fillMaxHeight()) {
                CameraView(Modifier.fillMaxSize(), withVideo = recordVideo)
                if (st.recording) {
                    Row(
                        Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).background(FailRed, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("REC", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Sayaç + durum + yön
                Column(
                    Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(12.dp)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val sec = st.elapsedMs / 1000.0
                    Text(
                        String.format(java.util.Locale.US, "%02d:%04.1f", (sec / 60).toInt(), sec % 60),
                        color = Color.White, fontSize = 44.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    )
                    val m = frame?.motion ?: MotionState.NOT_VISIBLE
                    val mc = when (m) {
                        MotionState.STATIC -> PassGreen
                        MotionState.MOVING -> Teal
                        MotionState.TURNING -> Coral
                        MotionState.RECOVERING -> WarnAmber
                        MotionState.FALLEN -> FailRed
                        MotionState.NOT_VISIBLE -> Color.Gray
                    }
                    Text(m.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.background(mc, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 4.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("Yön: ${frame?.direction?.label ?: "—"}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }

                SectionCard("Canlı değerler") {
                    val p = frame?.robotPose
                    KvRow("Konum", p?.let { "(${fmt(it.pos.x)}, ${fmt(it.pos.y)}) m · ${fmt(it.headingDeg, 0)}°" } ?: "görünmüyor", bold = true)
                    KvRow("Hız", "${fmt(frame?.speedMps ?: 0.0)} m/s · ${fmt(frame?.omegaDps ?: 0.0, 0)} °/s")
                    val box = frame?.boxStatus ?: BoxStatus.UNKNOWN
                    KvRow("Kutu", box.label, when (box) { BoxStatus.ON_ROBOT -> PassGreen; BoxStatus.DROPPED -> FailRed; else -> WarnAmber }, true)
                    KvRow("Eğim", tel?.let { "${fmt(it.tiltDeg, 1)}°" } ?: "-", if (alive) Color.Unspecified else FailRed, true)
                    KvRow("Şarj / batarya", tel?.let { "${if (it.charging) "ŞARJDA" else "yok"} · %${it.batteryPct}" } ?: "-", if (alive) Color.Unspecified else FailRed)
                    KvRow("Bağlantı", if (alive) "veri akıyor" else "KOPUK", if (alive) PassGreen else FailRed, true)
                }

                if (st.lines.isNotEmpty()) SectionCard("Değerlendirme (canlı)") { st.lines.forEach { LiveLineRow(it) } }

                when (st.phase) {
                    TestPhase.READY -> Button(
                        onClick = { c.tests.start() },
                        enabled = spec != null,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PassGreen),
                    ) { Text("TESTİ BAŞLAT", fontSize = 22.sp, fontWeight = FontWeight.Black) }
                    TestPhase.RUNNING -> Button(
                        onClick = { c.tests.stop() },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FailRed),
                    ) { Text("TESTİ BİTİR", fontSize = 22.sp, fontWeight = FontWeight.Black) }
                    TestPhase.FINALIZING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)); Text(st.message ?: "Kaydediliyor…")
                    }
                    TestPhase.DONE -> Text("Kaydedildi")
                    TestPhase.ERROR -> {
                        Text(st.message ?: "Hata", color = FailRed, fontWeight = FontWeight.Bold)
                        Button(onClick = { c.tests.reset() }) { Text("Tamam") }
                    }
                }
                if (st.phase == TestPhase.READY) {
                    Text(
                        "Telefon sabit olmalı. Başlat'a basıldığı an başlangıç zaman damgasıdır" + if (recordVideo) " ve kamera kaydı başlar." else ".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
