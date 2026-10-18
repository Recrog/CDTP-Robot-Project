package edu.ytu.cdtp

import android.content.Context
import edu.ytu.cdtp.report.HashChain
import edu.ytu.cdtp.task.EvalConfig
import edu.ytu.cdtp.vision.Calibration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class SettingsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("cdtp_settings", Context.MODE_PRIVATE)

    private val _evalConfig = MutableStateFlow(load("eval_config") ?: EvalConfig())
    val evalConfig: StateFlow<EvalConfig> = _evalConfig.asStateFlow()

    private val _frozen = MutableStateFlow(prefs.getBoolean("frozen", false))
    val frozen: StateFlow<Boolean> = _frozen.asStateFlow()

    private val _calibration = MutableStateFlow<Calibration?>(load("calibration"))
    val calibration: StateFlow<Calibration?> = _calibration.asStateFlow()

    private val _recordVideo = MutableStateFlow(prefs.getBoolean("record_video", true))
    val recordVideo: StateFlow<Boolean> = _recordVideo.asStateFlow()

    private inline fun <reified T> load(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    /** Dondurulduysa değişiklik reddedilir. */
    fun saveEvalConfig(c: EvalConfig): Boolean {
        if (_frozen.value) return false
        prefs.edit().putString("eval_config", json.encodeToString(c)).apply()
        _evalConfig.value = c
        return true
    }

    /** Geri alınamaz (uygulama verisi silinmedikçe). */
    fun freeze() {
        prefs.edit()
            .putBoolean("frozen", true)
            .putLong("frozen_at", System.currentTimeMillis())
            .putString("frozen_hash", configHash(_evalConfig.value))
            .apply()
        _frozen.value = true
    }

    val frozenAtMs: Long get() = prefs.getLong("frozen_at", 0)

    fun saveCalibration(c: Calibration) {
        prefs.edit().putString("calibration", json.encodeToString(c)).apply()
        _calibration.value = c
    }

    fun setRecordVideo(v: Boolean) {
        prefs.edit().putBoolean("record_video", v).apply()
        _recordVideo.value = v
    }

    var lastBleAddress: String?
        get() = prefs.getString("ble_addr", null)
        set(v) = prefs.edit().putString("ble_addr", v).apply()

    var lastSppAddress: String?
        get() = prefs.getString("spp_addr", null)
        set(v) = prefs.edit().putString("spp_addr", v).apply()

    var udpPort: Int
        get() = prefs.getInt("udp_port", 4210)
        set(v) = prefs.edit().putInt("udp_port", v).apply()

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun configHash(c: EvalConfig) = HashChain.sha256Hex(json.encodeToString(c).toByteArray())
    }
}
