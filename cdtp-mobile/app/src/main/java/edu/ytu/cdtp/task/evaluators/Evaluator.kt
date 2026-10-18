package edu.ytu.cdtp.task.evaluators

import edu.ytu.cdtp.task.BoxStatus
import edu.ytu.cdtp.task.Direction
import edu.ytu.cdtp.task.EvalConfig
import edu.ytu.cdtp.task.MotionState
import edu.ytu.cdtp.task.TaskSpec
import edu.ytu.cdtp.task.TaskType
import edu.ytu.cdtp.vision.Angles
import edu.ytu.cdtp.vision.Pose
import edu.ytu.cdtp.vision.Vec2
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Test sırasında 10 Hz alınan örnek. tMs = testin başından itibaren. */
data class TrackSample(
    val tMs: Long,
    val robot: Pose?,
    val motion: MotionState,
    val direction: Direction,
    val boxStatus: BoxStatus,
    /** Robot ile istasyon marker'ı arası (m), istasyon konumu biliniyorsa. */
    val stationDistM: Double?,
    val tiltDeg: Double?,
    val charging: Boolean?,
    val batteryPct: Int?,
    val robotStateCode: Int?,
)

data class RuleResult(val name: String, val passed: Boolean, val detail: String)

data class EvalResult(
    val passed: Boolean,
    val rules: List<RuleResult>,
    val measured: List<Pair<String, String>>,
)

/** Canlı ekrandaki kontrol listesi satırı. ok = null → bekleniyor. */
data class LiveLine(val text: String, val ok: Boolean?)

abstract class Evaluator(protected val cfg: EvalConfig) {
    protected val acc = TrackAccumulator(cfg)
    private val pendingEvents = ArrayList<String>()

    /** Örneği işle; otomatik bitiş gerekiyorsa gerekçeyi döndür. */
    abstract fun onSample(s: TrackSample): String?
    abstract fun liveLines(): List<LiveLine>
    abstract fun finish(durationMs: Long, endReason: String): EvalResult

    protected fun emit(msg: String) { pendingEvents += msg }
    fun drainEvents(): List<String> = pendingEvents.toList().also { pendingEvents.clear() }

    /** Bütün görevlerde ortak ölçümler (Plan 7.1-6: süre, mesafe, yön, son konum, kutu). */
    protected fun commonMeasured(durationMs: Long): MutableList<Pair<String, String>> = mutableListOf(
        "Süre" to "${f(durationMs / 1000.0, 1)} s",
        "Net mesafe" to "${f(acc.netDistM, 3)} m",
        "Yol uzunluğu" to "${f(acc.pathM, 3)} m",
        "Net dönüş" to "${f(acc.netTurnDeg, 1)}°",
        "Baskın yön" to acc.dominantDirection().label,
        "Son konum (x, y)" to (acc.last?.let { "(${f(it.pos.x, 3)}, ${f(it.pos.y, 3)}) m, yön ${f(it.headingDeg, 0)}°" } ?: "görülmedi"),
        "Kutu durumu" to (if (acc.boxDropped) "DÜŞTÜ (t=${f((acc.boxDropT ?: 0) / 1000.0, 1)} s)" else "ROBOTTA"),
        "Max |eğim|" to (acc.maxAbsTilt?.let { "${f(it, 1)}°" } ?: "veri yok"),
        "Görüntü takibi" to "%${f(acc.trackingRatio * 100, 0)}",
    )

    protected fun boxRule() = RuleResult(
        "Kutu robotta",
        !acc.boxDropped,
        if (acc.boxDropped) "Kutu ${f((acc.boxDropT ?: 0) / 1000.0, 1)}. saniyede düştü" else "Görev boyunca robotta",
    )

    protected fun trackingRule() = RuleResult(
        "Görüntü takibi ≥ %${f(cfg.minTrackingRatio * 100, 0)}",
        acc.trackingRatio >= cfg.minTrackingRatio,
        "Robot karelerin %${f(acc.trackingRatio * 100, 0)}'inde görüldü",
    )

    companion object {
        fun create(spec: TaskSpec, cfg: EvalConfig): Evaluator = when (spec.type) {
            TaskType.STATIC_BALANCE -> StaticBalanceEvaluator(cfg)
            TaskType.GUIDANCE -> GuidanceEvaluator(cfg, spec)
            TaskType.CHARGING -> ChargingEvaluator(cfg, spec.chargeMode)
            TaskType.INTEGRATED -> IntegratedEvaluator(cfg)
            TaskType.RECOVERY -> RecoveryEvaluator(cfg)
        }
    }
}

private val TR = Locale("tr", "TR")
fun f(v: Double, digits: Int): String = String.format(TR, "%.${digits}f", v)

/** Yörünge özetleri: yol, net mesafe, net dönüş, en büyük sapma, kutu, eğim. */
class TrackAccumulator(private val cfg: EvalConfig) {
    var first: Pose? = null; private set
    var last: Pose? = null; private set
    var pathM = 0.0; private set
    var signedForwardM = 0.0; private set
    var netTurnDeg = 0.0; private set
    var maxDispM = 0.0; private set
    var maxAbsTilt: Double? = null; private set
    var boxDropped = false; private set
    var boxDropT: Long? = null; private set
    private var visible = 0
    private var total = 0

    val trackingRatio: Double get() = if (total == 0) 0.0 else visible.toDouble() / total
    val netDistM: Double get() { val a = first; val b = last; return if (a != null && b != null) a.pos.dist(b.pos) else 0.0 }

    fun add(s: TrackSample) {
        total++
        s.tiltDeg?.let { maxAbsTilt = max(maxAbsTilt ?: 0.0, abs(it)) }
        if (s.boxStatus == BoxStatus.DROPPED && !boxDropped) { boxDropped = true; boxDropT = s.tMs }
        val p = s.robot ?: return
        visible++
        val f0 = first ?: p.also { first = it }
        last?.let { l ->
            val d = p.pos - l.pos
            val step = d.norm()
            if (step > 0.004) {
                pathM += step
                signedForwardM += d.dot(Vec2.unit(l.headingDeg))
            }
            netTurnDeg += Angles.diff(p.headingDeg, l.headingDeg)
        }
        last = p
        maxDispM = max(maxDispM, p.pos.dist(f0.pos))
    }

    /** Dönüş baskınsa SOL/SAĞ, değilse ileri/geri bileşenin işaretine göre İLERİ/GERİ. */
    fun dominantDirection(turnThresholdDeg: Double = 45.0): Direction {
        if (first == null) return Direction.NONE
        if (abs(netTurnDeg) >= turnThresholdDeg && abs(signedForwardM) < 0.3) {
            val left = (netTurnDeg > 0) != cfg.invertTurnSign
            return if (left) Direction.LEFT else Direction.RIGHT
        }
        if (abs(signedForwardM) < 0.05) return Direction.NONE
        return if (signedForwardM > 0) Direction.FORWARD else Direction.BACKWARD
    }
}

/** Belirli bir koşulun kesintisiz sürdüğü süreyi ölçer. */
class Streak {
    private var since: Long? = null
    fun update(t: Long, cond: Boolean): Long {
        if (!cond) { since = null; return 0 }
        val s = since ?: t.also { since = it }
        return t - s
    }
    fun reset() { since = null }
}
