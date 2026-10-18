package edu.ytu.cdtp.telemetry

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class LinkStatus(
    val kind: LinkKind? = null,
    val phase: LinkPhase = LinkPhase.IDLE,
    val detail: String = "",
    val deviceName: String? = null,
)

data class LinkStats(
    val packets: Int = 0,
    val lost: Int = 0,
    val badPackets: Int = 0,
    val meanPeriodMs: Double = 0.0,
    val maxGapMs: Long = 0,
    val lastRxElapsedMs: Long = 0,
    val disconnects: Int = 0,
) {
    val lossPct: Double get() = if (packets + lost == 0) 0.0 else 100.0 * lost / (packets + lost)
}

data class LogEvent(val wallMs: Long, val elapsedMs: Long, val type: String, val message: String)

/**
 * Aktif bağlantıyı yönetir, paketleri çözer, 2 s watchdog'u ve istatistikleri tutar.
 * ICD kuralı: son paketten bu yana > 2 s → "BAĞLANTI KOPTU", olay log'u, otomatik yeniden bağlanma.
 */
class TelemetryRepository(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val timeoutMs: () -> Long,
) : LinkCallbacks {

    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _latest = MutableStateFlow<Telemetry?>(null)
    val latest: StateFlow<Telemetry?> = _latest.asStateFlow()

    private val _stats = MutableStateFlow(LinkStats())
    val stats: StateFlow<LinkStats> = _stats.asStateFlow()

    /** Veri akıyor mu (son paket ≤ 2 s önce)? */
    private val _alive = MutableStateFlow(false)
    val alive: StateFlow<Boolean> = _alive.asStateFlow()

    private val _tiltHistory = MutableStateFlow<List<Float>>(emptyList())
    val tiltHistory: StateFlow<List<Float>> = _tiltHistory.asStateFlow()

    private val _events = MutableSharedFlow<LogEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<LogEvent> = _events.asSharedFlow()
    private val _recentEvents = MutableStateFlow<List<LogEvent>>(emptyList())
    val recentEvents: StateFlow<List<LogEvent>> = _recentEvents.asStateFlow()

    private var link: TelemetryLink? = null
    val simulator: SimulatedLink? get() = link as? SimulatedLink
    val activeLink: TelemetryLink? get() = link

    private val lock = Any()
    private val packetLog = ArrayDeque<Telemetry>()
    private var prev: Telemetry? = null
    private var gapSum = 0L
    private var gapCount = 0
    private var hadData = false
    private var lastRestartMs = 0L

    init {
        scope.launch { watchdog() }
    }

    fun connect(kind: LinkKind, target: String?) {
        disconnect(log = false)
        resetStats()
        val l: TelemetryLink = when (kind) {
            LinkKind.BLE -> BleLink(ctx, this, target)
            LinkKind.CLASSIC_SPP -> SppLink(ctx, this, requireNotNull(target) { "SPP adresi gerekli" })
            LinkKind.WIFI_UDP -> UdpLink(ctx, this, target?.toIntOrNull() ?: Icd.DEFAULT_UDP_PORT)
            LinkKind.SIMULATOR -> SimulatedLink(this)
        }
        link = l
        _status.value = LinkStatus(kind, LinkPhase.CONNECTING, "Başlatılıyor")
        log("BAĞLANTI", "${kind.label} başlatıldı" + (target?.let { " ($it)" } ?: ""))
        l.start()
    }

    fun disconnect(log: Boolean = true) {
        val l = link ?: return
        link = null
        l.stop()
        _alive.value = false
        _status.value = LinkStatus()
        if (log) log("BAĞLANTI", "${l.kind.label} kapatıldı")
    }

    fun resetStats() = synchronized(lock) {
        prev = null; gapSum = 0; gapCount = 0; hadData = false
        packetLog.clear()
        _stats.value = LinkStats()
        _tiltHistory.value = emptyList()
    }

    override fun onPayload(bytes: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val t = TelemetryCodec.parse(bytes, now, System.currentTimeMillis())
        if (t == null) {
            synchronized(lock) { _stats.value = _stats.value.copy(badPackets = _stats.value.badPackets + 1) }
            return
        }
        val firstEver: Boolean
        synchronized(lock) {
            val s = _stats.value
            var lost = s.lost
            var maxGap = s.maxGapMs
            val p = prev
            if (p != null) {
                val gap = now - p.rxElapsedMs
                gapSum += gap; gapCount++
                if (gap > maxGap) maxGap = gap
                val d = (t.seq - p.seq) and 0xFFFF
                // d==0: tekrar, d>1000: robot yeniden başlamış (seq sıfırlandı) → kayıp sayma
                if (d in 2..1000) lost += d - 1
            }
            prev = t
            firstEver = !hadData
            hadData = true
            _stats.value = s.copy(
                packets = s.packets + 1,
                lost = lost,
                meanPeriodMs = if (gapCount > 0) gapSum.toDouble() / gapCount else 0.0,
                maxGapMs = maxGap,
                lastRxElapsedMs = now,
            )
            packetLog.addLast(t)
            while (packetLog.size > 4000) packetLog.removeFirst()
        }
        _latest.value = t
        _tiltHistory.value = (_tiltHistory.value + t.tiltDeg.toFloat()).takeLast(90)
        if (!_alive.value) {
            _alive.value = true
            log("BAĞLANTI", if (firstEver) "İlk telemetri paketi alındı" else "Veri akışı geri geldi")
        }
    }

    override fun onPhase(phase: LinkPhase, detail: String, deviceName: String?) {
        val cur = _status.value
        if (link == null) return
        _status.value = cur.copy(
            kind = link?.kind,
            phase = phase,
            detail = detail,
            deviceName = deviceName ?: cur.deviceName,
        )
        if (phase == LinkPhase.CONNECTED || phase == LinkPhase.ERROR) log("BAĞLANTI", "${phase.label}: $detail")
    }

    private suspend fun watchdog() {
        while (true) {
            delay(100)
            val now = SystemClock.elapsedRealtime()
            val last = _stats.value.lastRxElapsedMs
            val limit = timeoutMs()
            if (_alive.value && now - last > limit) {
                _alive.value = false
                synchronized(lock) { _stats.value = _stats.value.copy(disconnects = _stats.value.disconnects + 1) }
                log("KOPMA", "BAĞLANTI KOPTU — ${now - last} ms paket yok, yeniden bağlanılıyor")
                lastRestartMs = now
                link?.restart()
            } else if (!_alive.value && hadData && link != null && now - lastRestartMs > 6000) {
                lastRestartMs = now
                link?.restart()
            }
        }
    }

    /** Şu an veri taze mi? */
    fun isFresh(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val t = _latest.value ?: return false
        return now - t.rxElapsedMs <= timeoutMs()
    }

    fun log(type: String, message: String) {
        val e = LogEvent(System.currentTimeMillis(), SystemClock.elapsedRealtime(), type, message)
        _events.tryEmit(e)
        _recentEvents.value = (listOf(e) + _recentEvents.value).take(200)
    }

    /** 10 dk 1 Hz testi için ham paket log'u (CSV). */
    fun exportCsv(dir: File): File {
        dir.mkdirs()
        val f = File(dir, "telemetri_${System.currentTimeMillis()}.csv")
        val rows = synchronized(lock) { packetLog.toList() }
        f.bufferedWriter().use { w ->
            w.write("seq,robot_t_ms,rx_elapsed_ms,rx_wall_ms,battery_pct,tilt_deg,charging,state\n")
            for (t in rows) {
                w.write("${t.seq},${t.robotTimeMs},${t.rxElapsedMs},${t.rxWallMs},${t.batteryPct},${t.tiltDeg},${if (t.charging) 1 else 0},${t.stateCode}\n")
            }
        }
        return f
    }
}
