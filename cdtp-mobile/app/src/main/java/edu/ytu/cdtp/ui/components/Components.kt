package edu.ytu.cdtp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ytu.cdtp.AppContainer
import edu.ytu.cdtp.CdtpApp
import edu.ytu.cdtp.task.evaluators.LiveLine
import edu.ytu.cdtp.telemetry.LinkPhase
import edu.ytu.cdtp.ui.theme.Coral
import edu.ytu.cdtp.ui.theme.FailRed
import edu.ytu.cdtp.ui.theme.Navy
import edu.ytu.cdtp.ui.theme.PassGreen
import edu.ytu.cdtp.ui.theme.WarnAmber
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as CdtpApp).container

private val TR = Locale("tr", "TR")
fun fmt(v: Double, digits: Int = 2): String = String.format(TR, "%.${digits}f", v)

/** UI'yi belirli aralıkla yenilemek için (ör. "son paket yaşı"). */
@Composable
fun rememberTicker(periodMs: Long = 100): Long {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = android.os.SystemClock.elapsedRealtime()
        }
    }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CdtpTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
        },
        actions = { actions(); LinkBadge(); Spacer(Modifier.width(8.dp)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Navy,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
    )
}

/** Her ekranda sağ üstte: bağlantı + veri akışı göstergesi. */
@Composable
fun LinkBadge() {
    val c = appContainer()
    val status by c.telemetry.status.collectAsStateWithLifecycle()
    val alive by c.telemetry.alive.collectAsStateWithLifecycle()
    val latest by c.telemetry.latest.collectAsStateWithLifecycle()
    val (color, text) = when {
        status.phase == LinkPhase.IDLE -> Color.Gray to "Bağlı değil"
        alive -> PassGreen to "${status.kind?.label ?: ""} · %${latest?.batteryPct ?: "-"} · ${latest?.let { fmt(it.tiltDeg, 1) } ?: "-"}°"
        status.phase == LinkPhase.ERROR -> FailRed to status.detail.take(28)
        latest != null -> FailRed to "BAĞLANTI KOPTU"
        else -> WarnAmber to status.phase.label
    }
    Row(
        Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun KvRow(k: String, v: String, valueColor: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun LiveLineRow(l: LiveLine) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (sym, col) = when (l.ok) {
            true -> "✓" to PassGreen
            false -> "✗" to FailRed
            null -> "…" to Color.Gray
        }
        Text(sym, color = col, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        Text(l.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun VerdictBanner(passed: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().background(if (passed) PassGreen else FailRed, RoundedCornerShape(12.dp)).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (passed) "GEÇTİ" else "KALDI", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
    }
}

/** Basit eğim grafiği (son ~90 paket). */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, range: Float = 15f) {
    val axis = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Canvas(modifier.fillMaxWidth().height(70.dp)) {
        val midY = size.height / 2
        drawLine(axis, Offset(0f, midY), Offset(size.width, midY))
        if (values.size < 2) return@Canvas
        val r = maxOf(range, values.maxOf { kotlin.math.abs(it) })
        val dx = size.width / 89f
        val path = Path()
        val start = 90 - values.size
        values.forEachIndexed { i, v ->
            val x = (start + i) * dx
            val y = midY - (v / r) * (size.height / 2)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Coral, style = Stroke(width = 3f))
    }
}
