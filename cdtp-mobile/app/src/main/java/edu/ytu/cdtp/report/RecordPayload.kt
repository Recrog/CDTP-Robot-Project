package edu.ytu.cdtp.report

import edu.ytu.cdtp.task.EvalConfig
import edu.ytu.cdtp.vision.Calibration
import kotlinx.serialization.Serializable

/** Hash'lenen kayıt içeriği. Alan sırası sabittir; JSON metni olduğu gibi saklanır. */
@Serializable
data class RecordPayload(
    val schema: Int = 1,
    val index: Long,
    val prevHash: String,
    val app: String,
    val device: String,
    val taskType: String,
    val taskTitle: String,
    val taskOptions: Map<String, String>,
    val startWallMs: Long,
    val endWallMs: Long,
    val durationMs: Long,
    val endReason: String,
    val passed: Boolean,
    val rules: List<RuleDto>,
    val measured: List<MeasuredDto>,
    val link: LinkSummaryDto,
    val calibration: Calibration?,
    val evalConfig: EvalConfig,
    val evalConfigHash: String,
    val evalConfigFrozen: Boolean,
    val video: VideoDto?,
    val telemetry: List<TelemetryDto>,
    val events: List<EventDto>,
    val track: List<TrackPointDto>,
)

@Serializable data class RuleDto(val name: String, val passed: Boolean, val detail: String)
@Serializable data class MeasuredDto(val key: String, val value: String)

@Serializable
data class TelemetryDto(
    val seq: Int,
    val tRobotMs: Long,
    /** testin başlangıcına göre alış zamanı */
    val tRelMs: Long,
    val battery: Int,
    val tilt: Double,
    val charging: Boolean,
    val state: Int,
)

@Serializable data class EventDto(val tRelMs: Long, val wallMs: Long, val type: String, val msg: String)

/** 10 Hz yörünge noktası (m, derece). m = hareket durumu, b = kutu durumu. */
@Serializable
data class TrackPointDto(val t: Long, val x: Double?, val y: Double?, val h: Double?, val m: String, val b: String)

@Serializable
data class VideoDto(val fileName: String, val path: String, val sha256: String, val sizeBytes: Long, val note: String? = null)

@Serializable
data class LinkSummaryDto(
    val kind: String,
    val packets: Int,
    val lost: Int,
    val maxGapMs: Long,
    val disconnects: Int,
    val meanPeriodMs: Double,
)
