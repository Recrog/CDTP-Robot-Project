package edu.ytu.cdtp.ui.history

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ytu.cdtp.BuildConfig
import edu.ytu.cdtp.report.HashChain
import edu.ytu.cdtp.report.PdfExporter
import edu.ytu.cdtp.ui.components.CdtpTopBar
import edu.ytu.cdtp.ui.components.SectionCard
import edu.ytu.cdtp.ui.components.appContainer
import edu.ytu.cdtp.ui.theme.FailRed
import edu.ytu.cdtp.ui.theme.PassGreen
import edu.ytu.cdtp.ui.theme.WarnAmber
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val flow = remember { c.records.observeAll() }
    val records by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var report by remember { mutableStateOf<HashChain.Report?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var confirmTamper by remember { mutableStateOf(false) }

    Scaffold(topBar = { CdtpTopBar("Kayıtlar (${records.size})", onBack) }) { pad ->
        Row(Modifier.padding(pad).fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1.5f).fillMaxHeight()) {
                if (records.isEmpty()) item { Text("Henüz kayıt yok.", Modifier.padding(16.dp)) }
                items(records, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("#${r.idx}", fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.taskTitle, fontWeight = FontWeight.Bold)
                            Text(PdfExporter.fmtDate(r.createdAtMs), style = MaterialTheme.typography.bodySmall)
                        }
                        Box(
                            Modifier.background(if (r.passed) PassGreen else FailRed, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text(if (r.passed) "GEÇTİ" else "KALDI", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                    HorizontalDivider()
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCard("Değiştirilemezlik") {
                    Text(
                        "Her kayıt bir öncekinin hash'ine bağlıdır: recordHash = SHA256(prevHash + içerik). Uygulamada güncelleme/silme yolu yoktur.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        verifying = true
                        scope.launch { report = c.records.verify(); verifying = false }
                    }, enabled = !verifying, modifier = Modifier.fillMaxWidth()) { Text(if (verifying) "Doğrulanıyor…" else "Zinciri doğrula") }
                    report?.let { rep ->
                        Text(
                            if (rep.ok) "✓ Zincir sağlam (${rep.total} kayıt)" else "✗ ZİNCİR KIRIK",
                            color = if (rep.ok) PassGreen else FailRed, fontWeight = FontWeight.Black,
                        )
                        rep.issues.forEach { i ->
                            Text("#${i.index}: ${i.problem}", color = if (i.fatal) FailRed else WarnAmber, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val f = c.records.exportChain(File(ctx.cacheDir, "reports"))
                            PdfExporter.share(ctx, f, "application/json")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Zinciri JSON olarak dışa aktar") }
                    Text("PC'de bağımsız doğrulama: python tools/verify_chain.py <dosya.json>", style = MaterialTheme.typography.bodySmall)
                }
                if (BuildConfig.DEBUG) {
                    SectionCard("Geliştirici testi (yalnız debug derleme)") {
                        Text("Son kaydın GEÇTİ/KALDI değerini DAO'yu atlayıp ham SQL ile değiştirir. Doğrulamanın kırılmayı yakaladığını göstermek için.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { confirmTamper = true }, enabled = records.isNotEmpty()) { Text("Son kaydı kurcala") }
                    }
                }
            }
        }
    }

    if (confirmTamper) {
        AlertDialog(
            onDismissRequest = { confirmTamper = false },
            title = { Text("Kurcalama testi") },
            text = { Text("Son kayıt ham SQL ile değiştirilecek ve zincir kalıcı olarak kırık görünecek. Final cihazında yapmayın.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTamper = false
                    scope.launch {
                        val ok = c.records.debugTamperLast()
                        Toast.makeText(ctx, if (ok) "Kayıt değiştirildi — şimdi zinciri doğrulayın" else "Yapılamadı", Toast.LENGTH_SHORT).show()
                        report = null
                    }
                }) { Text("Kurcala") }
            },
            dismissButton = { TextButton(onClick = { confirmTamper = false }) { Text("Vazgeç") } },
        )
    }
}
