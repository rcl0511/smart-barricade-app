package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.data.model.AlarmEvent
import com.example.myapplication.data.model.AlarmLevel
import com.example.myapplication.ui.main.AlarmAdapter
import com.example.myapplication.ui.main.MainViewModel
import com.example.myapplication.ui.util.PermissionHelper
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private enum class ConnectionMode { BLE, WIFI }
    private var currentMode: ConnectionMode = ConnectionMode.WIFI

    // ---------- View Refs ----------
    private lateinit var serialEdit: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnWifiAp: MaterialButton
    private lateinit var btnDisconnect: MaterialButton

    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var btnModeManual: MaterialButton
    private lateinit var btnModeAuto: MaterialButton

    private lateinit var btnGateOpen: MaterialButton
    private lateinit var btnGateStop: MaterialButton
    private lateinit var btnGateClose: MaterialButton

    private lateinit var btnLedUp: MaterialButton
    private lateinit var btnLedDown: MaterialButton
    private lateinit var btnLegUp: MaterialButton
    private lateinit var btnLegDown: MaterialButton

    private lateinit var btnResetOffset: MaterialButton

    private lateinit var chipConn: Chip
    private lateinit var chipRtt: Chip
    private lateinit var chipBattery: Chip
    private lateinit var chartPressure: LineChart

    private var txtDeviceInfo: TextView? = null
    private var txtSensorStatus: TextView? = null
    private var txtSensorLevel: TextView? = null
    private var txtFsrValue: TextView? = null
    private var txtLedState: TextView? = null
    private var txtBuzzerState: TextView? = null
    private var txtMotorState: TextView? = null
    private var txtLastUpdated: TextView? = null
    private var recyclerAlarms: RecyclerView? = null

    // ---------- BLE 관련 ----------
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD = 10_000L
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHAR_UUID_NOTIFY = UUID.fromString("abcd1234-1234-5678-9999-abcdef123456")
    private val CHAR_UUID_WRITE = UUID.fromString("abcd0002-1234-5678-9999-abcdef123456")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---------- 상태 관련 ----------
    private var pressureX = 0f
    private val CHART_INTERVAL_MS = 500L
    private var lastChartUpdateMs = 0L
    private val LOAD_THRESHOLD = 7000
    private val WIFI_STATUS_URL = "http://192.168.4.1/status"
    private var wifiStatusJob: Job? = null

    private val vm: MainViewModel by viewModels()
    private val alarmAdapter = AlarmAdapter(
        onAcknowledge = { },
        onDetails = { event ->
            val intent = Intent(this, BarricadeDetailActivity::class.java)
            intent.putExtra("device_id", event.deviceId)
            startActivity(intent)
        },
        onDismiss = { event -> vm.removeAlarm(event.id) }
    )

    private lateinit var perm: PermissionHelper

    // ---------- GATT Callback ----------
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            val currentGatt = gatt ?: return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { chipConn.text = "연결 오류: $status" }
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bluetoothGatt = currentGatt
                    runOnUiThread {
                        chipConn.text = "연결됨 (서비스 검색 중)"
                        enterBleMode()
                    }
                    // MTU 요청을 제거하고 바로 서비스 검색을 시작합니다.
                    if (checkPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        currentGatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    currentGatt.close()
                    bluetoothGatt = null
                    runOnUiThread {
                        chipConn.text = "연결 끊김"
                        if (currentMode == ConnectionMode.BLE) enterWifiMode()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                runOnUiThread { chipConn.text = "연결됨 (BLE)" }
                startRssiLoop()
                enableFsrNotify(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    txtDeviceInfo?.text = "신호 ${-rssi} dBm | ${formatTime(System.currentTimeMillis())}"
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val rawValue = characteristic.value ?: return
            val text = String(rawValue, Charsets.UTF_8).trim()
            val parts = text.split(",")
            // ESP32에서 정수형으로 보낼 때를 대비하여 파싱 로직은 그대로 유지 (floatOrNull은 정수도 처리 가능)
            if (parts.size >= 7 && parts[0] == "W") {
                val w1 = parts[1].toFloatOrNull() ?: 0f
                val w2 = parts[2].toFloatOrNull() ?: 0f
                val w3 = parts[3].toFloatOrNull() ?: 0f
                val ov = parts[4].toIntOrNull() == 1
                val auto = parts[5].toIntOrNull() == 1
                val ext = parts[6].toIntOrNull() != 100 // 100이 아닐 때 작동 중으로 표시
                runOnUiThread { handleSensorUpdateFromSource("BLE", w1, w2, w3, ov, auto, ext) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        bindViews()
        setupRecycler()
        setupSwipeToDelete()
        setupPermissions()
        wireUi()
        bindViewModel()
        setupChart()

        startWifiStatusLoop()
    }

    private fun bindViews() {
        serialEdit = findViewById(R.id.serialEdit)
        btnConnect = findViewById(R.id.btnConnect)
        btnWifiAp = findViewById(R.id.btnWifiAp)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        chipConn = findViewById(R.id.chipConn)
        chipRtt = findViewById(R.id.chipRtt)
        chipBattery = findViewById(R.id.chipBattery)

        toggleMode = findViewById(R.id.toggleMode)
        btnModeManual = findViewById(R.id.btnModeManual)
        btnModeAuto = findViewById(R.id.btnModeAuto)

        btnGateOpen = findViewById(R.id.btnGateOpen)
        btnGateStop = findViewById(R.id.btnGateStop)
        btnGateClose = findViewById(R.id.btnGateClose)

        btnLedUp = findViewById(R.id.btnLedUp)
        btnLedDown = findViewById(R.id.btnLedDown)
        btnLegUp = findViewById(R.id.btnLegUp)
        btnLegDown = findViewById(R.id.btnLegDown)

        btnResetOffset = findViewById(R.id.btnResetOffset)

        chartPressure = findViewById(R.id.chartPressure)
        txtDeviceInfo = findViewById(R.id.txtDeviceInfo)
        txtSensorStatus = findViewById(R.id.txtSensorStatus)
        txtSensorLevel = findViewById(R.id.txtSensorLevel)
        txtFsrValue = findViewById(R.id.txtFsrValue)
        txtLedState = findViewById(R.id.txtLedState)
        txtBuzzerState = findViewById(R.id.txtBuzzerState)
        txtMotorState = findViewById(R.id.txtMotorState)
        txtLastUpdated = findViewById(R.id.txtLastUpdated)
        recyclerAlarms = findViewById(R.id.recyclerAlarms)
    }

    private fun wireUi() {
        btnConnect.setOnClickListener {
            if(hasSerial()) {
                // 권한 체크 후 스캔 시작
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (checkPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                        checkPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        startBleScan()
                    } else {
                        perm.requestBlePermissions()
                    }
                } else {
                    perm.requestBlePermissions()
                }
            }
        }
        btnWifiAp.setOnClickListener { enterWifiMode(); toast("WiFi 모드로 전환") }
        btnDisconnect.setOnClickListener { disconnectBle(); enterWifiMode() }

        btnModeManual.setOnClickListener { sendBleCommand("MODE_MANUAL") }
        btnModeAuto.setOnClickListener { sendBleCommand("MODE_AUTO") }

        btnGateOpen.setOnClickListener { sendBleCommand("EXTEND") }
        btnGateStop.setOnClickListener { sendBleCommand("STOP") }
        btnGateClose.setOnClickListener { sendBleCommand("RETRACT") }

        btnLedUp.setOnClickListener { sendBleCommand("LED_UP") }
        btnLedDown.setOnClickListener { sendBleCommand("LED_DOWN") }
        btnLegUp.setOnClickListener { sendBleCommand("LEG_UP") }
        btnLegDown.setOnClickListener { sendBleCommand("LEG_DOWN") }

        btnResetOffset.setOnClickListener {
            sendBleCommand("TARE")
            toast("영점 조절 명령 전송")
        }

        serialEdit.addTextChangedListener { btnConnect.isEnabled = hasSerial() }
    }

    @SuppressLint("MissingPermission")
    private fun sendBleCommand(cmd: String) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH

        if (!checkPermission(permission)) {
            toast("블루투스 권한이 필요합니다.")
            return
        }
        val gatt = bluetoothGatt
        if (gatt == null) {
            toast("기기가 연결되어 있지 않습니다.")
            return
        }

        try {
            val service = gatt.getService(SERVICE_UUID)
            val char = service?.getCharacteristic(CHAR_UUID_WRITE)
            if (char != null) {
                char.value = cmd.toByteArray()
                val success = gatt.writeCharacteristic(char)
                if (success) Log.d("BLE", "명령 전송 성공: $cmd")
                else toast("명령 전송 실패")
            } else {
                toast("통신 특성을 찾을 수 없습니다.")
            }
        } catch (e: Exception) {
            Log.e("BLE", "Error: ${e.message}")
        }
    }

    private fun startRssiLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && bluetoothGatt != null) {
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
                if (checkPermission(permission)) {
                    try { bluetoothGatt?.readRemoteRssi() } catch(e: Exception) {}
                }
                delay(2000)
            }
        }
    }

    private fun startWifiStatusLoop() {
        wifiStatusJob?.cancel()
        wifiStatusJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val conn = (URL(WIFI_STATUS_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1000; readTimeout = 1000
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        withContext(Dispatchers.Main) {
                            handleSensorUpdateFromSource("WiFi",
                                json.optDouble("W1", 0.0).toFloat(),
                                json.optDouble("W2", 0.0).toFloat(),
                                json.optDouble("W3", 0.0).toFloat(),
                                json.optInt("overloaded", 0) == 1,
                                json.optInt("autoMode", 1) == 1,
                                json.optInt("actuatorState", 0) == 1
                            )
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) { Log.e("WIFI", "Error: ${e.message}") }
                delay(1000)
            }
        }
    }

    private fun handleSensorUpdateFromSource(src: String, w1: Float, w2: Float, w3: Float, ov: Boolean, auto: Boolean, ext: Boolean) {
        val now = System.currentTimeMillis()
        val total = w1 + w2 + w3
        val avg = total / 3f

        txtSensorStatus?.text = "하중($src 평균): %.0f g %s".format(avg, if(ov) "(과부하)" else "(정상)")
        txtFsrValue?.text = "평균하중 값: %.0f g (W1:%.0f, W2:%.0f, W3:%.0f)".format(avg, w1, w2, w3)
        txtLedState?.text = "모드: ${if(auto) "AUTO" else "MANUAL"}"
        txtBuzzerState?.text = "과부하 상태: ${if(ov) "예" else "아니오"}"
        txtMotorState?.text = "게이트: ${if(ext) "작동 중" else "대기 중"}"
        txtLastUpdated?.text = "마지막 수신: ${formatTime(now)}"

        if (ov) {
            txtSensorLevel?.text = "위험"
            txtSensorLevel?.setBackgroundColor(Color.RED)
        } else {
            txtSensorLevel?.text = "정상"
            txtSensorLevel?.setBackgroundColor(Color.parseColor("#4CAF50"))
        }

        chipRtt.text = "$src 업데이트: ${formatTime(now)}"
        if (auto) btnModeAuto.isChecked = true else btnModeManual.isChecked = true

        appendPressureValue(w1, w2, w3)

        if (ov) {
            pushPresetAlarm(AlarmLevel.ERROR, "과부하 위험 감지", "합계 하중 ${total.toInt()}g (임계값 초과)", serialOrDefault())
        }
    }

    private fun setupChart() {
        chartPressure.apply {
            val limitLine = LimitLine(LOAD_THRESHOLD.toFloat(), "임계값").apply {
                lineColor = Color.RED; lineWidth = 2f; textColor = Color.RED
            }
            data = LineData(
                LineDataSet(mutableListOf(), "W1").apply { color = Color.BLUE; setDrawCircles(false); setDrawValues(false) },
                LineDataSet(mutableListOf(), "W2").apply { color = Color.GREEN; setDrawCircles(false); setDrawValues(false) },
                LineDataSet(mutableListOf(), "W3").apply { color = Color.RED; setDrawCircles(false); setDrawValues(false) }
            )
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = LOAD_THRESHOLD * 1.5f
                addLimitLine(limitLine)
            }
            axisRight.isEnabled = false
            description.isEnabled = false
            legend.isEnabled = true
            invalidate()
        }
    }

    private fun appendPressureValue(w1: Float, w2: Float, w3: Float) {
        val now = System.currentTimeMillis()
        if (now - lastChartUpdateMs < CHART_INTERVAL_MS) return
        lastChartUpdateMs = now
        pressureX += 1f
        val data = chartPressure.data ?: return
        for (i in 0..2) {
            val set = data.getDataSetByIndex(i) as? LineDataSet ?: continue
            val valToPush = when(i) { 0 -> w1; 1 -> w2; else -> w3 }
            data.addEntry(Entry(pressureX, valToPush), i)
            if (set.entryCount > 50) set.removeFirst()
        }
        data.notifyDataChanged()
        chartPressure.notifyDataSetChanged()
        chartPressure.moveViewToX(pressureX)
    }

    private fun setupRecycler() {
        recyclerAlarms?.layoutManager = LinearLayoutManager(this)
        recyclerAlarms?.adapter = alarmAdapter
    }

    private fun setupSwipeToDelete() {
        recyclerAlarms?.let { rv ->
            val swipe = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
                override fun onSwiped(vh: RecyclerView.ViewHolder, d: Int) {
                    val pos = vh.bindingAdapterPosition
                    val item = alarmAdapter.currentList.getOrNull(pos) ?: return
                    val removed = vm.removeAlarm(item.id)
                    Snackbar.make(rv, "알람 삭제됨", Snackbar.LENGTH_LONG)
                        .setAction("복구") { removed?.let { vm.restoreAlarm(it) } }.show()
                }
            }
            ItemTouchHelper(swipe).attachToRecyclerView(rv)
        }
    }

    private fun setupPermissions() {
        perm = PermissionHelper(this) { if(it) startBleScan() }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

        if (!checkPermission(scanPermission)) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        discoveredDevices.clear()
        isScanning = true
        scanner.startScan(leScanCallback)
        chipConn.text = "스캔 중..."
        scanHandler.postDelayed({ stopBleScan(); showDeviceSelectDialog() }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

        if (checkPermission(scanPermission)) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(ct: Int, res: ScanResult?) {
            res?.device?.let { device ->
                val name = device.name
                if (!name.isNullOrBlank() && !discoveredDevices.contains(device)) {
                    discoveredDevices.add(device)
                    Log.d("BLE", "장치 발견: $name (${device.address})")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectDialog() {
        if (discoveredDevices.isEmpty()) {
            chipConn.text = "기기 없음"
            toast("검색된 기기가 없습니다.")
            return
        }

        val names = discoveredDevices.map { "${it.name}\n${it.address}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("연결할 기기 선택")
            .setItems(names) { _, i -> connectToDevice(discoveredDevices[i]) }
            .setNegativeButton("취소", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(dev: BluetoothDevice) {
        val connectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH

        if (checkPermission(connectPermission)) {
            chipConn.text = "연결 시도 중..."
            bluetoothGatt = dev.connectGatt(this, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectBle() {
        val connectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH

        if (checkPermission(connectPermission)) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
    }

    private fun enterBleMode() { currentMode = ConnectionMode.BLE; wifiStatusJob?.cancel() }
    private fun enterWifiMode() { currentMode = ConnectionMode.WIFI; startWifiStatusLoop() }

    private fun bindViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.alarms.collectLatest { alarmAdapter.submitList(it) }
            }
        }
    }

    private fun hasSerial() = serialEdit.text.isNotBlank()
    private fun serialOrDefault() = serialEdit.text.toString().ifBlank { "A-10" }
    private fun formatTime(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(ms))
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun checkPermission(p: String) = ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun enableFsrNotify(gatt: BluetoothGatt) {
        val connectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH

        if (!checkPermission(connectPermission)) return

        val service = gatt.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(CHAR_UUID_NOTIFY) ?: return
        gatt.setCharacteristicNotification(char, true)
        val desc = char.getDescriptor(CCCD_UUID) ?: return
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(desc)
    }

    private fun pushPresetAlarm(l: AlarmLevel, t: String, d: String, dev: String) {
        vm.pushAlarm(AlarmEvent(UUID.randomUUID().toString(), l, System.currentTimeMillis(), dev, t, d))
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectBle()
        wifiStatusJob?.cancel()
    }
}