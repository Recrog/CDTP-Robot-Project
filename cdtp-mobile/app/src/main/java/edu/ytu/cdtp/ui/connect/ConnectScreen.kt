package edu.ytu.cdtp.ui.connect

import android.content.Context
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ytu.cdtp.report.PdfExporter
import edu.ytu.cdtp.telemetry.LinkKind
import edu.ytu.cdtp.telemetry.LinkPhase
import edu.ytu.cdtp.telemetry.SppLink
import edu.ytu.cdtp.ui.components.CdtpTopBar
import edu.ytu.cdtp.ui.components.KvRow
import edu.ytu.cdtp.ui.components.SectionCard
import edu.ytu.cdtp.ui.components.Sparkline
import edu.ytu.cdtp.ui.components.appContainer
import edu.ytu.cdtp.ui.components.fmt
import edu.ytu.cdtp.ui.components.rememberTicker
import edu.ytu.cdtp.ui.theme.FailRed
import edu.ytu.cdtp.ui.theme.PassGreen
import edu.ytu.cdtp.ui.theme.WarnAmber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = appContainer()
    val status by c.telemetry.status.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf(status.kind ?: LinkKind.BLE) }

    Scaffold(topBar = { CdtpTopBar("Bağlantı ve Canlı Telemetri", onBack) }) { pad ->
        Row(Modifier.padding(pad).fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionCard("Bağlantı türü") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinkKind.entries.forEach { k ->
                            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label, fontSize = 12.sp) })
                        }
                    }
                    Text(
                        when (kind) {
                            LinkKind.BLE -> "ESP32 + NimBLE. Cihaz adı CDTP-ROBOT, ICD servis UUID'si 8f3a0001-…"
                            LinkKind.CLASSIC_SPP -> "HC-05/HC-06 ya da ESP32 BluetoothSerial. Önce telefon ayarlarından eşleştirin (PIN genelde 1234). Çerçeve: A5 5A + 11 byte + XOR."
                            LinkKind.WIFI_UDP -> "Robot (ESP32 Wi-Fi / Raspberry Pi) paketleri bu telefonun IP'sine ya da 255.255.255.255'e UDP ile gönderir."
                            LinkKind.SIMULATOR -> "Donanım olmadan uygulamayı denemek için. ESP32 simülatörüyle aynı veriyi üretir."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (kind) {
                    LinkKind.BLE -> BlePanel()
                    LinkKind.CLASSIC_SPP -> SppPanel()
                    LinkKind.WIFI_UDP -> UdpPanel()
                    LinkKind.SIMULATOR -> SimPanel()
                }
                if (status.phase != LinkPhase.IDLE) {
                    OutlinedButton(onClick = { c.telemetry.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Bağlantıyı kapat") }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TelemetryPanel()
                StatsPanel(ctx)
                EventsPanel()
            }
        }
    }
}

@Composable
private fun BlePanel() {
    val ctx = LocalContext.current
    val c = appContainer()
    val results by c.bleScanner.results.collectAsStateWithLifecycle()
    val scanning by c.bleScanner.scanning.collectAsStateWithLifecycle()
    var onlyCdtp by remember { mutableStateOf(true) }
    SectionCard("BLE") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { c.telemetry.connect(LinkKind.BLE, null) }) { Text("Otomatik bağlan (CDTP-ROBOT)") }
            OutlinedButton(onClick = {
                c.bleScanner.start(onlyCdtp)?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            }, enabled = !scanning) { Text(if (scanning) "Taranıyor…" else "Tara") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(onlyCdtp, { onlyCdtp = it })
            Text("Sadece CDTP cihazları", style = MaterialTheme.typography.bodySmall)
        }
        c.settings.lastBleAddress?.let { addr ->
            OutlinedButton(onClick = { c.telemetry.connect(LinkKind.BLE, addr) }) { Text("Son cihaz: $addr") }
        }
        results.forEach { r ->
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable {
                    c.bleScanner.stop()
                    c.settings.lastBleAddress = r.address
                    c.telemetry.connect(LinkKind.BLE, r.address)
                }.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(r.name, fontWeight = if (r.isCdtp) FontWeight.Bold else FontWeight.Normal)
                Text("${r.address}  ${r.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SppPanel() {
    val ctx = LocalContext.current
    val c = appContainer()
    var devices by remember { mutableStateOf(SppLink.bondedDevices(ctx)) }
    SectionCard("Eşleştirilmiş cihazlar") {
        OutlinedButton(onClick = { devices = SppLink.bondedDevices(ctx) }) { Text("Listeyi yenile") }
        if (devices.isEmpty()) Text("Eşleştirilmiş cihaz yok (ya da Bluetooth izni verilmedi).", style = MaterialTheme.typography.bodySmall)
        devices.forEach { (name, addr) ->
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable {
                    c.settings.lastSppAddress = addr
                    c.telemetry.connect(LinkKind.CLASSIC_SPP, addr)
                }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(name, fontWeight = FontWeight.Bold)
                Text(addr, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun phoneIp(ctx: Context): String {
    val ip = ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ipAddress ?: 0
    if (ip == 0) return "Wi-Fi bağlı değil"
    return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
}

@Composable
private fun UdpPanel() {
    val ctx = LocalContext.current
    val c = appContainer()
    var port by remember { mutableStateOf(c.settings.udpPort.toString()) }
    SectionCard("Wi-Fi UDP") {
        KvRow("Telefonun IP adresi", phoneIp(ctx), bold = true)
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("UDP portu") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        Button(onClick = {
            val p = port.toIntOrNull() ?: 4210
            c.settings.udpPort = p
            c.telemetry.connect(LinkKind.WIFI_UDP, p.toString())
        }) { Text("Dinlemeye başla") }
        Text("PC'den denemek için: python tools/udp_simulator.py --ip <telefon IP>", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SimPanel() {
    val c = appContainer()
    val status by c.telemetry.status.collectAsStateWithLifecycle()
    rememberTicker(300)
    val sim = c.telemetry.simulator
    SectionCard("Simülatör") {
        if (status.kind != LinkKind.SIMULATOR || sim == null) {
            Button(onClick = { c.telemetry.connect(LinkKind.SIMULATOR, null) }) { Text("Simülatörü başlat") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sim.toggleCharging() }) { Text(if (sim.charging) "Şarjı kes" else "Şarjı başlat") }
                OutlinedButton(onClick = { sim.simulateDropout(3000) }) { Text("3 s kopma") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sim.kickTilt(10.0) }) { Text("+10° it") }
                OutlinedButton(onClick = { sim.kickTilt(-10.0) }) { Text("−10° it") }
            }
            Text("ESP32 simülatöründe şarj BOOT butonuyla değişir.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TelemetryPanel() {
    val c = appContainer()
    val status by c.telemetry.status.collectAsStateWithLifecycle()
    val alive by c.telemetry.alive.collectAsStateWithLifecycle()
    val t by c.telemetry.latest.collectAsStateWithLifecycle()
    val tilts by c.telemetry.tiltHistory.collectAsStateWithLifecycle()
    SectionCard("Canlı telemetri (1 Hz)") {
        KvRow("Durum", "${status.phase.label}${status.deviceName?.let { " · $it" } ?: ""}",
            when { alive -> PassGreen; status.phase == LinkPhase.IDLE -> MaterialTheme.colorScheme.onSurface; else -> WarnAmber }, true)
        if (status.detail.isNotBlank()) Text(status.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!alive && t != null) Text("BAĞLANTI KOPTU", color = FailRed, fontWeight = FontWeight.Black, fontSize = 20.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Big("Batarya", t?.let { "%${it.batteryPct}" } ?: "-")
            Big("Eğim", t?.let { "${fmt(it.tiltDeg, 1)}°" } ?: "-")
            Big("Şarj", t?.let { if (it.charging) "ŞARJDA" else "yok" } ?: "-")
            Big("Robot", t?.state?.label ?: "-")
        }
        Sparkline(tilts)
    }
}

@Composable
private fun Big(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatsPanel(ctx: Context) {
    val c = appContainer()
    val s by c.telemetry.stats.collectAsStateWithLifecycle()
    val t by c.telemetry.latest.collectAsStateWithLifecycle()
    val now = rememberTicker(100)
    val age = if (s.lastRxElapsedMs > 0) now - s.lastRxElapsedMs else null
    SectionCard("Bağlantı kalitesi (kabul: periyot 1 s ±50 ms, kayıp < %1, aralık < 2 s)") {
        KvRow("Son paket yaşı", age?.let { "$it ms" } ?: "-", if ((age ?: 0) > 2000) FailRed else PassGreen, true)
        KvRow("Paket / kayıp", "${s.packets} / ${s.lost}  (%${fmt(s.lossPct, 2)})", if (s.lossPct < 1.0) PassGreen else FailRed)
        KvRow("Ortalama periyot", if (s.meanPeriodMs > 0) "${fmt(s.meanPeriodMs, 1)} ms" else "-",
            if (s.meanPeriodMs == 0.0 || kotlin.math.abs(s.meanPeriodMs - 1000) <= 50) PassGreen else FailRed)
        KvRow("En büyük aralık", "${s.maxGapMs} ms", if (s.maxGapMs <= 2000) PassGreen else FailRed)
        KvRow("Kopma (> 2 s)", "${s.disconnects}", if (s.disconnects == 0) PassGreen else FailRed)
        KvRow("Bozuk paket", "${s.badPackets}")
        t?.let { KvRow("Son seq / robot saati", "${it.seq} / ${fmt(it.robotTimeMs / 1000.0, 1)} s") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { c.telemetry.resetStats() }) { Text("Sıfırla") }
            OutlinedButton(onClick = {
                val f = c.telemetry.exportCsv(File(ctx.cacheDir, "reports"))
                PdfExporter.share(ctx, f, "text/csv")
            }) { Text("CSV dışa aktar") }
        }
    }
}

@Composable
private fun EventsPanel() {
    val c = appContainer()
    val events by c.telemetry.recentEvents.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    SectionCard("Olay log'u") {
        if (events.isEmpty()) Text("Henüz olay yok", style = MaterialTheme.typography.bodySmall)
        events.take(30).forEach { e ->
            Text(
                "${fmt.format(Date(e.wallMs))}  [${e.type}] ${e.message}",
                style = MaterialTheme.typography.bodySmall,
                color = if (e.type == "KOPMA") FailRed else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
