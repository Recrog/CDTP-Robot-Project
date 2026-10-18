package edu.ytu.cdtp.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ytu.cdtp.report.PdfExporter
import edu.ytu.cdtp.ui.components.CdtpTopBar
import edu.ytu.cdtp.ui.components.KvRow
import edu.ytu.cdtp.ui.components.SectionCard
import edu.ytu.cdtp.ui.components.appContainer
import edu.ytu.cdtp.ui.theme.FailRed
import edu.ytu.cdtp.ui.theme.PassGreen
import edu.ytu.cdtp.ui.theme.WarnAmber

fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}.toTypedArray()

private data class Tile(val route: String, val title: String, val sub: String, val icon: ImageVector)

@Composable
fun HomeScreen(openCvOk: Boolean, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = appContainer()
    var missing by remember { mutableStateOf(listOf<String>()) }
    fun refresh() {
        missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    LaunchedEffect(Unit) {
        refresh()
        if (missing.isNotEmpty()) launcher.launch(requiredPermissions())
    }

    val cal by c.settings.calibration.collectAsStateWithLifecycle()
    val frozen by c.settings.frozen.collectAsStateWithLifecycle()
    val alive by c.telemetry.alive.collectAsStateWithLifecycle()
    val recordsFlow = remember { c.records.observeAll() }
    val records by recordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val tiles = listOf(
        Tile("connect", "1 · Bağlantı", "Robot telemetrisi (BLE / BT / Wi-Fi / Simülatör)", Icons.Filled.Bluetooth),
        Tile("calibration", "2 · Kalibrasyon", "Zemin marker'ları → metre", Icons.Filled.GridOn),
        Tile("tasks", "3 · Görev Seç ve Test", "4 görev + toparlanma", Icons.Filled.PlayArrow),
        Tile("history", "Kayıtlar", "Rapor, PDF, zinciri doğrula", Icons.Filled.History),
        Tile("settings", "Ayarlar", "Eşikler (EvalConfig), dondurma, video", Icons.Filled.Settings),
    )

    Scaffold(topBar = { CdtpTopBar("ÇDTP · Mobil İzleme ve Değerlendirme", onBack = null) }) { pad ->
        Row(Modifier.padding(pad).fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(2f).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tiles) { t ->
                    Card(
                        Modifier.fillMaxWidth().heightIn(min = 130.dp).clickable { go(t.route) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(2.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(t.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard("Hazırlık durumu") {
                    KvRow("İzinler", if (missing.isEmpty()) "Tamam" else "${missing.size} eksik", if (missing.isEmpty()) PassGreen else FailRed, true)
                    KvRow("OpenCV", if (openCvOk) "Yüklü" else "YÜKLENEMEDİ", if (openCvOk) PassGreen else FailRed, true)
                    KvRow("Telemetri", if (alive) "Veri akıyor" else "Yok", if (alive) PassGreen else WarnAmber, true)
                    KvRow("Kalibrasyon", cal?.let { "${it.floorW}×${it.floorH} m" } ?: "Yapılmadı", if (cal != null) PassGreen else WarnAmber, true)
                    KvRow("Eşikler", if (frozen) "DONDURULDU" else "Düzenlenebilir", if (frozen) PassGreen else Color.Gray, true)
                    KvRow("Kayıt sayısı", "${records.size}")
                    records.firstOrNull()?.let { KvRow("Son kayıt", PdfExporter.fmtDate(it.createdAtMs)) }
                    if (missing.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { launcher.launch(requiredPermissions()) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("İzinleri ver") }
                    }
                }
                SectionCard("Test akışı") {
                    Text(
                        "1) Telefonu tripoda sabitleyin.\n2) Robotla bağlantıyı kurun (1 Hz telemetri).\n3) Zemin marker'larıyla kalibre edin.\n" +
                            "4) Görevi seçin → Testi Başlat.\n5) Sonuç otomatik değerlendirilir ve değiştirilemez kayda yazılır.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
