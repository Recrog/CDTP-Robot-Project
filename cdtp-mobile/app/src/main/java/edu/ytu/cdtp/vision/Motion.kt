package edu.ytu.cdtp.vision

import edu.ytu.cdtp.task.BoxStatus
import edu.ytu.cdtp.task.Direction
import edu.ytu.cdtp.task.EvalConfig
import edu.ytu.cdtp.task.MotionState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Konum için hareketli ortalama, yön için dairesel ortalama. */
class PoseFilter(var size: Int = 7, private val holdMs: Long = 300) {
    private val buf = ArrayDeque<Pair<Long, Pose>>()
    private var lastOut: Pose? = null
    private var lastSeenMs = Long.MIN_VALUE

    fun reset() { buf.clear(); lastOut = null; lastSeenMs = Long.MIN_VALUE }

    fun update(t: Long, p: Pose?): Pose? {
        if (p == null) {
            // Kısa kayıplarda son konumu tut (titreme olmasın).
            return if (t - lastSeenMs <= holdMs) lastOut else { buf.clear(); null }
        }
        if (buf.isNotEmpty() && t - buf.last().first > 500) buf.clear()
        buf.addLast(t to p)
        while (buf.size > size.coerceAtLeast(1)) buf.removeFirst()
        val x = buf.sumOf { it.second.pos.x } / buf.size
        val y = buf.sumOf { it.second.pos.y } / buf.size
        val s = buf.sumOf { sin(Math.toRadians(it.second.headingDeg)) }
        val c = buf.sumOf { cos(Math.toRadians(it.second.headingDeg)) }
        val out = Pose(Vec2(x, y), Math.toDegrees(atan2(s, c)))
        lastOut = out
        lastSeenMs = t
        return out
    }
}

data class MotionResult(
    val state: MotionState,
    val direction: Direction,
    val speedMps: Double,
    val signedSpeedMps: Double,
    val omegaDps: Double,
)

/**
 * Plan 7.6: son ~0,5 s penceresinde v, ω ve telemetriden θ ile durum + yön.
 * Dünya çerçevesi sağ elli (x sağ, y kameradan uzağa) → ω > 0 = SOLA dönüş.
 */
class MotionClassifier {
    private val hist = ArrayDeque<Pair<Long, Pose>>()
    private var last = MotionState.STATIC

    fun reset() { hist.clear(); last = MotionState.STATIC }

    fun update(t: Long, pose: Pose?, tiltDeg: Double?, cfg: EvalConfig): MotionResult {
        val tiltAbs = tiltDeg?.let { abs(it) }
        if (pose == null) {
            hist.clear()
            val s = when {
                tiltAbs != null && tiltAbs >= cfg.fallTiltDeg -> MotionState.FALLEN
                tiltAbs != null && tiltAbs >= cfg.recoverTiltDeg -> MotionState.RECOVERING
                else -> MotionState.NOT_VISIBLE
            }
            return MotionResult(s, Direction.NONE, 0.0, 0.0, 0.0)
        }
        hist.addLast(t to pose)
        val windowMs = (cfg.motionWindowS * 1000).toLong()
        while (hist.size > 2 && t - hist[1].first >= windowMs) hist.removeFirst()
        val (t0, p0) = hist.first()
        val dt = (t - t0) / 1000.0
        var speed = 0.0
        var vSigned = 0.0
        var omega = 0.0
        if (dt >= 0.15) {
            val d = pose.pos - p0.pos
            speed = d.norm() / dt
            vSigned = d.dot(Vec2.unit(pose.headingDeg)) / dt
            omega = Angles.diff(pose.headingDeg, p0.headingDeg) / dt
        }
        val state = when {
            tiltAbs != null && tiltAbs >= cfg.fallTiltDeg -> MotionState.FALLEN
            abs(omega) >= cfg.turnOmegaDps -> MotionState.TURNING
            speed >= cfg.staticSpeedMps -> MotionState.MOVING
            tiltAbs != null && tiltAbs >= cfg.recoverTiltDeg -> MotionState.RECOVERING
            abs(omega) < cfg.staticOmegaDps -> MotionState.STATIC
            // 10–20 °/s arası belirsiz bölge: önceki durumu koru (histerezis)
            last == MotionState.TURNING -> MotionState.TURNING
            else -> MotionState.STATIC
        }
        last = state
        val left = (omega > 0) != cfg.invertTurnSign
        val dir = when (state) {
            MotionState.MOVING -> if (vSigned >= 0) Direction.FORWARD else Direction.BACKWARD
            MotionState.TURNING -> if (left) Direction.LEFT else Direction.RIGHT
            else -> Direction.NONE
        }
        return MotionResult(state, dir, speed, vSigned, omega)
    }
}

/**
 * Kutu düştü: kutu–robot mesafesi > 0,15 m (0,3 s sürerse) veya
 * robot görünürken kutu > 1 s görünmüyor.
 */
class BoxMonitor {
    private var lastBoxSeen = Long.MIN_VALUE
    private var farSince: Long? = null
    private var status = BoxStatus.UNKNOWN

    fun reset(now: Long) {
        lastBoxSeen = now
        farSince = null
        status = BoxStatus.UNKNOWN
    }

    fun update(t: Long, robot: Vec2?, box: Vec2?, cfg: EvalConfig): BoxStatus {
        if (lastBoxSeen == Long.MIN_VALUE) lastBoxSeen = t
        status = if (box != null) {
            lastBoxSeen = t
            if (robot != null) {
                if (robot.dist(box) > cfg.boxMaxDistM) {
                    val since = farSince ?: t.also { farSince = it }
                    if (t - since >= 300) BoxStatus.DROPPED else status
                } else {
                    farSince = null
                    BoxStatus.ON_ROBOT
                }
            } else BoxStatus.UNKNOWN
        } else {
            farSince = null
            if (robot != null && t - lastBoxSeen > (cfg.boxMissingS * 1000).toLong()) BoxStatus.DROPPED
            else if (robot != null) status
            else BoxStatus.UNKNOWN
        }
        return status
    }
}
