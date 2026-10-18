package edu.ytu.cdtp.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ytu.cdtp.BuildConfig
import edu.ytu.cdtp.SettingsStore
import edu.ytu.cdtp.report.PdfExporter
import edu.ytu.cdtp.task.EvalConfig
import edu.ytu.cdtp.ui.components.CdtpTopBar
import edu.ytu.cdtp.ui.components.KvRow
import edu.ytu.cdtp.ui.components.SectionCard
import edu.ytu.cdtp.ui.components.appContainer
import edu.ytu.cdtp.ui.theme.FailRed
import edu.ytu.cdtp.ui.theme.PassGreen

private class NumField(val key: String, val label: String, val get: (EvalConfig) -> Double, val set: EvalConfig.(Double) -> EvalConfig)

private val groups: List<Pair<String, List<NumField>>> = listOf(
    "Hareket sınıflandırma" to listOf(
        NumField("mw", "Pencere (s)", { it.motionWindowS }) { copy(motionWindowS = it) },
        NumField("ff", "Filtre (kare)", { it.filterFrames.toDouble() }) { copy(filterFrames = it.toInt().coerceAtLeast(1)) },
        NumField("ss", "Statik hız < (m/s)", { it.staticSpeedMps }) { copy(staticSpeedMps = it) },
        NumField("so", "Statik ω < (°/s)", { it.staticOmegaDps }) { copy(staticOmegaDps = it) },
        NumField("to", "Dönüş ω ≥ (°/s)", { it.turnOmegaDps }) { copy(turnOmegaDps = it) },
        NumField("rt", "Toparlanma |θ| ≥ (°)", { it.recoverTiltDeg }) { copy(recoverTiltDeg = it) },
        NumField("ft", "Devrilme |θ| ≥ (°)", { it.fallTiltDeg }) { copy(fallTiltDeg = it) },
    ),
    "Kutu" to listOf(
        NumField("bd", "Kutu–robot > (m)", { it.boxMaxDistM }) { copy(boxMaxDistM = it) },
        NumField("bm", "Kutu görünmüyor > (s)", { it.boxMissingS }) { copy(boxMissingS = it) },
    ),
    "Statik denge" to listOf(
        NumField("sd", "Süre (s)", { it.staticDurationS }) { copy(staticDurationS = it) },
        NumField("dt", "Süre toleransı ± (s)", { it.durationTolS }) { copy(durationTolS = it) },
        NumField("sm", "Yer değiştirme < (m)", { it.staticMaxDisplacementM }) { copy(staticMaxDisplacementM = it) },
        NumField("tr", "Min takip oranı (0–1)", { it.minTrackingRatio }) { copy(minTrackingRatio = it) },
    ),
    "Yönlendirme" to listOf(
        NumField("g1", "Uzun: min (m)", { it.guidanceMinDistM }) { copy(guidanceMinDistM = it) },
        NumField("g2", "Uzun: max (m)", { it.guidanceMaxDistM }) { copy(guidanceMaxDistM = it) },
        NumField("g3", "Kısa: min (m)", { it.guidanceShortMinDistM }) { copy(guidanceShortMinDistM = it) },
        NumField("g4", "Kısa: max (m)", { it.guidanceShortMaxDistM }) { copy(guidanceShortMaxDistM = it) },
        NumField("t1", "Dönüş min (°)", { it.turnMinDeg }) { copy(turnMinDeg = it) },
        NumField("t2", "Dönüş max (°)", { it.turnMaxDeg }) { copy(turnMaxDeg = it) },
        NumField("st", "Durma süresi (s)", { it.settleS }) { copy(settleS = it) },
        NumField("gt", "Zaman aşımı (s)", { it.guidanceTimeoutS }) { copy(guidanceTimeoutS = it) },
    ),
    "Şarj" to listOf(
        NumField("dd", "Yerleşme uzaklığı < (m)", { it.dockDistM }) { copy(dockDistM = it) },
        NumField("cs", "Şarj başlama ≤ (s)", { it.chargeStartMaxS }) { copy(chargeStartMaxS = it) },
        NumField("co", "Çekince sönme ≤ (s)", { it.chargeOffMaxS }) { copy(chargeOffMaxS = it) },
        NumField("ct", "Zaman aşımı (s)", { it.chargeTimeoutS }) { copy(chargeTimeoutS = it) },
        NumField("d1", "Dock eğimi min (°)", { it.dockTiltMinDeg }) { copy(dockTiltMinDeg = it) },
        NumField("d2", "Dock eğimi max (°)", { it.dockTiltMaxDeg }) { copy(dockTiltMaxDeg = it) },
    ),
    "Bütünleşik" to listOf(
        NumField("ih", "Statik bekleme (s)", { it.integStaticHoldS }) { copy(integStaticHoldS = it) },
        NumField("ip", "Min yol (m)", { it.integMovePathM }) { copy(integMovePathM = it) },
        NumField("it", "Min dönüş (°)", { it.integTurnDeg }) { copy(integTurnDeg = it) },
        NumField("ia", "Yaklaşma bölgesi < (m)", { it.integApproachZoneM }) { copy(integApproachZoneM = it) },
        NumField("is", "Adım zaman aşımı (s)", { it.integStepTimeoutS }) { copy(integStepTimeoutS = it) },
    ),
    "Toparlanma" to listOf(
        NumField("rw", "Süre (s)", { it.recoveryWindowS }) { copy(recoveryWindowS = it) },
        NumField("rs", "Toparlandı |θ| < (°)", { it.recoverySettledDeg }) { copy(recoverySettledDeg = it) },
        NumField("ri", "Başlangıç |θ| ≥ (°)", { it.recoveryMinInitialDeg }) { copy(recoveryMinInitialDeg = it) },
    ),
    "Bağlantı / kamera" to listOf(
        NumField("lt", "Kopma eşiği (ms)", { it.linkTimeoutMs.toDouble() }) { copy(linkTimeoutMs = it.toLong()) },
        NumField("cm", "Kamera oynadı > (px)", { it.cameraMovedPx }) { copy(cameraMovedPx = it) },
    ),
)

private fun show(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = appContainer()
    val cfg by c.settings.evalConfig.collectAsStateWithLifecycle()
    val frozen by c.settings.frozen.collectAsStateWithLifecycle()
    val recordVideo by c.settings.recordVideo.collectAsStateWithLifecycle()
    val texts = remember(cfg) { mutableStateMapOf<String, String>().apply { groups.flatMap { it.second }.forEach { put(it.key, show(it.get(cfg))) } } }
    var invertTurn by remember(cfg) { mutableStateOf(cfg.invertTurnSign) }
    var dockTilt by remember(cfg) { mutableStateOf(cfg.dockTiltCheck) }
    var requireTurn by remember(cfg) { mutableStateOf(cfg.integRequireTurn) }
    var askFreeze by remember { mutableStateOf(false) }

    fun build(): EvalConfig? {
        var out = cfg.copy(invertTurnSign = invertTurn, dockTiltCheck = dockTilt, integRequireTurn = requireTurn)
        for (f in groups.flatMap { it.second }) {
            val v = texts[f.key]?.replace(',', '.')?.toDoubleOrNull() ?: return null
            out = f.set(out, v)
        }
        return out
    }

    Scaffold(topBar = { CdtpTopBar("Ayarlar", onBack) }) { pad ->
        Row(Modifier.padding(pad).fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1.6f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { (title, fields) ->
                    SectionCard(title) {
                        fields.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { f ->
                                    OutlinedTextField(
                                        value = texts[f.key] ?: "",
                                        onValueChange = { texts[f.key] = it },
                                        label = { Text(f.label, fontSize = 12.sp) },
                                        enabled = !frozen,
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            }
                        }
                        when (title) {
                            "Hareket sınıflandırma" -> SwitchRow("Dönüş işaretini ters çevir (SOL/SAĞ)", invertTurn, !frozen) { invertTurn = it }
                            "Şarj" -> SwitchRow("Eğim ile dock doğrulamasını kural yap", dockTilt, !frozen) { dockTilt = it }
                            "Bütünleşik" -> SwitchRow("Harekette dönüş zorunlu", requireTurn, !frozen) { requireTurn = it }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCard("Değerlendirme eşikleri") {
                    KvRow("Durum", if (frozen) "DONDURULDU" else "Düzenlenebilir", if (frozen) PassGreen else FailRed, true)
                    if (frozen) KvRow("Dondurma tarihi", PdfExporter.fmtDate(c.settings.frozenAtMs))
                    Text("SHA-256: ${SettingsStore.configHash(cfg)}", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    if (!frozen) {
                        Button(onClick = {
                            val n = build()
                            if (n == null) Toast.makeText(ctx, "Geçersiz sayı var", Toast.LENGTH_SHORT).show()
                            else { c.settings.saveEvalConfig(n); Toast.makeText(ctx, "Kaydedildi", Toast.LENGTH_SHORT).show() }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Kaydet") }
                        OutlinedButton(onClick = { c.settings.saveEvalConfig(EvalConfig()) }, modifier = Modifier.fillMaxWidth()) { Text("Varsayılana dön") }
                        Button(
                            onClick = { askFreeze = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = FailRed),
                        ) { Text("DONDUR (12. hafta)") }
                    }
                    Text("Dondurulduktan sonra eşikler değiştirilemez; her kayıt eşiklerin kopyasını ve hash'ini içerir.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Kayıt") {
                    SwitchRow("Test sırasında video kaydet", recordVideo, true) { c.settings.setRecordVideo(it) }
                    Text("Video + analiz performans sorunu çıkarırsa kapatın; zaman damgalı kayıt yine üretilir.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Hakkında") {
                    KvRow("Sürüm", "${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " (debug)" else ""}")
                    KvRow("ArUco sözlüğü", "DICT_4X4_50")
                    KvRow("Marker'lar", "0 robot · 1 kutu · 10–13 zemin · 20 istasyon")
                }
            }
        }
    }

    if (askFreeze) {
        AlertDialog(
            onDismissRequest = { askFreeze = false },
            title = { Text("Eşikler dondurulsun mu?") },
            text = { Text("Bu işlem geri alınamaz (uygulama verisi silinmedikçe). Önce kaydedilmemiş değişiklikleri kaydedin.") },
            confirmButton = { TextButton(onClick = { askFreeze = false; c.settings.freeze() }) { Text("DONDUR", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { askFreeze = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, enabled = enabled)
        androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
    }
}
