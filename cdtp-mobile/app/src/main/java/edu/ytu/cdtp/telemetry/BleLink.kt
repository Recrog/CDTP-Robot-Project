package edu.ytu.cdtp.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ESP32 (NimBLE) robotuna BLE ile bağlanır, telemetri karakteristiğine abone olur.
 * Bağlantı düşerse kendiliğinden tekrar bağlanır.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val ctx: Context,
    private val cb: LinkCallbacks,
    private var address: String?,
) : TelemetryLink {
    override val kind = LinkKind.BLE

    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var scanCb: ScanCallback? = null
    @Volatile private var stopped = true
    private val retry = Runnable { begin() }
    private val connectTimeout = Runnable {
        cb.onPhase(LinkPhase.CONNECTING, "Bağlantı zaman aşımı, yeniden deneniyor")
        closeGatt(); scheduleRetry(500)
    }

    /** Son bağlanılan adres (ayarlarda saklamak için). */
    val currentAddress: String? get() = address

    override fun start() {
        stopped = false
        main.post { begin() }
    }

    override fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        main.post {
            stopScan()
            closeGatt()
        }
    }

    override fun restart() {
        main.post {
            if (stopped) return@post
            main.removeCallbacks(retry)
            main.removeCallbacks(connectTimeout)
            stopScan()
            closeGatt()
            begin()
        }
    }

    private fun scheduleRetry(ms: Long) {
        if (stopped) return
        main.removeCallbacks(retry)
        main.postDelayed(retry, ms)
    }

    private fun begin() {
        try {
            beginUnsafe()
        } catch (e: SecurityException) {
            cb.onPhase(LinkPhase.ERROR, "Bluetooth izni yok — Ana ekrandan izin verin")
        }
    }

    private fun beginUnsafe() {
        if (stopped) return
        val a = adapter
        if (a == null) {
            cb.onPhase(LinkPhase.ERROR, "Bu cihazda Bluetooth yok"); return
        }
        if (!a.isEnabled) {
            cb.onPhase(LinkPhase.ERROR, "Bluetooth kapalı — açın")
            scheduleRetry(3000); return
        }
        val addr = address
        if (addr != null) connect(a.getRemoteDevice(addr)) else scan()
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { scheduleRetry(2000); return }
        cb.onPhase(LinkPhase.SEARCHING, "${Icd.DEVICE_NAME} aranıyor…")
        val c = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!isCdtp(result)) return
                main.post {
                    if (scanCb == null) return@post
                    stopScan()
                    address = result.device.address
                    connect(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    cb.onPhase(LinkPhase.ERROR, "Tarama hatası ($errorCode)")
                    scanCb = null
                    scheduleRetry(3000)
                }
            }
        }
        scanCb = c
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, c)
        main.postDelayed({
            if (scanCb === c) {
                stopScan()
                cb.onPhase(LinkPhase.SEARCHING, "Bulunamadı, tekrar aranıyor…")
                scheduleRetry(500)
            }
        }, 12_000)
    }

    private fun stopScan() {
        val c = scanCb ?: return
        scanCb = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(c) }
    }

    private fun connect(dev: BluetoothDevice) {
        if (stopped) return
        closeGatt()
        cb.onPhase(LinkPhase.CONNECTING, "Bağlanıyor: ${dev.address}", dev.name)
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, 10_000)
    }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(Icd.CCCD_UUID)
        if (d == null) {
            main.removeCallbacks(connectTimeout)
            cb.onPhase(LinkPhase.CONNECTED, "Bağlı (CCCD yok)", g.device.name)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) { runCatching { g.close() }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    cb.onPhase(LinkPhase.CONNECTING, "Servisler keşfediliyor", g.device.name)
                    main.postDelayed({ if (gatt === g) g.discoverServices() }, 300)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    closeGatt()
                    if (!stopped) {
                        cb.onPhase(LinkPhase.CONNECTING, "Bağlantı kesildi (status=$status), yeniden bağlanılıyor")
                        scheduleRetry(1000)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val ch = g.getService(Icd.SERVICE_UUID)?.getCharacteristic(Icd.TELEMETRY_UUID)
                if (ch == null) {
                    cb.onPhase(LinkPhase.ERROR, "Telemetri servisi bulunamadı (UUID'leri kontrol edin)")
                    closeGatt(); scheduleRetry(3000)
                    return@post
                }
                enableNotify(g, ch)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                main.removeCallbacks(connectTimeout)
                if (status == BluetoothGatt.GATT_SUCCESS) cb.onPhase(LinkPhase.CONNECTED, "Bildirimler açık", g.device.name)
                else cb.onPhase(LinkPhase.ERROR, "Bildirim açılamadı (status=$status)", g.device.name)
            }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            if (characteristic.uuid == Icd.TELEMETRY_UUID) cb.onPayload(v.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == Icd.TELEMETRY_UUID) cb.onPayload(value)
        }
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun isCdtp(r: ScanResult): Boolean {
            val name = r.scanRecord?.deviceName ?: r.device.name
            if (name != null && name.startsWith("CDTP")) return true
            return r.scanRecord?.serviceUuids?.contains(ParcelUuid(Icd.SERVICE_UUID)) == true
        }
    }
}

/** Bağlantı ekranındaki "BLE Tara" listesi. */
@SuppressLint("MissingPermission")
class BleScanner(private val ctx: Context) {
    data class Found(val name: String, val address: String, val rssi: Int, val isCdtp: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val _results = MutableStateFlow<List<Found>>(emptyList())
    val results: StateFlow<List<Found>> = _results.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private var cb: ScanCallback? = null

    fun start(onlyCdtp: Boolean, timeoutMs: Long = 10_000): String? {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return "Bluetooth yok"
        if (!adapter.isEnabled) return "Bluetooth kapalı"
        val scanner = adapter.bluetoothLeScanner ?: return "Tarayıcı yok"
        stop()
        _results.value = emptyList()
        val c = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val cdtp = BleLink.isCdtp(result)
                if (onlyCdtp && !cdtp) return
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "(isimsiz)"
                val f = Found(name, result.device.address, result.rssi, cdtp)
                main.post {
                    val list = _results.value.filterNot { it.address == f.address } + f
                    _results.value = list.sortedWith(compareByDescending<Found> { it.isCdtp }.thenByDescending { it.rssi })
                }
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), c)
        } catch (e: SecurityException) {
            return "Bluetooth tarama izni yok"
        }
        cb = c
        _scanning.value = true
        main.postDelayed({ if (cb === c) stop() }, timeoutMs)
        return null
    }

    fun stop() {
        val c = cb ?: return
        cb = null
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c) }
        _scanning.value = false
    }
}
