package edu.ytu.cdtp.task

import kotlinx.serialization.Serializable

/**
 * Bütün eşikler burada. Plan: 12. haftada dondurulur (Ayarlar → DONDUR).
 * Her kayda bu yapının kopyası ve SHA-256'sı yazılır.
 */
@Serializable
data class EvalConfig(
    // Hareket sınıflandırma (Plan 7.6)
    val motionWindowS: Double = 0.5,
    val filterFrames: Int = 7,
    val staticSpeedMps: Double = 0.03,
    val staticOmegaDps: Double = 10.0,
    val turnOmegaDps: Double = 20.0,
    val recoverTiltDeg: Double = 5.0,
    val fallTiltDeg: Double = 30.0,
    val invertTurnSign: Boolean = false,

    // Kutu düştü tespiti
    val boxMaxDistM: Double = 0.15,
    val boxMissingS: Double = 1.0,

    // Statik denge
    val staticDurationS: Double = 30.0,
    val durationTolS: Double = 2.0,
    val staticMaxDisplacementM: Double = 0.10,
    val minTrackingRatio: Double = 0.7,

    // Yönlendirme (Açık Soru #1: iki ön ayar)
    val guidanceMinDistM: Double = 0.8,
    val guidanceMaxDistM: Double = 1.2,
    val guidanceShortMinDistM: Double = 0.3,
    val guidanceShortMaxDistM: Double = 0.7,
    val turnMinDeg: Double = 60.0,
    val turnMaxDeg: Double = 120.0,
    val settleS: Double = 3.0,
    val guidanceTimeoutS: Double = 40.0,

    // Şarj
    val dockDistM: Double = 0.25,
    val chargeStartMaxS: Double = 5.0,
    val chargeOffMaxS: Double = 2.0,
    val chargeTimeoutS: Double = 90.0,
    val dockTiltCheck: Boolean = false,
    val dockTiltMinDeg: Double = -15.0,
    val dockTiltMaxDeg: Double = 15.0,

    // Bütünleşik görev
    val integStaticHoldS: Double = 5.0,
    val integMovePathM: Double = 0.8,
    val integTurnDeg: Double = 45.0,
    val integRequireTurn: Boolean = true,
    val integApproachZoneM: Double = 0.40,
    val integStepTimeoutS: Double = 90.0,

    // Toparlanma (Ö1.4)
    val recoveryWindowS: Double = 10.0,
    val recoverySettledDeg: Double = 3.0,
    val recoveryMinInitialDeg: Double = 8.0,

    // Bağlantı / kamera
    val linkTimeoutMs: Long = 2000,
    val cameraMovedPx: Double = 25.0,
)
