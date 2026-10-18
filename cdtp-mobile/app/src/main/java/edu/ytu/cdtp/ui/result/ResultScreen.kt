package edu.ytu.cdtp.ui.result

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import edu.ytu.cdtp.report.PdfExporter
import edu.ytu.cdtp.report.RecordPayload
import edu.ytu.cdtp.report.RecordRepository
import edu.ytu.cdtp.report.db.TestRecordEntity
import edu.ytu.cdtp.ui.components.CdtpTopBar
import edu.ytu.cdtp.ui.components.KvRow
import edu.ytu.cdtp.ui.components.SectionCard
import edu.ytu.cdtp.ui.components.VerdictBanner
import edu.ytu.cdtp.ui.components.appContainer
import edu.ytu.cdtp.ui.components.fmt
import edu.ytu.cdtp.ui.theme.FailRed
import edu.ytu.cdtp.ui.theme.PassGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sonuç: karar, gerekçe, ölçümler, hash'ler. Düzenleme butonu YOK. */
@Composable
fun ResultScreen(id: Long, onBack: () -> Unit, onNewTest: () -> Unit) {
    val ctx = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var entity by remember { mutableStateOf<TestRecordEntity?>(null) }
    var payload by remember { mutableStateOf<RecordPayload?>(null) }
    LaunchedEffect(id) {
        val e = c.records.byId(id)
        entity = e
        payload = e?.let { withContext(Dispatchers.Default) { RecordRepository.decode(it) } }
    }

    Scaffold(topBar = { CdtpTopBar(payload?.let { "Kayıt #${it.index} · ${it.taskTitle}" } ?: "Sonuç", onBack) }) { pad ->
        val e = entity
        val p = payload
        if (e == null || p == null) {
            Text("Yükleniyor…", Modifier.padding(pad).padding(16.dp))
            return@Scaffold
        }
        Row(Modifier.padding(pad).fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VerdictBanner(p.passed)
                SectionCard("Gerekçe (kurallar)") {
                    p.rules.forEach { r ->
                        Text((if (r.passed) "✓ " else "✗ ") + r.name, color = if (r.passed) PassGreen else FailRed, fontWeight = FontWeight.Bold)
                        Text(r.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 18.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { PdfExporter.export(ctx, e, p) }
                            PdfExporter.share(ctx, f, "application/pdf")
                        }
                    }) { Text("PDF paylaş") }
                    p.video?.let { v ->
                        OutlinedButton(onClick = {
                            val f = File(v.path)
                            if (!f.exists()) { Toast.makeText(ctx, "Video dosyası yok", Toast.LENGTH_SHORT).show(); return@OutlinedButton }
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }) { Text("Videoyu aç") }
                    }
                    OutlinedButton(onClick = onNewTest) { Text("Yeni test") }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCard("Ölçülen değerler") {
                    KvRow("Başlangıç", PdfExporter.fmtDate(p.startWallMs))
                    KvRow("Bitiş nedeni", p.endReason)
                    p.measured.forEach { KvRow(it.key, it.value) }
                }
                SectionCard("Telemetri bağlantısı") {
                    KvRow("Tür", p.link.kind)
                    KvRow("Paket / kayıp", "${p.link.packets} / ${p.link.lost}")
                    KvRow("Ortalama periyot", "${fmt(p.link.meanPeriodMs, 0)} ms")
                    KvRow("En büyük aralık", "${p.link.maxGapMs} ms", if (p.link.maxGapMs <= 2000) PassGreen else FailRed)
                    KvRow("Kopma", "${p.link.disconnects}")
                }
                SectionCard("Bütünlük") {
                    KvRow("Eşikler", if (p.evalConfigFrozen) "DONDURULMUŞ" else "dondurulmamış")
                    KvRow("Video", p.video?.let { "${it.fileName} (${it.sizeBytes / 1024} KB)" } ?: "yok (zaman damgalı kayıt)")
                    p.video?.let { Text("video SHA-256 ${it.sha256}", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                    Text("prevHash   ${e.prevHash}", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text("recordHash ${e.recordHash}", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text("Telemetri ${p.telemetry.size} paket · ${p.events.size} olay · ${p.track.size} konum noktası", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Zaman çizelgesi") {
                    p.events.take(60).forEach {
                        Text("+${fmt(it.tRelMs / 1000.0, 1)} s  [${it.type}] ${it.msg}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
