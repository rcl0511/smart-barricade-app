@file:Suppress("MissingPermission")

package com.example.myapplication.data.ble
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.UUID

class RealBleRepository(
    private val context: Context
) : BleRepository {

    private val tag = "RealBleRepository"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter
    private val bleScanner
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var cmdCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private var isConnected = false
    private var metricsJob: Job? = null

    companion object {
        // ESP32와 약속한 UUID
        val SERVICE_UUID: UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ab")
        val CHAR_UUID_NOTIFY: UUID =
            UUID.fromString("abcd1234-1234-1234-1234-abcdef123456")
        val CHAR_UUID_WRITE: UUID =
            UUID.fromString("abcd0002-1234-5678-9999-abcdef123456")

        // CCCD (Notification 활성화용)
        private val CLIENT_CHAR_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // --------------------------------------------------------------------
    // 1. 스캔
    // --------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    override suspend fun scan(timeoutMs: Long): List<String> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: return@withContext emptyList()
        if (!adapter.isEnabled) return@withContext emptyList()

        val scanner = bleScanner ?: return@withContext emptyList()

        val foundNames = mutableSetOf<String>()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                if (!name.isNullOrBlank()) {
                    foundNames += name
                }
            }
        }

        try {
            scanner.startScan(filters, settings, callback)
            delay(timeoutMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        foundNames.toList()
    }

    // --------------------------------------------------------------------
    // 2. 연결 (serial 포함된 이름을 가진 기기)
    // --------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    override suspend fun connect(serial: String): Boolean = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: return@withContext false
        if (!adapter.isEnabled) return@withContext false

        if (isConnected && bluetoothGatt != null) return@withContext true

        val scanner = bleScanner ?: return@withContext false

        suspendCancellableCoroutine { cont ->
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: ""

                    if (serial.isBlank() || name.contains(serial, ignoreCase = true)) {
                        Log.d(tag, "found target: $name, stop scan & connect")
                        scanner.stopScan(this)
                        connectGattInternal(result.device, cont)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(tag, "scan failed: $errorCode")
                    if (cont.isActive) cont.resume(false) {}
                }
            }

            scanner.startScan(listOf(filter), settings, scanCallback)

            // 타임아웃
            scope.launch {
                delay(8000L)
                if (cont.isActive) {
                    Log.w(tag, "connect timeout")
                    runCatching { scanner.stopScan(scanCallback) }
                    cont.resume(false) {}
                }
            }

            cont.invokeOnCancellation {
                runCatching { scanner.stopScan(scanCallback) }
            }
        }
    }

    // 실제 GATT 연결
    @SuppressLint("MissingPermission")
    private fun connectGattInternal(
        device: BluetoothDevice,
        cont: CancellableContinuation<Boolean>
    ) {
        bluetoothGatt = device.connectGatt(
            appContext,
            false,
            object : BluetoothGattCallback() {

                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(tag, "GATT connection failed: $status")
                        isConnected = false
                        cmdCharacteristic = null
                        notifyCharacteristic = null
                        gatt.close()
                        if (cont.isActive) cont.resume(false) {}
                        return
                    }

                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(tag, "GATT connected, discoverServices()")
                            gatt.discoverServices()
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(tag, "GATT disconnected")
                            isConnected = false
                            cmdCharacteristic = null
                            notifyCharacteristic = null
                            gatt.close()
                        }
                    }
                }

                override fun onServicesDiscovered(
                    gatt: BluetoothGatt,
                    status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(tag, "service discovery failed: $status")
                        if (cont.isActive) cont.resume(false) {}
                        return
                    }

                    val service = gatt.getService(SERVICE_UUID)
                    if (service == null) {
                        Log.w(tag, "service not found: $SERVICE_UUID")
                        if (cont.isActive) cont.resume(false) {}
                        return
                    }

                    cmdCharacteristic = service.getCharacteristic(CHAR_UUID_WRITE)
                    notifyCharacteristic = service.getCharacteristic(CHAR_UUID_NOTIFY)

                    if (cmdCharacteristic == null) {
                        Log.w(tag, "write characteristic not found")
                    }
                    if (notifyCharacteristic == null) {
                        Log.w(tag, "notify characteristic not found")
                    }

                    // Notify 활성화
                    notifyCharacteristic?.let { ch ->
                        gatt.setCharacteristicNotification(ch, true)
                        val cccd = ch.getDescriptor(CLIENT_CHAR_CONFIG_UUID)
                        if (cccd != null) {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(cccd)
                        }
                    }

                    isConnected = true
                    if (cont.isActive) cont.resume(true) {}
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    if (characteristic.uuid == CHAR_UUID_NOTIFY) {
                        val raw = characteristic.value?.toString(Charsets.UTF_8) ?: return
                        Log.d(tag, "Notify from ESP32: $raw")

                        // TODO: 여기서 raw("FSR,LED,BUZ,MOT") 파싱해서
                        // ViewModel / UI로 전달하면 실시간 그래프/상태 갱신 가능
                    }
                }
            }
        )
    }

    // --------------------------------------------------------------------
    // 3. 연결 해제
    // --------------------------------------------------------------------
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT) {
            metricsJob?.cancel()
            metricsJob = null
            isConnected = false
            cmdCharacteristic = null
            notifyCharacteristic = null
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
    }

    // --------------------------------------------------------------------
    // 4. 네트워크 품질(더미)
    // --------------------------------------------------------------------
    override fun startMetrics(onUpdate: (rttMs: Int, lossPct: Int) -> Unit) {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            while (isActive) {
                val rtt = if (isConnected) 50 else -1
                val loss = if (isConnected) 0 else 100

                withContext(Dispatchers.Main) {
                    onUpdate(rtt, loss)
                }

                delay(1000L)
            }
        }
    }

    // --------------------------------------------------------------------
    // 5. Read (지금은 사용 안 함)
    // --------------------------------------------------------------------
    override suspend fun readCharacteristic(path: String): ByteArray {
        return ByteArray(0)
    }

    // --------------------------------------------------------------------
    // 6. Write: BarricadeDetailActivity → ESP32 명령
    // --------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    override suspend fun writeCharacteristic(
        path: String,
        payload: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        val gatt = bluetoothGatt ?: return@withContext false
        val target = when (path) {
            "cmd" -> cmdCharacteristic
            else  -> cmdCharacteristic
        } ?: return@withContext false

        target.value = payload

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(
                target,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            status == BluetoothStatusCodes.SUCCESS
        } else {
            // 구버전: writeType을 characteristic에 세팅 후 호출
            target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(target)
        }
    }
}
