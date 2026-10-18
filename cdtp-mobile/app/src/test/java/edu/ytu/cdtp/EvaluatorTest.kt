package edu.ytu.cdtp

import edu.ytu.cdtp.task.BoxStatus
import edu.ytu.cdtp.task.ChargeMode
import edu.ytu.cdtp.task.Direction
import edu.ytu.cdtp.task.EvalConfig
import edu.ytu.cdtp.task.MotionState
import edu.ytu.cdtp.task.TaskSpec
import edu.ytu.cdtp.task.TaskType
import edu.ytu.cdtp.task.evaluators.EvalResult
import edu.ytu.cdtp.task.evaluators.Evaluator
import edu.ytu.cdtp.task.evaluators.TrackSample
import edu.ytu.cdtp.vision.Pose
import edu.ytu.cdtp.vision.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sentetik 10 Hz örnek akışlarıyla değerlendirme kuralları. */
class EvaluatorTest {
    private val cfg = EvalConfig()
    private val station = Vec2(1.2, 0.8)

    private fun s(
        t: Long, x: Double, y: Double, h: Double, m: MotionState,
        box: BoxStatus = BoxStatus.ON_ROBOT, tilt: Double? = 1.0, chg: Boolean? = false, dir: Direction = Direction.NONE,
    ) = TrackSample(t, Pose(Vec2(x, y), h), m, dir, box, Pose(Vec2(x, y), h).pos.dist(station), tilt, chg, 80, if (chg == true) 3 else 0)

    /** Örnekleri sırayla verir, otomatik bitişte durur. */
    private fun run(ev: Evaluator, samples: Sequence<TrackSample>): Pair<EvalResult, String> {
        var last = 0L
        var reason = "Kullanıcı: Testi Bitir"
        for (x in samples) {
            last = x.tMs
            val end = ev.onSample(x)
            if (end != null) { reason = end; break }
        }
        return ev.finish(last, reason) to reason
    }

    @Test
    fun staticBalancePasses() {
        val ev = Evaluator.create(TaskSpec(TaskType.STATIC_BALANCE), cfg)
        val (r, reason) = run(ev, (0L..40_000L step 100).asSequence().map { s(it, 0.5 + 0.01 * ((it / 100) % 3), 0.5, 90.0, MotionState.STATIC) })
        assertTrue(reason.startsWith("Süre doldu"))
        assertTrue(r.rules.joinToString { "${it.name}=${it.passed}" }, r.passed)
    }

    @Test
    fun staticBalanceFailsOnDriftAndBoxDrop() {
        val ev = Evaluator.create(TaskSpec(TaskType.STATIC_BALANCE), cfg)
        val (r, _) = run(ev, (0L..40_000L step 100).asSequence().map { s(it, 0.5 + it / 100_000.0, 0.5, 90.0, MotionState.STATIC) })
        assertFalse(r.passed) // 0,3 m kaydı
        val ev2 = Evaluator.create(TaskSpec(TaskType.STATIC_BALANCE), cfg)
        val (r2, reason2) = run(ev2, (0L..40_000L step 100).asSequence().map {
            s(it, 0.5, 0.5, 90.0, MotionState.STATIC, box = if (it > 5000) BoxStatus.DROPPED else BoxStatus.ON_ROBOT)
        })
        assertEquals("Kutu düştü", reason2)
        assertFalse(r2.passed)
    }

    private fun drive(distance: Double, sign: Double) = sequence {
        // 1 s bekle, 5 s boyunca heading 90° doğrultusunda git, sonra 4 s dur
        for (t in 0L..1000L step 100) yield(s(t, 0.5, 0.2, 90.0, MotionState.STATIC))
        for (t in 1100L..6000L step 100) yield(s(t, 0.5, 0.2 + sign * distance * (t - 1000) / 5000.0, 90.0, MotionState.MOVING))
        for (t in 6100L..12_000L step 100) yield(s(t, 0.5, 0.2 + sign * distance, 90.0, MotionState.STATIC))
    }

    @Test
    fun guidanceForwardOneMeterPasses() {
        val ev = Evaluator.create(TaskSpec(TaskType.GUIDANCE, Direction.FORWARD, 0.8, 1.2), cfg)
        val (r, reason) = run(ev, drive(1.0, +1.0))
        assertTrue(reason.startsWith("Robot durdu"))
        assertTrue(r.rules.joinToString { "${it.name}=${it.passed}" }, r.passed)
    }

    @Test
    fun guidanceWrongDirectionOrDistanceFails() {
        val back = Evaluator.create(TaskSpec(TaskType.GUIDANCE, Direction.FORWARD, 0.8, 1.2), cfg)
        assertFalse(run(back, drive(1.0, -1.0)).first.passed)
        val short = Evaluator.create(TaskSpec(TaskType.GUIDANCE, Direction.FORWARD, 0.8, 1.2), cfg)
        assertFalse(run(short, drive(0.5, +1.0)).first.passed)
        val halfMeter = Evaluator.create(TaskSpec(TaskType.GUIDANCE, Direction.BACKWARD, 0.3, 0.7), cfg)
        assertTrue(run(halfMeter, drive(0.5, -1.0)).first.passed)
    }

    @Test
    fun guidanceLeftTurnPasses() {
        val seq = sequence {
            for (t in 0L..500L step 100) yield(s(t, 0.5, 0.5, 0.0, MotionState.STATIC))
            for (t in 600L..2600L step 100) yield(s(t, 0.5, 0.5, 90.0 * (t - 500) / 2100.0, MotionState.TURNING))
            for (t in 2700L..7000L step 100) yield(s(t, 0.5, 0.5, 90.0, MotionState.STATIC))
        }
        val (r, _) = run(Evaluator.create(TaskSpec(TaskType.GUIDANCE, Direction.LEFT), cfg), seq)
        assertTrue(r.rules.joinToString { "${it.name}=${it.passed}:${it.detail}" }, r.passed)
        val (r2, _) = run(Evaluator.create(TaskSpec(TaskType.GUIDANCE, Direction.RIGHT), cfg), seq)
        assertFalse(r2.passed)
    }

    /** Robot istasyona yaklaşır, 2 s sonra şarj başlar. */
    private fun dockSeq(chargeDelayMs: Long) = sequence {
        val start = Vec2(0.4, 0.2)
        for (t in 0L..3000L step 100) {
            val k = t / 3000.0
            val p = start + (station - start) * k
            yield(s(t, p.x, p.y, 45.0, MotionState.MOVING))
        }
        for (t in 3100L..20_000L step 100) {
            val chg = t >= 3100 + chargeDelayMs
            yield(s(t, station.x - 0.05, station.y, 45.0, MotionState.STATIC, chg = chg))
        }
    }

    @Test
    fun chargingWithinFiveSecondsPasses() {
        val ev = Evaluator.create(TaskSpec(TaskType.CHARGING, chargeMode = ChargeMode.BASIC), cfg)
        val (r, reason) = run(ev, dockSeq(2000))
        assertEquals("Şarj doğrulandı", reason)
        assertTrue(r.rules.joinToString { "${it.name}=${it.passed}:${it.detail}" }, r.passed)
    }

    @Test
    fun chargingTooLateFails() {
        val ev = Evaluator.create(TaskSpec(TaskType.CHARGING), cfg)
        val (r, _) = run(ev, dockSeq(7000))
        assertFalse(r.passed)
    }

    @Test
    fun integratedFullSequencePasses() {
        val seq = sequence {
            var t = 0L
            // 1) 6 s statik
            while (t <= 6000) { yield(s(t, 0.3, 0.2, 90.0, MotionState.STATIC)); t += 100 }
            // 2) 1 m ileri (y yönünde), sonra 90° sağa dön
            val t0 = t
            while (t <= t0 + 5000) { yield(s(t, 0.3, 0.2 + (t - t0) / 5000.0, 90.0, MotionState.MOVING)); t += 100 }
            val t1 = t
            while (t <= t1 + 2000) { yield(s(t, 0.3, 1.2, 90.0 - 90.0 * (t - t1) / 2000.0, MotionState.TURNING)); t += 100 }
            // 3) hedefte 6 s statik
            val t2 = t
            while (t <= t2 + 6000) { yield(s(t, 0.3, 1.2, 0.0, MotionState.STATIC)); t += 100 }
            // 4–5) istasyona (1,2 ; 0,8) git ve dur
            val t3 = t
            val a = Vec2(0.3, 1.2)
            val b = Vec2(station.x - 0.05, station.y)
            while (t <= t3 + 4000) { val p = a + (b - a) * ((t - t3) / 4000.0); yield(s(t, p.x, p.y, 0.0, MotionState.MOVING)); t += 100 }
            // 6) 1 s sonra şarj
            val t4 = t
            while (t <= t4 + 8000) { yield(s(t, b.x, b.y, 0.0, MotionState.STATIC, chg = t >= t4 + 1000)); t += 100 }
        }
        val (r, reason) = run(Evaluator.create(TaskSpec(TaskType.INTEGRATED), cfg), seq)
        assertEquals("Bütün adımlar tamamlandı", reason)
        assertTrue(r.rules.joinToString { "${it.name}=${it.passed}:${it.detail}" }, r.passed)
    }

    @Test
    fun recoveryFromTenDegrees() {
        val seq = (0L..12_000L step 100).asSequence().map { t ->
            val tilt = 10.0 * kotlin.math.exp(-t / 1500.0)
            s(t, 0.5, 0.5, 90.0, if (tilt > 5) MotionState.RECOVERING else MotionState.STATIC, tilt = tilt)
        }
        val (r, _) = run(Evaluator.create(TaskSpec(TaskType.RECOVERY), cfg), seq)
        assertTrue(r.rules.joinToString { "${it.name}=${it.passed}:${it.detail}" }, r.passed)
        assertNotNull(r.measured.firstOrNull { it.first == "Toparlanma süresi" })
    }
}
