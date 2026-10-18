package edu.ytu.cdtp.ui.taskselect

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ytu.cdtp.task.ChargeMode
import edu.ytu.cdtp.task.Direction
import edu.ytu.cdtp.task.TaskSpec
import edu.ytu.cdtp.task.TaskType
import edu.ytu.cdtp.ui.components.CdtpTopBar
import edu.ytu.cdtp.ui.components.SectionCard
import edu.ytu.cdtp.ui.components.appContainer
import edu.ytu.cdtp.ui.components.fmt
import edu.ytu.cdtp.ui.theme.WarnAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskSelectScreen(onBack: () -> Unit, onContinue: () -> Unit) {
    val c = appContainer()
    val cfg by c.settings.evalConfig.collectAsStateWithLifecycle()
    val cal by c.settings.calibration.collectAsStateWithLifecycle()
    val alive by c.telemetry.alive.collectAsStateWithLifecycle()
    var type by remember { mutableStateOf(c.tests.spec.value?.type ?: TaskType.STATIC_BALANCE) }
    var dir by remember { mutableStateOf(Direction.FORWARD) }
    var longPreset by remember { mutableStateOf(true) }
    var chargeMode by remember { mutableStateOf(ChargeMode.BASIC) }

    Scaffold(topBar = { CdtpTopBar("Görev Seçimi", onBack) }) { pad ->
        Row(Modifier.padding(pad).fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskType.entries.forEach { t ->
                    val sel = t == type
                    Card(
                        Modifier.fillMaxWidth().clickable { type = t },
                        colors = CardDefaults.cardColors(containerColor = if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface),
                        border = if (sel) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(t.metric, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(t.module, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCard("Seçenekler") {
                    when (type) {
                        TaskType.STATIC_BALANCE -> Text(
                            "${fmt(cfg.staticDurationS, 0)} s (±${fmt(cfg.durationTolS, 0)}) boyunca yer değiştirme < ${fmt(cfg.staticMaxDisplacementM)} m, " +
                                "|θ| < ${fmt(cfg.fallTiltDeg, 0)}°, kutu robotta. Süre dolunca test kendiliğinden biter.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TaskType.GUIDANCE -> {
                            Text("Beklenen yön (eldiven komutu)", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(Direction.FORWARD, Direction.BACKWARD, Direction.LEFT, Direction.RIGHT).forEach { d ->
                                    FilterChip(dir == d, { dir = d }, label = { Text(d.label) })
                                }
                            }
                            if (dir == Direction.FORWARD || dir == Direction.BACKWARD) {
                                Text("Mesafe aralığı (Açık Soru #1)", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(longPreset, { longPreset = true }, label = { Text("~1 m (${fmt(cfg.guidanceMinDistM, 1)}–${fmt(cfg.guidanceMaxDistM, 1)})") })
                                    FilterChip(!longPreset, { longPreset = false }, label = { Text("~0,5 m (${fmt(cfg.guidanceShortMinDistM, 1)}–${fmt(cfg.guidanceShortMaxDistM, 1)})") })
                                }
                            } else {
                                Text("Dönüş ${fmt(cfg.turnMinDeg, 0)}–${fmt(cfg.turnMaxDeg, 0)}° aralığında olmalı.", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Robot hareket edip ${fmt(cfg.settleS, 0)} s durunca test biter.", style = MaterialTheme.typography.bodySmall)
                        }
                        TaskType.CHARGING -> {
                            ChargeMode.entries.forEach { m -> FilterChip(chargeMode == m, { chargeMode = m }, label = { Text(m.title) }) }
                            Text(
                                "Yerleşme: robot istasyon marker'ına < ${fmt(cfg.dockDistM)} m ve STATİK. Bundan sonra ≤ ${fmt(cfg.chargeStartMaxS, 0)} s içinde telemetride charging=1 olmalı. " +
                                    "Doğrulama: kamera + eğim + şarj/pil.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TaskType.INTEGRATED -> Text(
                            "1) ${fmt(cfg.integStaticHoldS, 0)} s statik → 2) ≥ ${fmt(cfg.integMovePathM, 1)} m hareket + ≥ ${fmt(cfg.integTurnDeg, 0)}° dönüş → " +
                                "3) hedefte ${fmt(cfg.integStaticHoldS, 0)} s statik → 4) istasyona yaklaşma → 5) yerleşme → 6) ≤ ${fmt(cfg.chargeStartMaxS, 0)} s içinde şarj. Kutu baştan sona robotta.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TaskType.RECOVERY -> Text(
                            "Robotu ±10° eğik tutun, \"Testi Başlat\" ile bırakın. ${fmt(cfg.recoveryWindowS, 0)} s içinde |θ| < ${fmt(cfg.recoverySettledDeg, 0)}° olmalı.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (!alive) Text("⚠ Telemetri yok — eğim/şarj kuralları kalır.", color = WarnAmber, fontWeight = FontWeight.Bold)
                if (cal == null) Text("⚠ Kalibrasyon yok — konum/mesafe ölçülemez.", color = WarnAmber, fontWeight = FontWeight.Bold)
                Button(
                    onClick = {
                        val spec = when (type) {
                            TaskType.GUIDANCE -> TaskSpec(
                                type, dir,
                                minDistM = if (longPreset) cfg.guidanceMinDistM else cfg.guidanceShortMinDistM,
                                maxDistM = if (longPreset) cfg.guidanceMaxDistM else cfg.guidanceShortMaxDistM,
                            )
                            TaskType.CHARGING -> TaskSpec(type, chargeMode = chargeMode)
                            else -> TaskSpec(type)
                        }
                        c.tests.select(spec)
                        onContinue()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Canlı test ekranına geç") }
                Text("Not: Başlatılan her test kayda dönüşür, silinemez.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}
