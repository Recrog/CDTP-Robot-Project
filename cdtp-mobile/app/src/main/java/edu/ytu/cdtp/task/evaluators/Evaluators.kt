package edu.ytu.cdtp.task.evaluators

import edu.ytu.cdtp.task.ChargeMode
import edu.ytu.cdtp.task.Direction
import edu.ytu.cdtp.task.EvalConfig
import edu.ytu.cdtp.task.MotionState
import edu.ytu.cdtp.task.TaskSpec
import edu.ytu.cdtp.telemetry.RobotState
import kotlin.math.abs

/** Modül A – 30 s statik denge. */
class StaticBalanceEvaluator(cfg: EvalConfig) : Evaluator(cfg) {
    private val targetMs = (cfg.staticDurationS * 1000).toLong()
    private var t = 0L

    override fun onSample(s: TrackSample): String? {
        acc.add(s)
        t = s.tMs
        if (acc.boxDropped) return "Kutu düştü"
        return if (s.tMs >= targetMs) "Süre doldu (${f(cfg.staticDurationS, 0)} s)" else null
    }

    override fun liveLines() = listOf(
        LiveLine("Süre ${f(t / 1000.0, 1)} / ${f(cfg.staticDurationS, 0)} s", if (t >= targetMs) true else null),
        LiveLine("Yer değiştirme ${f(acc.maxDispM, 3)} m (< ${f(cfg.staticMaxDisplacementM, 2)})", acc.maxDispM < cfg.staticMaxDisplacementM),
        LiveLine("Max |eğim| ${acc.maxAbsTilt?.let { f(it, 1) } ?: "-"}° (< ${f(cfg.fallTiltDeg, 0)})", acc.maxAbsTilt?.let { it < cfg.fallTiltDeg }),
        LiveLine("Kutu robotta", !acc.boxDropped),
    )

    override fun finish(durationMs: Long, endReason: String): EvalResult {
        val d = durationMs / 1000.0
        val tilt = acc.maxAbsTilt
        val rules = listOf(
            RuleResult(
                "Süre ${f(cfg.staticDurationS, 0)} s ±${f(cfg.durationTolS, 0)} s",
                abs(d - cfg.staticDurationS) <= cfg.durationTolS,
                "Ölçülen ${f(d, 1)} s",
            ),
            RuleResult(
                "Toplam yer değiştirme < ${f(cfg.staticMaxDisplacementM, 2)} m",
                acc.first != null && acc.maxDispM < cfg.staticMaxDisplacementM,
                "Başlangıçtan en büyük sapma ${f(acc.maxDispM, 3)} m",
            ),
            RuleResult(
                "Devrilme yok (|θ| < ${f(cfg.fallTiltDeg, 0)}°)",
                tilt != null && tilt < cfg.fallTiltDeg,
                tilt?.let { "Max |eğim| ${f(it, 1)}°" } ?: "Eğim telemetrisi alınamadı",
            ),
            boxRule(),
            trackingRule(),
        )
        return EvalResult(rules.all { it.passed }, rules, commonMeasured(durationMs))
    }
}

/** Modül B – yönlendirme: ileri/geri mesafe ya da sola/sağa ~90° dönüş. */
class GuidanceEvaluator(cfg: EvalConfig, private val spec: TaskSpec) : Evaluator(cfg) {
    private val dir = spec.expectedDirection ?: Direction.FORWARD
    private val isTurn = dir == Direction.LEFT || dir == Direction.RIGHT
    private val minD = spec.minDistM ?: cfg.guidanceMinDistM
    private val maxD = spec.maxDistM ?: cfg.guidanceMaxDistM
    private val staticStreak = Streak()
    private var moved = false
    private var lastMotion = MotionState.NOT_VISIBLE
    private var settledMs = 0L

    override fun onSample(s: TrackSample): String? {
        acc.add(s)
        lastMotion = s.motion
        if (!moved && (acc.pathM > 0.10 || abs(acc.netTurnDeg) > 20)) { moved = true; emit("Hareket başladı") }
        settledMs = staticStreak.update(s.tMs, s.motion == MotionState.STATIC)
        if (acc.boxDropped) return "Kutu düştü"
        if (moved && settledMs >= (cfg.settleS * 1000).toLong()) return "Robot durdu (${f(cfg.settleS, 0)} s statik)"
        if (s.tMs >= (cfg.guidanceTimeoutS * 1000).toLong()) return "Zaman aşımı (${f(cfg.guidanceTimeoutS, 0)} s)"
        return null
    }

    private fun turnSignedForDir(): Double = if ((dir == Direction.LEFT) != cfg.invertTurnSign) acc.netTurnDeg else -acc.netTurnDeg

    override fun liveLines(): List<LiveLine> = buildList {
        if (isTurn) {
            val turn = turnSignedForDir()
            add(LiveLine("Dönüş ${f(turn, 0)}° (${f(cfg.turnMinDeg, 0)}–${f(cfg.turnMaxDeg, 0)})", if (turn in cfg.turnMinDeg..cfg.turnMaxDeg) true else null))
        } else {
            add(LiveLine("Net mesafe ${f(acc.netDistM, 2)} m (${f(minD, 1)}–${f(maxD, 1)})", if (acc.netDistM in minD..maxD) true else null))
            add(LiveLine("Yön: ${acc.dominantDirection().label} (beklenen ${dir.label})", if (acc.dominantDirection() == dir) true else null))
        }
        add(LiveLine("Hareket ${if (moved) "başladı" else "bekleniyor"}", if (moved) true else null))
        add(LiveLine("Durma ${f(settledMs / 1000.0, 1)} / ${f(cfg.settleS, 0)} s", if (moved && lastMotion == MotionState.STATIC) true else null))
        add(LiveLine("Kutu robotta", !acc.boxDropped))
    }

    override fun finish(durationMs: Long, endReason: String): EvalResult {
        val rules = mutableListOf<RuleResult>()
        if (isTurn) {
            val turn = turnSignedForDir()
            rules += RuleResult(
                "Dönüş açısı ${f(cfg.turnMinDeg, 0)}–${f(cfg.turnMaxDeg, 0)}° (${dir.label})",
                turn in cfg.turnMinDeg..cfg.turnMaxDeg,
                "Ölçülen net dönüş ${f(acc.netTurnDeg, 1)}° → ${dir.label} yönünde ${f(turn, 1)}°",
            )
        } else {
            rules += RuleResult(
                "Net mesafe ${f(minD, 1)}–${f(maxD, 1)} m",
                acc.netDistM in minD..maxD,
                "Ölçülen ${f(acc.netDistM, 3)} m",
            )
            val dom = acc.dominantDirection()
            rules += RuleResult("Baskın yön = ${dir.label}", dom == dir, "Ölçülen ${dom.label}")
        }
        rules += RuleResult(
            "Sonda STATİK",
            lastMotion == MotionState.STATIC,
            "Son durum ${lastMotion.label}, kesintisiz statik ${f(settledMs / 1000.0, 1)} s",
        )
        rules += boxRule()
        rules += trackingRule()
        return EvalResult(rules.all { it.passed }, rules, commonMeasured(durationMs))
    }
}

/** Modül C – yerleşme anından itibaren ≤ 5 s içinde şarj; isteğe bağlı çek/geri getir. */
class ChargingEvaluator(cfg: EvalConfig, private val mode: ChargeMode) : Evaluator(cfg) {
    private var dockT: Long? = null
    private var chargeOnT: Long? = null
    private var chargingBeforeDock = false
    private var undockT: Long? = null
    private var chargeOffT: Long? = null
    private var redockT: Long? = null
    private var chargeOn2T: Long? = null
    private val dockTilts = ArrayList<Double>()
    private var batteryAtDock: Int? = null
    private var lastBattery: Int? = null
    private var sawSarjdaState = false
    private var lastStationDist: Double? = null

    private fun docked(s: TrackSample) =
        s.stationDistM != null && s.stationDistM < cfg.dockDistM && s.motion == MotionState.STATIC

    override fun onSample(s: TrackSample): String? {
        acc.add(s)
        lastBattery = s.batteryPct ?: lastBattery
        lastStationDist = s.stationDistM ?: lastStationDist
        if (s.robotStateCode == RobotState.SARJDA.code) sawSarjdaState = true
        val t = s.tMs
        if (dockT == null && docked(s)) {
            dockT = t; batteryAtDock = s.batteryPct
            if (s.charging == true) chargingBeforeDock = true
            emit("Yerleşme algılandı (istasyona ${f(s.stationDistM ?: 0.0, 2)} m)")
        }
        val dt = dockT
        if (dt != null && chargeOnT == null && s.charging == true) {
            chargeOnT = if (chargingBeforeDock) dt else t
            emit("Şarj başladı (+${f((chargeOnT!! - dt) / 1000.0, 1)} s)")
        }
        if (dt != null && chargeOnT == null && redockT == null) s.tiltDeg?.let { dockTilts += it }

        if (acc.boxDropped) return "Kutu düştü"
        val chargeLimit = (cfg.chargeStartMaxS * 1000).toLong()
        if (dt != null && chargeOnT == null && t - dt > chargeLimit + 3000) return "Şarj ${f(cfg.chargeStartMaxS, 0)} s içinde başlamadı"

        if (mode == ChargeMode.BASIC) {
            chargeOnT?.let { if (t - it >= 2000) return "Şarj doğrulandı" }
        } else chargeOnT?.let { on ->
            // Ö3.4: çekilince ≤ 2 s içinde sönmeli, geri gelince ≤ 5 s içinde yanmalı.
            val away = s.stationDistM != null && s.stationDistM > cfg.dockDistM + 0.05
            if (undockT == null && t > on && away) { undockT = t; emit("Robot istasyondan çekildi") }
            val ud = undockT
            if (ud != null && chargeOffT == null && s.charging == false) { chargeOffT = t; emit("Şarj kesildi (+${f((t - ud) / 1000.0, 1)} s)") }
            if (ud != null && redockT == null && t > ud && docked(s)) { redockT = t; emit("Robot yeniden yerleşti") }
            val rd = redockT
            if (rd != null && chargeOn2T == null && s.charging == true && (chargeOffT != null)) { chargeOn2T = t; emit("Şarj yeniden başladı (+${f((t - rd) / 1000.0, 1)} s)") }
            chargeOn2T?.let { if (t - it >= 2000) return "Çek/geri getir doğrulandı" }
            if (rd != null && chargeOn2T == null && t - rd > chargeLimit + 3000) return "Yeniden yerleşmede şarj başlamadı"
        }
        if (t >= (cfg.chargeTimeoutS * 1000).toLong()) return "Zaman aşımı (${f(cfg.chargeTimeoutS, 0)} s)"
        return null
    }

    private fun delayS(a: Long?, b: Long?) = if (a != null && b != null) (b - a) / 1000.0 else null

    override fun liveLines(): List<LiveLine> = buildList {
        add(LiveLine("İstasyona uzaklık ${lastStationDist?.let { f(it, 2) } ?: "?"} m (< ${f(cfg.dockDistM, 2)})", if (dockT != null) true else null))
        val d = delayS(dockT, chargeOnT)
        add(LiveLine("Şarj başlangıcı ${d?.let { "+${f(it, 1)} s" } ?: "bekleniyor"} (≤ ${f(cfg.chargeStartMaxS, 0)} s)", d?.let { it <= cfg.chargeStartMaxS }))
        if (mode == ChargeMode.UNPLUG_REPLUG) {
            val off = delayS(undockT, chargeOffT)
            add(LiveLine("Çekilince sönme ${off?.let { "+${f(it, 1)} s" } ?: "-"} (≤ ${f(cfg.chargeOffMaxS, 0)} s)", off?.let { it <= cfg.chargeOffMaxS }))
            val on2 = delayS(redockT, chargeOn2T)
            add(LiveLine("Geri gelince yanma ${on2?.let { "+${f(it, 1)} s" } ?: "-"} (≤ ${f(cfg.chargeStartMaxS, 0)} s)", on2?.let { it <= cfg.chargeStartMaxS }))
        }
        add(LiveLine("Kutu robotta", !acc.boxDropped))
    }

    override fun finish(durationMs: Long, endReason: String): EvalResult {
        val rules = mutableListOf<RuleResult>()
        rules += RuleResult(
            "Kamera: robot istasyona yerleşti (< ${f(cfg.dockDistM, 2)} m ve STATİK)",
            dockT != null,
            dockT?.let { "Yerleşme t=${f(it / 1000.0, 1)} s" } ?: "Yerleşme görülmedi (son uzaklık ${lastStationDist?.let { f(it, 2) } ?: "?"} m)",
        )
        val d = delayS(dockT, chargeOnT)
        rules += RuleResult(
            "Telemetri: şarj ≤ ${f(cfg.chargeStartMaxS, 0)} s içinde başladı",
            d != null && d <= cfg.chargeStartMaxS,
            d?.let { "Gecikme ${f(it, 1)} s" + if (chargingBeforeDock) " (yerleşmeden önce zaten şarjda)" else "" } ?: "Şarj sinyali gelmedi",
        )
        if (cfg.dockTiltCheck) {
            val avg = if (dockTilts.isEmpty()) null else dockTilts.average()
            rules += RuleResult(
                "Eğim: dock'a yaslanma ${f(cfg.dockTiltMinDeg, 0)}…${f(cfg.dockTiltMaxDeg, 0)}°",
                avg != null && avg in cfg.dockTiltMinDeg..cfg.dockTiltMaxDeg,
                avg?.let { "Yerleşmedeki ortalama eğim ${f(it, 1)}°" } ?: "Eğim verisi yok",
            )
        }
        if (mode == ChargeMode.UNPLUG_REPLUG) {
            val off = delayS(undockT, chargeOffT)
            rules += RuleResult("Çekilince ≤ ${f(cfg.chargeOffMaxS, 0)} s içinde şarj kesildi", off != null && off <= cfg.chargeOffMaxS,
                off?.let { "Gecikme ${f(it, 1)} s (telemetri 1 Hz → ±1 s çözünürlük)" } ?: "Görülmedi")
            val on2 = delayS(redockT, chargeOn2T)
            rules += RuleResult("Geri gelince ≤ ${f(cfg.chargeStartMaxS, 0)} s içinde şarj başladı", on2 != null && on2 <= cfg.chargeStartMaxS,
                on2?.let { "Gecikme ${f(it, 1)} s" } ?: "Görülmedi")
        }
        rules += boxRule()
        val m = commonMeasured(durationMs)
        m += "Yerleşme anı" to (dockT?.let { "${f(it / 1000.0, 1)} s" } ?: "-")
        m += "Şarj başlama gecikmesi" to (d?.let { "${f(it, 1)} s" } ?: "-")
        m += "İstasyona son uzaklık" to (lastStationDist?.let { "${f(it, 3)} m" } ?: "-")
        m += "Batarya (yerleşme → son)" to "${batteryAtDock ?: "-"}% → ${lastBattery ?: "-"}%"
        m += "Robot durumu ŞARJDA görüldü" to (if (sawSarjdaState) "evet" else "hayır")
        m += "Doğrulama kaynakları" to "kamera (konum) + eğim + şarj/pil telemetrisi"
        return EvalResult(rules.all { it.passed }, rules, m)
    }
}

/** Modül E – bütünleşik görev durum makinesi. */
class IntegratedEvaluator(cfg: EvalConfig) : Evaluator(cfg) {
    private enum class Step(val title: String) {
        HOLD_START("Başlangıçta 5 s statik"),
        MOVE("Hareket (~1 m + dönüş)"),
        HOLD_TARGET("Hedefte 5 s statik"),
        APPROACH("İstasyona yaklaşma"),
        DOCK("Yerleşme (docking)"),
        CHARGE("≤ 5 s içinde şarj"),
    }

    private val steps = Step.entries
    private var idx = 0
    private var stepStart = 0L
    private val doneAt = LongArray(steps.size) { -1 }
    private var failedReason: String? = null
    private val streak = Streak()
    private var stepPath0 = 0.0
    private var stepTurn0 = 0.0
    private var maxTurnInMove = 0.0
    private var lastStationDist: Double? = null

    private val hold get() = (cfg.integStaticHoldS * 1000).toLong()
    private val timeout get() = (cfg.integStepTimeoutS * 1000).toLong()

    private fun advance(t: Long) {
        doneAt[idx] = t
        emit("Adım tamam: ${steps[idx].title} (t=${f(t / 1000.0, 1)} s)")
        idx++
        stepStart = t
        streak.reset()
        stepPath0 = acc.pathM
        stepTurn0 = acc.netTurnDeg
        maxTurnInMove = 0.0
    }

    override fun onSample(s: TrackSample): String? {
        acc.add(s)
        lastStationDist = s.stationDistM ?: lastStationDist
        val t = s.tMs
        if (acc.boxDropped) { failedReason = "Kutu düştü"; return failedReason }
        if (idx >= steps.size) return if (t - doneAt.last() >= 1000) "Bütün adımlar tamamlandı" else null
        val isStatic = s.motion == MotionState.STATIC
        when (steps[idx]) {
            Step.HOLD_START, Step.HOLD_TARGET -> if (streak.update(t, isStatic) >= hold) advance(t)
            Step.MOVE -> {
                val path = acc.pathM - stepPath0
                maxTurnInMove = maxOf(maxTurnInMove, abs(acc.netTurnDeg - stepTurn0))
                val turnOk = !cfg.integRequireTurn || maxTurnInMove >= cfg.integTurnDeg
                if (path >= cfg.integMovePathM && turnOk) advance(t)
            }
            Step.APPROACH -> if (s.stationDistM != null && s.stationDistM < cfg.integApproachZoneM) advance(t)
            Step.DOCK -> if (s.stationDistM != null && s.stationDistM < cfg.dockDistM && isStatic) advance(t)
            Step.CHARGE -> {
                if (s.charging == true) advance(t)
                else if (t - stepStart > (cfg.chargeStartMaxS * 1000).toLong()) {
                    failedReason = "Şarj ${f(cfg.chargeStartMaxS, 0)} s içinde başlamadı"; return failedReason
                }
            }
        }
        if (idx < steps.size && steps[idx] != Step.CHARGE && t - stepStart > timeout) {
            failedReason = "Zaman aşımı: ${steps[idx].title}"; return failedReason
        }
        return null
    }

    override fun liveLines(): List<LiveLine> = steps.mapIndexed { i, st ->
        val extra = when {
            i != idx -> ""
            st == Step.MOVE -> " — yol ${f(acc.pathM - stepPath0, 2)} m, dönüş ${f(maxTurnInMove, 0)}°"
            st == Step.APPROACH || st == Step.DOCK -> " — istasyon ${lastStationDist?.let { f(it, 2) } ?: "?"} m"
            else -> ""
        }
        val ok = when {
            doneAt[i] >= 0 -> true
            i == idx && failedReason != null -> false
            else -> null
        }
        LiveLine("${i + 1}. ${st.title}$extra", ok)
    } + LiveLine("Kutu robotta", !acc.boxDropped)

    override fun finish(durationMs: Long, endReason: String): EvalResult {
        val rules = steps.mapIndexed { i, st ->
            RuleResult(
                st.title,
                doneAt[i] >= 0,
                if (doneAt[i] >= 0) "t=${f(doneAt[i] / 1000.0, 1)} s'de tamamlandı"
                else if (i == idx) (failedReason ?: "Tamamlanmadı ($endReason)") else "Önceki adım tamamlanmadı",
            )
        } + boxRule()
        val m = commonMeasured(durationMs)
        m += "Tamamlanan adım" to "${steps.count { doneAt[it.ordinal] >= 0 }}/${steps.size}"
        m += "İstasyona son uzaklık" to (lastStationDist?.let { "${f(it, 3)} m" } ?: "-")
        return EvalResult(rules.all { it.passed }, rules, m)
    }
}

/** Ö1.4 – ±10° eğimden bırakılınca 10 s içinde toparlanma (ek görev). */
class RecoveryEvaluator(cfg: EvalConfig) : Evaluator(cfg) {
    private var initialTilt: Double? = null
    private var maxEarly = 0.0
    private var settledT: Long? = null
    private var fell = false
    private var t = 0L

    override fun onSample(s: TrackSample): String? {
        acc.add(s)
        t = s.tMs
        s.tiltDeg?.let { tilt ->
            if (initialTilt == null) initialTilt = tilt
            if (s.tMs <= 2000) maxEarly = maxOf(maxEarly, abs(tilt))
            if (abs(tilt) >= cfg.fallTiltDeg) fell = true
            if (settledT == null && abs(tilt) < cfg.recoverySettledDeg && maxOf(maxEarly, abs(initialTilt ?: 0.0)) >= cfg.recoveryMinInitialDeg) {
                settledT = s.tMs; emit("Toparlandı (t=${f(s.tMs / 1000.0, 1)} s)")
            }
        }
        if (acc.boxDropped) return "Kutu düştü"
        if (fell) return "Robot devrildi"
        return if (s.tMs >= (cfg.recoveryWindowS * 1000).toLong()) "Süre doldu (${f(cfg.recoveryWindowS, 0)} s)" else null
    }

    private val startTilt get() = maxOf(maxEarly, abs(initialTilt ?: 0.0))

    override fun liveLines() = listOf(
        LiveLine("Başlangıç eğimi ${f(startTilt, 1)}° (≥ ${f(cfg.recoveryMinInitialDeg, 0)})", if (startTilt >= cfg.recoveryMinInitialDeg) true else null),
        LiveLine("Toparlanma ${settledT?.let { "${f(it / 1000.0, 1)} s" } ?: "bekleniyor"} (≤ ${f(cfg.recoveryWindowS, 0)} s)", if (settledT != null) true else null),
        LiveLine("Devrilme yok", !fell),
        LiveLine("Kutu robotta", !acc.boxDropped),
    )

    override fun finish(durationMs: Long, endReason: String): EvalResult {
        val rules = listOf(
            RuleResult("Başlangıç eğimi ≥ ${f(cfg.recoveryMinInitialDeg, 0)}°", startTilt >= cfg.recoveryMinInitialDeg, "Ölçülen ${f(startTilt, 1)}°"),
            RuleResult(
                "${f(cfg.recoveryWindowS, 0)} s içinde |θ| < ${f(cfg.recoverySettledDeg, 0)}°",
                settledT != null && settledT!! <= (cfg.recoveryWindowS * 1000).toLong(),
                settledT?.let { "t=${f(it / 1000.0, 1)} s'de toparlandı" } ?: "Toparlanma görülmedi",
            ),
            RuleResult("Devrilme yok", !fell && acc.maxAbsTilt != null, if (acc.maxAbsTilt == null) "Eğim verisi yok" else "Max |eğim| ${f(acc.maxAbsTilt!!, 1)}°"),
            boxRule(),
        )
        val m = commonMeasured(durationMs)
        m += "Başlangıç eğimi" to "${f(startTilt, 1)}°"
        m += "Toparlanma süresi" to (settledT?.let { "${f(it / 1000.0, 1)} s" } ?: "-")
        return EvalResult(rules.all { it.passed }, rules, m)
    }
}
