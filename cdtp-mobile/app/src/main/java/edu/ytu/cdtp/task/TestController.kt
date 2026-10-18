package edu.ytu.cdtp.task

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import edu.ytu.cdtp.BuildConfig
import edu.ytu.cdtp.SettingsStore
import edu.ytu.cdtp.camera.CameraController
import edu.ytu.cdtp.report.EventDto
import edu.ytu.cdtp.report.HashChain
import edu.ytu.cdtp.report.LinkSummaryDto
import edu.ytu.cdtp.report.MeasuredDto
import edu.ytu.cdtp.report.RecordPayload
import edu.ytu.cdtp.report.RecordRepository
import edu.ytu.cdtp.report.RuleDto
import edu.ytu.cdtp.report.TelemetryDto
import edu.ytu.cdtp.report.TrackPointDto
import edu.ytu.cdtp.report.VideoDto
import edu.ytu.cdtp.task.evaluators.Evaluator
import edu.ytu.cdtp.task.evaluators.LiveLine
import edu.ytu.cdtp.task.evaluators.TrackSample
import edu.ytu.cdtp.telemetry.TelemetryRepository
import edu.ytu.cdtp.vision.VisionPipeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.roundToLong

enum class TestPhase { READY, RUNNING, FINALIZING, DONE, ERROR }

data class LiveTestState(
    val phase: TestPhase = TestPhase.READY,
    val elapsedMs: Long = 0,
    val lines: List<LiveLine> = emptyList(),
    val recording: Boolean = false,
    val recordId: Long? = null,
    val message: String? = null,
)

/**
 * Test oturumu (Plan 7.1-3..7): "Testi Başlat" zaman damgası + kamera kaydı,
 * 10 Hz örnekleme, otomatik bitiş, değerlendirme ve hash zincirine kayıt.
 * Başlatılan her test mutlaka kayda dönüşür (iptal yolu yok).
 */
class TestController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val telemetry: TelemetryRepository,
    private val vision: VisionPipeline,
    private val records: RecordRepository,
    private val camera: () -> CameraController?,
) {
    private val _spec = MutableStateFlow<TaskSpec?>(null)
    val spec: StateFlow<TaskSpec?> = _spec.asStateFlow()

    private val _state = MutableStateFlow(LiveTestState())
    val state: StateFlow<LiveTestState> = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val confined = Dispatchers.Default.limitedParallelism(1)

    private var loopJob: Job? = null
    private var eventsJob: Job? = null
    private var evaluator: Evaluator? = null
    private var cfg = EvalConfig()
    private var startElapsed = 0L
    private var startWall = 0L
    private val track = ArrayList<TrackPointDto>()
    private val telem = ArrayList<TelemetryDto>()
    private val events = ArrayList<EventDto>()
    private var videoFile: File? = null
    private var videoDone: CompletableDeferred<String?>? = null
    private var statsAtStart = telemetry.stats.value
    @Volatile private var stopRequested = false

    fun select(spec: TaskSpec) {
        if (_state.value.phase == TestPhase.RUNNING || _state.value.phase == TestPhase.FINALIZING) return
        _spec.value = spec
        _state.value = LiveTestState()
    }

    fun start() {
        val sp = _spec.value ?: return
        if (_state.value.phase != TestPhase.READY) return
        stopRequested = false
        _state.value = _state.value.copy(phase = TestPhase.RUNNING, message = null)
        scope.launch(confined) {
            cfg = settings.evalConfig.value
            evaluator = Evaluator.create(sp, cfg)
            track.clear(); telem.clear(); events.clear()
            startElapsed = SystemClock.elapsedRealtime()
            startWall = System.currentTimeMillis()
            statsAtStart = telemetry.stats.value
            vision.resetForTest(startElapsed)
            event("TEST", "Testi Başlat: ${sp.title}")
            if (!telemetry.isFresh()) event("UYARI", "Test başında telemetri yok")
            if (settings.calibration.value == null) event("UYARI", "Kalibrasyon yok — mesafe ölçülemez")

            videoFile = null; videoDone = null
            val cam = camera()
            if (settings.recordVideo.value && cam != null && cam.videoAvailable) {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
                val f = File(dir, "cdtp_${startWall}_${sp.type.name.lowercase()}.mp4")
                val d = CompletableDeferred<String?>()
                val ok = withContext(Dispatchers.Main) { cam.startRecording(f) { err -> d.complete(err) } }
                if (ok) { videoFile = f; videoDone = d; event("VIDEO", "Kamera kaydı başladı: ${f.name}") }
                else event("VIDEO", "Video başlatılamadı — zaman damgalı kayıt kullanılacak")
            } else {
                event("VIDEO", "Video kapalı/desteklenmiyor — zaman damgalı kayıt kullanılacak")
            }
            _state.update { it.copy(recording = videoFile != null) }

            eventsJob = scope.launch {
                telemetry.events.collect { e ->
                    withContext(confined) {
                        if (_state.value.phase == TestPhase.RUNNING) events += EventDto(e.elapsedMs - startElapsed, e.wallMs, e.type, e.message)
                    }
                }
            }
            loopJob = launch { loop() }
        }
    }

    /** Döngü en geç 100 ms içinde görür ve testi bitirir (yarış durumu olmasın diye bayrak). */
    fun stop() {
        if (_state.value.phase == TestPhase.RUNNING) stopRequested = true
    }

    private fun event(type: String, msg: String) {
        val now = SystemClock.elapsedRealtime()
        events += EventDto(now - startElapsed, System.currentTimeMillis(), type, msg)
    }

    private suspend fun loop() {
        var lastSeq = -1
        var shownMotion: MotionState? = null
        var candMotion: MotionState? = null
        var candSince = 0L
        var lastBox: BoxStatus? = null
        var lastTrackT = -1000L
        val ev = evaluator ?: return
        while (currentCoroutineContext().isActive) {
            if (stopRequested) { finish("Kullanıcı: Testi Bitir"); return }
            val now = SystemClock.elapsedRealtime()
            val t = now - startElapsed
            val fs = vision.frame.value?.takeIf { now - it.tMs < 500 }
            val tel = telemetry.latest.value
            val fresh = tel != null && now - tel.rxElapsedMs <= cfg.linkTimeoutMs

            if (tel != null && tel.seq != lastSeq && tel.rxElapsedMs >= startElapsed - 1000) {
                lastSeq = tel.seq
                telem += TelemetryDto(tel.seq, tel.robotTimeMs, tel.rxElapsedMs - startElapsed, tel.batteryPct, tel.tiltDeg, tel.charging, tel.stateCode)
            }

            val motion = fs?.motion ?: MotionState.NOT_VISIBLE
            val pose = fs?.robotPose
            val sample = TrackSample(
                tMs = t,
                robot = pose,
                motion = motion,
                direction = fs?.direction ?: Direction.NONE,
                boxStatus = fs?.boxStatus ?: BoxStatus.UNKNOWN,
                stationDistM = fs?.stationPos?.let { st -> pose?.pos?.dist(st) },
                tiltDeg = if (fresh) tel!!.tiltDeg else null,
                charging = if (fresh) tel!!.charging else null,
                batteryPct = if (fresh) tel!!.batteryPct else null,
                robotStateCode = if (fresh) tel!!.stateCode else null,
            )

            // Durum değişimlerini 0,3 s kararlı kaldıysa log'a yaz (titreme olmasın).
            if (motion != candMotion) { candMotion = motion; candSince = t }
            if (candMotion != shownMotion && t - candSince >= 300) {
                shownMotion = candMotion
                event("DURUM", "${motion.label}${if (sample.direction != Direction.NONE) " / ${sample.direction.label}" else ""}")
            }
            if (sample.boxStatus != lastBox) {
                if (sample.boxStatus == BoxStatus.DROPPED) event("KUTU", "KUTU DÜŞTÜ")
                lastBox = sample.boxStatus
            }
            if (t - lastTrackT >= 100 && track.size < 6000) {
                lastTrackT = t
                track += TrackPointDto(t, pose?.pos?.x?.r3(), pose?.pos?.y?.r3(), pose?.headingDeg?.r1(), motion.name, sample.boxStatus.name)
            }

            val end = ev.onSample(sample)
            ev.drainEvents().forEach { event("DEĞERLENDİRME", it) }
            _state.update { it.copy(elapsedMs = t, lines = ev.liveLines()) }
            if (end != null) { finish(end); return }
            delay(100)
        }
    }

    private suspend fun finish(reason: String) {
        _state.update { it.copy(phase = TestPhase.FINALIZING, message = "Kaydediliyor…") }
        val endElapsed = SystemClock.elapsedRealtime()
        val endWall = System.currentTimeMillis()
        val durationMs = endElapsed - startElapsed
        eventsJob?.cancel()
        event("TEST", "Test bitti: $reason")
        val sp = _spec.value!!
        val ev = evaluator!!

        var video: VideoDto? = null
        val vf = videoFile
        if (vf != null) {
            withContext(Dispatchers.Main) { camera()?.stopRecording() }
            val err = withTimeoutOrNull(8000) { videoDone?.await() }
            if (vf.exists() && vf.length() > 0) {
                val sha = withContext(Dispatchers.IO) { HashChain.fileSha256(vf) }
                video = VideoDto(vf.name, vf.absolutePath, sha, vf.length(), err)
            } else {
                event("VIDEO", "Video dosyası oluşmadı (${err ?: "bilinmeyen"}) — zaman damgalı kayıt geçerli")
            }
        }

        val result = ev.finish(durationMs, reason)
        val st = telemetry.stats.value
        // Test içindeki en büyük paket aralığı (başı ve sonu dahil)
        val rx = telem.map { it.tRelMs }.filter { it >= 0 }
        var maxGap = 0L
        if (rx.isNotEmpty()) {
            maxGap = maxOf(rx.first(), durationMs - rx.last())
            for (i in 1 until rx.size) maxGap = maxOf(maxGap, rx[i] - rx[i - 1])
        } else maxGap = durationMs
        val link = LinkSummaryDto(
            kind = telemetry.status.value.kind?.label ?: "yok",
            packets = (st.packets - statsAtStart.packets).coerceAtLeast(0),
            lost = (st.lost - statsAtStart.lost).coerceAtLeast(0),
            maxGapMs = maxGap,
            disconnects = (st.disconnects - statsAtStart.disconnects).coerceAtLeast(0),
            meanPeriodMs = if (rx.size >= 2) (rx.last() - rx.first()).toDouble() / (rx.size - 1) else 0.0,
        )

        try {
            val entity = records.append { index, prev ->
                RecordPayload(
                    index = index,
                    prevHash = prev,
                    app = "cdtp-mobile ${BuildConfig.VERSION_NAME}",
                    device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                    taskType = sp.type.name,
                    taskTitle = sp.title,
                    taskOptions = sp.options(),
                    startWallMs = startWall,
                    endWallMs = endWall,
                    durationMs = durationMs,
                    endReason = reason,
                    passed = result.passed,
                    rules = result.rules.map { RuleDto(it.name, it.passed, it.detail) },
                    measured = result.measured.map { MeasuredDto(it.first, it.second) },
                    link = link,
                    calibration = settings.calibration.value,
                    evalConfig = cfg,
                    evalConfigHash = SettingsStore.configHash(cfg),
                    evalConfigFrozen = settings.frozen.value,
                    video = video,
                    telemetry = telem.toList(),
                    events = events.toList(),
                    track = track.toList(),
                )
            }
            _state.update { it.copy(phase = TestPhase.DONE, recordId = entity.id, recording = false, message = null) }
        } catch (e: Exception) {
            _state.update { it.copy(phase = TestPhase.ERROR, recording = false, message = "Kayıt hatası: ${e.message}") }
        }
    }

    /** Sonuç ekranına geçildikten sonra yeni test için hazırla. */
    fun reset() {
        if (_state.value.phase == TestPhase.DONE || _state.value.phase == TestPhase.ERROR) _state.value = LiveTestState()
    }
}

private fun Double.r3() = (this * 1000).roundToLong() / 1000.0
private fun Double.r1() = (this * 10).roundToLong() / 10.0
