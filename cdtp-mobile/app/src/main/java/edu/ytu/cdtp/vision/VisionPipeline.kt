package edu.ytu.cdtp.vision

import android.os.SystemClock
import edu.ytu.cdtp.SettingsStore
import edu.ytu.cdtp.task.BoxStatus
import edu.ytu.cdtp.task.Direction
import edu.ytu.cdtp.task.MotionState
import edu.ytu.cdtp.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Bir kamera karesinin işlenmiş hali (UI ve test oturumu bunu okur). */
data class FrameState(
    val tMs: Long,
    val imgW: Int,
    val imgH: Int,
    val markers: List<DetectedMarker>,
    val calibrated: Boolean,
    val robotPose: Pose?,
    val robotImg: DetectedMarker?,
    val boxPos: Vec2?,
    val boxStatus: BoxStatus,
    val stationPos: Vec2?,
    val stationVisible: Boolean,
    val motion: MotionState,
    val direction: Direction,
    val speedMps: Double,
    val omegaDps: Double,
    val floorVisible: Int,
    /** Kalibrasyondaki zemin marker konumlarından en büyük sapma (px) — kamera oynadı mı? */
    val cameraShiftPx: Double?,
    val trail: List<Vec2>,
    val fps: Double,
)

/**
 * Kare → marker'lar → zemin koordinatı → filtre → hareket sınıfı → kutu durumu.
 * Analiz iş parçacığında çalışır; sonucu StateFlow olarak yayınlar.
 */
class VisionPipeline(
    private val settings: SettingsStore,
    private val telemetry: TelemetryRepository,
) {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tracker = ArucoTracker()

    private val _frame = MutableStateFlow<FrameState?>(null)
    val frame: StateFlow<FrameState?> = _frame.asStateFlow()

    private val filter = PoseFilter()
    private val classifier = MotionClassifier()
    private val boxMonitor = BoxMonitor()
    private val trail = ArrayDeque<Vec2>()
    private var lastStation: Vec2? = null
    private var cachedCal: Calibration? = null
    private var cachedH: Homography? = null
    private var fps = 0.0
    private var lastFrameMs = 0L
    private val lock = Any()

    fun analyzer() = FrameAnalyzer(tracker) { onFrame(it) }

    /** Test başında iz ve kutu izleyiciyi sıfırla. */
    fun resetForTest(now: Long = SystemClock.elapsedRealtime()) = synchronized(lock) {
        trail.clear()
        boxMonitor.reset(now)
    }

    fun clearTrail() = synchronized(lock) { trail.clear() }

    private fun homography(cal: Calibration?): Homography? {
        if (cal !== cachedCal) {
            cachedCal = cal
            cachedH = cal?.homography()
            lastStation = cal?.station
        }
        return cachedH
    }

    private fun onFrame(fm: FrameMarkers) = synchronized(lock) {
        val cfg = settings.evalConfig.value
        filter.size = cfg.filterFrames
        val cal = settings.calibration.value?.takeIf { it.imgW == fm.width && it.imgH == fm.height }
        val hm = homography(cal)

        if (lastFrameMs > 0) {
            val dt = (fm.tMs - lastFrameMs).coerceAtLeast(1)
            fps = 0.9 * fps + 0.1 * (1000.0 / dt)
        }
        lastFrameMs = fm.tMs

        val byId = fm.markers.associateBy { it.id }
        val robotM = byId[MarkerIds.ROBOT]
        var rawPose: Pose? = null
        if (robotM != null && hm != null) {
            val w = robotM.corners.map { hm.map(it) }
            val c = Vec2.mean(w)
            val front = (w[0] + w[1]) * 0.5
            rawPose = Pose(c, Angles.of(front - c))
        }
        val pose = filter.update(fm.tMs, rawPose)

        val box = byId[MarkerIds.BOX]?.let { m -> hm?.map(m.center) }
        val stationM = byId[MarkerIds.STATION]
        if (stationM != null && hm != null) lastStation = hm.map(stationM.center)

        val tel = telemetry.latest.value
        val tilt = if (tel != null && telemetry.isFresh(fm.tMs)) tel.tiltDeg else null
        val motion = if (hm != null) classifier.update(fm.tMs, pose, tilt, cfg)
        else MotionResult(MotionState.NOT_VISIBLE, Direction.NONE, 0.0, 0.0, 0.0)
        val boxStatus = boxMonitor.update(fm.tMs, pose?.pos, box, cfg)

        var floorVisible = 0
        var shift: Double? = null
        MarkerIds.FLOOR.forEachIndexed { i, id ->
            val m = byId[id] ?: return@forEachIndexed
            floorVisible++
            if (cal != null) {
                val d = m.center.dist(cal.imagePoint(i))
                shift = maxOf(shift ?: 0.0, d)
            }
        }

        robotM?.let { m ->
            val c = m.center
            if (trail.isEmpty() || trail.last().dist(c) > 3.0) {
                trail.addLast(c)
                while (trail.size > 600) trail.removeFirst()
            }
        }

        _frame.value = FrameState(
            tMs = fm.tMs, imgW = fm.width, imgH = fm.height,
            markers = fm.markers,
            calibrated = hm != null,
            robotPose = pose,
            robotImg = robotM,
            boxPos = box,
            boxStatus = boxStatus,
            stationPos = lastStation,
            stationVisible = stationM != null,
            motion = motion.state,
            direction = motion.direction,
            speedMps = motion.speedMps,
            omegaDps = motion.omegaDps,
            floorVisible = floorVisible,
            cameraShiftPx = shift,
            trail = trail.toList(),
            fps = fps,
        )
    }
}
