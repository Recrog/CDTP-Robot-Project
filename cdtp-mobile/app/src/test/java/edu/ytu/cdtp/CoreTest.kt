package edu.ytu.cdtp

import edu.ytu.cdtp.report.HashChain
import edu.ytu.cdtp.report.LinkSummaryDto
import edu.ytu.cdtp.report.RecordPayload
import edu.ytu.cdtp.report.RecordRepository
import edu.ytu.cdtp.report.db.TestRecordEntity
import edu.ytu.cdtp.task.BoxStatus
import edu.ytu.cdtp.task.Direction
import edu.ytu.cdtp.task.EvalConfig
import edu.ytu.cdtp.task.MotionState
import edu.ytu.cdtp.telemetry.FrameDecoder
import edu.ytu.cdtp.telemetry.TelemetryCodec
import edu.ytu.cdtp.vision.BoxMonitor
import edu.ytu.cdtp.vision.Homography
import edu.ytu.cdtp.vision.MotionClassifier
import edu.ytu.cdtp.vision.Pose
import edu.ytu.cdtp.vision.Vec2
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCodecTest {
    @Test
    fun icdLayoutLittleEndian() {
        // seq=0x0102, t=0x01020304 ms, bat=87, tilt=-1.4° (→ -14), charging=1, state=3
        val b = TelemetryCodec.encode(0x0102, 0x01020304, 87, -1.4, true, 3)
        assertEquals(11, b.size)
        assertEquals(0x02.toByte(), b[0]); assertEquals(0x01.toByte(), b[1])
        assertEquals(0x04.toByte(), b[2]); assertEquals(0x01.toByte(), b[5])
        assertEquals(87.toByte(), b[6])
        assertEquals((-14 and 0xFF).toByte(), b[7]); assertEquals(0xFF.toByte(), b[8])
        assertEquals(1.toByte(), b[9]); assertEquals(3.toByte(), b[10])
        val t = TelemetryCodec.parse(b, 0, 0)!!
        assertEquals(0x0102, t.seq)
        assertEquals(0x01020304L, t.robotTimeMs)
        assertEquals(87, t.batteryPct)
        assertEquals(-1.4, t.tiltDeg, 1e-9)
        assertTrue(t.charging)
        assertEquals(3, t.stateCode)
    }

    @Test
    fun largeUnsignedValues() {
        val t = TelemetryCodec.parse(TelemetryCodec.encode(65535, 0xFFFFFFF0L, 100, 32.0, false, 4), 0, 0)!!
        assertEquals(65535, t.seq)
        assertEquals(0xFFFFFFF0L, t.robotTimeMs)
        assertEquals(32.0, t.tiltDeg, 1e-9)
    }

    @Test
    fun shortPacketRejected() = assertNull(TelemetryCodec.parse(ByteArray(10), 0, 0))

    @Test
    fun frameDecoderFindsFramesInNoisyStream() {
        val out = ArrayList<ByteArray>()
        val dec = FrameDecoder { out += it }
        val p1 = TelemetryCodec.encode(1, 1000, 50, 2.5, false, 0)
        val p2 = TelemetryCodec.encode(2, 2000, 49, -3.0, true, 3)
        val bad = TelemetryCodec.frame(p1).also { it[13] = (it[13].toInt() xor 0x55).toByte() }
        val stream = byteArrayOf(0x00, 0xA5.toByte(), 0x13) + TelemetryCodec.frame(p1) + bad + byteArrayOf(0xA5.toByte()) + TelemetryCodec.frame(p2)
        // parça parça besle
        stream.toList().chunked(5).forEach { dec.feed(it.toByteArray()) }
        assertEquals(2, out.size)
        assertEquals(1, TelemetryCodec.parse(out[0], 0, 0)!!.seq)
        assertEquals(2, TelemetryCodec.parse(out[1], 0, 0)!!.seq)
        assertEquals(1, dec.checksumErrors)
    }
}

class HomographyTest {
    @Test
    fun fourPointMappingIsExact() {
        val img = listOf(Vec2(210.0, 700.0), Vec2(1050.0, 690.0), Vec2(900.0, 300.0), Vec2(380.0, 310.0))
        val world = listOf(Vec2(0.0, 0.0), Vec2(1.5, 0.0), Vec2(1.5, 1.0), Vec2(0.0, 1.0))
        val h = Homography.fromPoints(img, world)
        img.zip(world).forEach { (i, w) ->
            val m = h.map(i)
            assertEquals(w.x, m.x, 1e-9); assertEquals(w.y, m.y, 1e-9)
        }
        val inv = h.inverse()
        val back = inv.map(h.map(Vec2(640.0, 480.0)))
        assertEquals(640.0, back.x, 1e-6); assertEquals(480.0, back.y, 1e-6)
    }
}

class MotionTest {
    private val cfg = EvalConfig()

    @Test
    fun forwardBackwardTurnStatic() {
        val mc = MotionClassifier()
        // 0,2 m/s heading 90° (y yönünde) ileri
        var r = mc.update(0, Pose(Vec2(0.0, 0.0), 90.0), 0.5, cfg)
        for (t in 33L..1000L step 33) r = mc.update(t, Pose(Vec2(0.0, 0.2 * t / 1000.0), 90.0), 0.5, cfg)
        assertEquals(MotionState.MOVING, r.state); assertEquals(Direction.FORWARD, r.direction)

        mc.reset()
        for (t in 0L..1000L step 33) r = mc.update(t, Pose(Vec2(0.0, -0.2 * t / 1000.0), 90.0), 0.5, cfg)
        assertEquals(Direction.BACKWARD, r.direction)

        mc.reset()
        // yerinde +60°/s (CCW) → SOL
        for (t in 0L..1000L step 33) r = mc.update(t, Pose(Vec2(0.5, 0.5), 60.0 * t / 1000.0), 0.5, cfg)
        assertEquals(MotionState.TURNING, r.state); assertEquals(Direction.LEFT, r.direction)

        mc.reset()
        for (t in 0L..1000L step 33) r = mc.update(t, Pose(Vec2(0.5, 0.5), -60.0 * t / 1000.0), 0.5, cfg)
        assertEquals(Direction.RIGHT, r.direction)

        mc.reset()
        for (t in 0L..1000L step 33) r = mc.update(t, Pose(Vec2(0.5 + 0.001 * (t % 2), 0.5), 10.0), 1.0, cfg)
        assertEquals(MotionState.STATIC, r.state)

        // büyük eğim, hareketsiz → TOPARLANMA; ≥30° → DEVRİLDİ
        r = mc.update(1100, Pose(Vec2(0.5, 0.5), 10.0), 8.0, cfg)
        assertEquals(MotionState.RECOVERING, r.state)
        r = mc.update(1133, Pose(Vec2(0.5, 0.5), 10.0), 35.0, cfg)
        assertEquals(MotionState.FALLEN, r.state)
    }

    @Test
    fun boxDropDetection() {
        val bm = BoxMonitor()
        bm.reset(0)
        val robot = Vec2(0.0, 0.0)
        assertEquals(BoxStatus.ON_ROBOT, bm.update(0, robot, Vec2(0.05, 0.0), cfg))
        // kutu görünmüyor ama 1 s dolmadı
        assertEquals(BoxStatus.ON_ROBOT, bm.update(800, robot, null, cfg))
        assertEquals(BoxStatus.DROPPED, bm.update(1100, robot, null, cfg))
        // uzak kutu: 0,3 s sonra DÜŞTÜ
        bm.reset(2000)
        assertEquals(BoxStatus.ON_ROBOT, bm.update(2000, robot, Vec2(0.05, 0.0), cfg))
        assertEquals(BoxStatus.ON_ROBOT, bm.update(2100, robot, Vec2(0.4, 0.0), cfg))
        assertEquals(BoxStatus.DROPPED, bm.update(2450, robot, Vec2(0.4, 0.0), cfg))
    }
}

class HashChainTest {
    private fun payload(index: Long, prev: String, passed: Boolean) = RecordPayload(
        index = index, prevHash = prev, app = "test", device = "jvm", taskType = "STATIC_BALANCE", taskTitle = "Statik Denge",
        taskOptions = emptyMap(), startWallMs = 1000L * index, endWallMs = 1000L * index + 30000, durationMs = 30000,
        endReason = "Süre doldu", passed = passed, rules = emptyList(), measured = emptyList(),
        link = LinkSummaryDto("Simülatör", 30, 0, 1010, 0, 1000.0), calibration = null, evalConfig = EvalConfig(),
        evalConfigHash = "x", evalConfigFrozen = false, video = null, telemetry = emptyList(), events = emptyList(), track = emptyList(),
    )

    private fun chain(n: Int): List<TestRecordEntity> {
        val out = ArrayList<TestRecordEntity>()
        var prev = HashChain.GENESIS
        for (i in 1..n) {
            val text = RecordRepository.json.encodeToString(payload(i.toLong(), prev, i % 2 == 0))
            val e = TestRecordEntity(i.toLong(), i.toLong(), 1000L * i, "STATIC_BALANCE", "Statik Denge", i % 2 == 0, text, prev, HashChain.recordHash(prev, text))
            out += e; prev = e.recordHash
        }
        return out
    }

    @Test
    fun intactChainVerifies() = assertTrue(HashChain.verify(chain(5)).ok)

    @Test
    fun singleByteChangeIsDetected() {
        val c = chain(5).toMutableList()
        val e = c[2]
        c[2] = e.copy(payloadJson = e.payloadJson.replace("\"passed\":false", "\"passed\":true"), passed = true)
        val r = HashChain.verify(c)
        assertFalse(r.ok)
        assertTrue(r.issues.any { it.index == 3L && it.problem.startsWith("Hash") })
    }

    @Test
    fun columnFlipIsDetected() {
        val c = chain(3).toMutableList()
        c[0] = c[0].copy(passed = !c[0].passed)
        assertFalse(HashChain.verify(c).ok)
    }

    @Test
    fun deletedMiddleRecordIsDetected() {
        val c = chain(4).toMutableList()
        c.removeAt(1)
        val r = HashChain.verify(c)
        assertFalse(r.ok)
        assertNotNull(r.issues.firstOrNull { it.problem.startsWith("Zincir") })
    }
}
