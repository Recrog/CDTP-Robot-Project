package edu.ytu.cdtp.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Klasik Bluetooth SPP (HC-05/HC-06 veya ESP32 BluetoothSerial).
 * Cihaz önce telefonun Bluetooth ayarlarından eşleştirilmelidir.
 * Akış çerçeveli gelir: A5 5A <11 byte> XOR.
 */
@SuppressLint("MissingPermission")
class SppLink(
    private val ctx: Context,
    private val cb: LinkCallbacks,
    private val address: String,
) : TelemetryLink {
    override val kind = LinkKind.CLASSIC_SPP
    private var scope: CoroutineScope? = null
    @Volatile private var socket: BluetoothSocket? = null

    override fun start() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) { cb.onPhase(LinkPhase.ERROR, "Bluetooth yok"); return@launch }
            while (isActive) {
                try {
                    if (!adapter.isEnabled) throw java.io.IOException("Bluetooth kapalı")
                    val dev = adapter.getRemoteDevice(address)
                    cb.onPhase(LinkPhase.CONNECTING, "RFCOMM bağlanıyor: $address", dev.name)
                    adapter.cancelDiscovery()
                    val sock = dev.createRfcommSocketToServiceRecord(Icd.SPP_UUID)
                    socket = sock
                    sock.connect()
                    cb.onPhase(LinkPhase.CONNECTED, "SPP bağlı", dev.name)
                    val dec = FrameDecoder { cb.onPayload(it) }
                    val input = sock.inputStream
                    val buf = ByteArray(256)
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        dec.feed(buf, n)
                    }
                } catch (e: Exception) {
                    if (isActive) cb.onPhase(LinkPhase.CONNECTING, "SPP hata: ${e.message}; 2 s sonra yeniden")
                } finally {
                    closeSocket()
                }
                delay(2000)
            }
        }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    override fun stop() {
        scope?.cancel(); scope = null
        closeSocket()
    }

    /** Soketi kapatmak okuma döngüsünü düşürür; döngü yeniden bağlanır. */
    override fun restart() = closeSocket()

    companion object {
        fun bondedDevices(ctx: Context): List<Pair<String, String>> = try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.map { (it.name ?: "(isimsiz)") to it.address }
                ?.sortedBy { it.first } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}

/**
 * Wi-Fi UDP: robot (ESP32 Wi-Fi, Raspberry Pi vb.) paketleri telefonun IP'sine ya da
 * yayın adresine (255.255.255.255) gönderir. Ham 11 byte ya da 14 byte çerçeve kabul edilir.
 */
class UdpLink(
    private val ctx: Context,
    private val cb: LinkCallbacks,
    private val port: Int,
) : TelemetryLink {
    override val kind = LinkKind.WIFI_UDP
    private var scope: CoroutineScope? = null
    @Volatile private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    override fun start() {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        lock = wifi?.createMulticastLock("cdtp-udp")?.apply { setReferenceCounted(false); acquire() }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (isActive) {
                try {
                    val sock = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 1000
                        bind(InetSocketAddress(port))
                    }
                    socket = sock
                    cb.onPhase(LinkPhase.CONNECTED, "UDP :$port dinleniyor")
                    val dec = FrameDecoder { cb.onPayload(it) }
                    val buf = ByteArray(512)
                    var lastSender: String? = null
                    while (isActive) {
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(p)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val sender = p.address?.hostAddress
                        if (sender != lastSender) {
                            lastSender = sender
                            cb.onPhase(LinkPhase.CONNECTED, "UDP :$port", sender)
                        }
                        if (p.length == TelemetryCodec.PAYLOAD_LEN && buf[0] != TelemetryCodec.SYNC1) {
                            cb.onPayload(buf.copyOf(p.length))
                        } else {
                            dec.feed(buf, p.length)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) cb.onPhase(LinkPhase.ERROR, "UDP hata: ${e.message}")
                } finally {
                    runCatching { socket?.close() }
                    socket = null
                }
                delay(2000)
            }
        }
    }

    override fun stop() {
        scope?.cancel(); scope = null
        runCatching { socket?.close() }
        runCatching { lock?.release() }
    }

    /** UDP'de oturum yok; sadece dinlemeye devam edilir. */
    override fun restart() {}
}

/**
 * Donanım olmadan geliştirme/test için uygulama içi robot simülatörü.
 * ESP32 simülatörüyle aynı veriyi üretir: eğim = sinüs + gürültü, batarya yavaşça azalır.
 */
class SimulatedLink(private val cb: LinkCallbacks) : TelemetryLink {
    override val kind = LinkKind.SIMULATOR
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var charging = false
        private set
    @Volatile var state = RobotState.BEKLE.code
    @Volatile private var dropoutUntil = 0L
    @Volatile private var kickAt = -1L
    @Volatile private var kickDeg = 0.0
    private var battery = 87.0

    fun toggleCharging() {
        charging = !charging
        state = if (charging) RobotState.SARJDA.code else RobotState.BEKLE.code
    }

    /** Belirtilen süre boyunca paket göndermez (kopma testi). */
    fun simulateDropout(ms: Long = 3000) {
        dropoutUntil = SystemClock.elapsedRealtime() + ms
    }

    /** Robotu ±deg kadar ittirmiş gibi eğim üretir, sonra sönümlenir (toparlanma). */
    fun kickTilt(deg: Double = 10.0) {
        kickDeg = deg
        kickAt = SystemClock.elapsedRealtime()
    }

    override fun start() {
        job?.cancel()
        job = scope.launch {
            cb.onPhase(LinkPhase.CONNECTED, "Simülatör çalışıyor", "SIM-ROBOT")
            val t0 = SystemClock.elapsedRealtime()
            var next = t0
            var seq = 0
            while (isActive) {
                next += 1000
                delay((next - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                val now = SystemClock.elapsedRealtime()
                val t = now - t0
                battery = if (charging) (battery + 0.5).coerceAtMost(100.0) else (battery - 0.02).coerceAtLeast(0.0)
                var tilt = 1.5 * sin(2 * PI * t / 8000.0) + Random.nextDouble(-0.3, 0.3)
                if (kickAt >= 0) tilt += kickDeg * exp(-(now - kickAt) / 1500.0)
                val payload = TelemetryCodec.encode(seq, t, battery.toInt(), tilt, charging, state)
                seq = (seq + 1) and 0xFFFF
                if (now < dropoutUntil) continue
                cb.onPayload(payload)
            }
        }
    }

    override fun stop() {
        job?.cancel(); job = null
    }

    override fun restart() {}
}
