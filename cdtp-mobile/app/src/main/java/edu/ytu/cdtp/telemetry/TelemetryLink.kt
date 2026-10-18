package edu.ytu.cdtp.telemetry

import java.util.UUID

/**
 * Robot ile telefon arasındaki taşıma katmanı. Hangi donanım seçilirse seçilsin
 * (ESP32 BLE, HC-05 Klasik Bluetooth, Wi-Fi/Raspberry Pi) uygulamanın geri kalanı
 * aynı 11 byte'lık ICD paketini görür.
 */
enum class LinkKind(val label: String) {
    BLE("BLE (ESP32)"),
    CLASSIC_SPP("Klasik BT (HC-05/06)"),
    WIFI_UDP("Wi-Fi UDP"),
    SIMULATOR("Simülatör"),
}

enum class LinkPhase(val label: String) {
    IDLE("Bağlı değil"),
    SEARCHING("Aranıyor"),
    CONNECTING("Bağlanıyor"),
    CONNECTED("Bağlı"),
    ERROR("Hata"),
}

interface LinkCallbacks {
    /** Ham 11 byte ICD paketi (çerçeve ayıklanmış). */
    fun onPayload(bytes: ByteArray)
    fun onPhase(phase: LinkPhase, detail: String, deviceName: String? = null)
}

interface TelemetryLink {
    val kind: LinkKind
    fun start()
    fun stop()
    /** Watchdog veri akışı durduğunda çağırır. */
    fun restart()
}

object Icd {
    const val DEVICE_NAME = "CDTP-ROBOT"
    val SERVICE_UUID: UUID = UUID.fromString("8f3a0001-5c2e-4b7a-9d10-3e6f2a1b0c01")
    val TELEMETRY_UUID: UUID = UUID.fromString("8f3a0002-5c2e-4b7a-9d10-3e6f2a1b0c01")
    val COMMAND_UUID: UUID = UUID.fromString("8f3a0003-5c2e-4b7a-9d10-3e6f2a1b0c01")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val DEFAULT_UDP_PORT = 4210
}
