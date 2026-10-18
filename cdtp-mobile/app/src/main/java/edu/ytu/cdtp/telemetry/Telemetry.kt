package edu.ytu.cdtp.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** ICD'deki `state` alanı (Bölüm 5). */
enum class RobotState(val code: Int, val label: String) {
    BEKLE(0, "BEKLE"),
    MANUEL(1, "MANUEL"),
    DOCKING(2, "DOCKING"),
    SARJDA(3, "ŞARJDA"),
    HATA(4, "HATA"),
    BILINMIYOR(-1, "?");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: BILINMIYOR
    }
}

/** Robotun 1 Hz telemetri paketi + telefonun alış zamanı. */
data class Telemetry(
    val seq: Int,
    val robotTimeMs: Long,
    val batteryPct: Int,
    val tiltDeg: Double,
    val charging: Boolean,
    val stateCode: Int,
    /** SystemClock.elapsedRealtime() — aralık ölçümü için */
    val rxElapsedMs: Long,
    /** System.currentTimeMillis() — rapordaki zaman damgası için */
    val rxWallMs: Long,
) {
    val state: RobotState get() = RobotState.of(stateCode)
}

/**
 * ICD telemetri paketi: 11 byte, little-endian.
 *
 * | 0 seq u16 | 2 t_ms u32 | 6 battery u8 | 7 tilt_x10 i16 | 9 charging u8 | 10 state u8 |
 *
 * Akış tabanlı bağlantılarda (Klasik Bluetooth SPP / UART) paket çerçevelenir:
 * `A5 5A <11 byte> <XOR(11 byte)>` = 14 byte.
 */
object TelemetryCodec {
    const val PAYLOAD_LEN = 11
    const val FRAME_LEN = 14
    const val SYNC1: Byte = 0xA5.toByte()
    const val SYNC2: Byte = 0x5A

    fun parse(b: ByteArray, rxElapsedMs: Long, rxWallMs: Long): Telemetry? {
        if (b.size < PAYLOAD_LEN) return null
        val bb = ByteBuffer.wrap(b, 0, PAYLOAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
        val seq = bb.getShort(0).toInt() and 0xFFFF
        val t = bb.getInt(2).toLong() and 0xFFFFFFFFL
        val battery = b[6].toInt() and 0xFF
        val tilt = bb.getShort(7) / 10.0
        val charging = b[9].toInt() != 0
        val state = b[10].toInt() and 0xFF
        return Telemetry(seq, t, battery, tilt, charging, state, rxElapsedMs, rxWallMs)
    }

    fun encode(seq: Int, robotTimeMs: Long, batteryPct: Int, tiltDeg: Double, charging: Boolean, state: Int): ByteArray {
        val bb = ByteBuffer.allocate(PAYLOAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort((seq and 0xFFFF).toShort())
        bb.putInt((robotTimeMs and 0xFFFFFFFFL).toInt())
        bb.put(batteryPct.coerceIn(0, 255).toByte())
        bb.putShort((tiltDeg * 10).roundToInt().coerceIn(-32768, 32767).toShort())
        bb.put(if (charging) 1 else 0)
        bb.put(state.coerceIn(0, 255).toByte())
        return bb.array()
    }

    fun xor(b: ByteArray, from: Int = 0, len: Int = b.size): Byte {
        var x = 0
        for (i in from until from + len) x = x xor (b[i].toInt() and 0xFF)
        return x.toByte()
    }

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size == PAYLOAD_LEN)
        return byteArrayOf(SYNC1, SYNC2) + payload + byteArrayOf(xor(payload))
    }
}

/** Bayt akışından `A5 5A ... XOR` çerçevelerini ayıklar. */
class FrameDecoder(private val onPayload: (ByteArray) -> Unit) {
    private val buf = ByteArray(TelemetryCodec.FRAME_LEN)
    private var n = 0
    var checksumErrors = 0
        private set

    fun feed(data: ByteArray, len: Int = data.size) {
        for (i in 0 until len) push(data[i])
    }

    private fun push(b: Byte) {
        when (n) {
            0 -> if (b == TelemetryCodec.SYNC1) buf[n++] = b
            1 -> when (b) {
                TelemetryCodec.SYNC2 -> buf[n++] = b
                TelemetryCodec.SYNC1 -> n = 1
                else -> n = 0
            }
            else -> {
                buf[n++] = b
                if (n == TelemetryCodec.FRAME_LEN) {
                    val payload = buf.copyOfRange(2, 2 + TelemetryCodec.PAYLOAD_LEN)
                    if (TelemetryCodec.xor(payload) == buf[TelemetryCodec.FRAME_LEN - 1]) onPayload(payload)
                    else checksumErrors++
                    n = 0
                }
            }
        }
    }
}
