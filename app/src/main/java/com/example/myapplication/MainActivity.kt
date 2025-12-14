package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
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
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // ---------- 상태 ----------
    private var detailsExpanded = false

    // 🔹 연결 모드 (BLE / WiFi)
    private enum class ConnectionMode { BLE, WIFI }
    private var currentMode: ConnectionMode = ConnectionMode.WIFI

    // ---------- View refs ----------
    private lateinit var serialEdit: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton

    // 🔹 모드 / GATE 제어 (스위치 + 버튼)
    private lateinit var switchAuto: MaterialSwitch
    private lateinit var btnGateOpen: MaterialButton
    private lateinit var btnGateClose: MaterialButton

    private var btnPresetLoad: MaterialButton? = null
    private var btnPresetDensity: MaterialButton? = null
    private var btnPresetBattery: MaterialButton? = null

    private lateinit var chipConn: TextView      // 연결 상태
    private lateinit var chipRtt: TextView       // 마지막 업데이트(BLE/WiFi)

    private lateinit var chipBattery: Chip

    private var txtDeviceTitle: TextView? = null
    private var txtDeviceInfo: TextView? = null
    private var txtSensorStatus: TextView? = null   // 상단 카드 센서 텍스트

    private var cardDevice: MaterialCardView? = null
    private var scroll: ViewGroup? = null

    private var recyclerAlarms: RecyclerView? = null

    // ▼ 실시간 상태 대시보드(View)
    private var txtSensorTitle: TextView? = null
    private var txtSensorLevel: TextView? = null
    private var txtFsrValue: TextView? = null
    private var txtLedState: TextView? = null
    private var txtBuzzerState: TextView? = null
    private var txtMotorState: TextView? = null
    private var txtLastUpdated: TextView? = null

    // ---------- BLE 관련 ----------
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD = 10_000L  // 10초 스캔

    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    // ESP32-S3 서비스 / 캐릭터리스틱 UUID (아두이노 코드와 동일)
    private val SERVICE_UUID = UUID.fromString(
        "12345678-1234-1234-1234-1234567890ab"
    )
    private val CHAR_UUID_NOTIFY = UUID.fromString(
        "abcd1234-1234-5678-9999-abcdef123456" // ESP32 → Android (Notify)
    )
    private val CHAR_UUID_WRITE = UUID.fromString(
        "abcd0002-1234-5678-9999-abcdef123456" // Android → ESP32 (Write)
    )
    private val CCCD_UUID = UUID.fromString(
        "00002902-0000-1000-8000-00805f9b34fb"
    )

    // ---------- BLE 안정화용 상태 ----------
    private var lastConnectedDevice: BluetoothDevice? = null
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 3
    private val RECONNECT_DELAY_MS = 3_000L

    // ---------- Chart ----------
    private lateinit var chartPressure: LineChart
    private var pressureX = 0f

    // 🔹 그래프 갱신 간격 빠르게 (0.5초)
    private val CHART_INTERVAL_MS = 500L
    private var lastChartUpdateMs = 0L
    private var lastBleUpdateMs = 0L

    // 로드셀 임계값 (ESP32와 맞추기: 10,000g)
    private val LOAD_THRESHOLD = 10_000f

    // ---------- WiFi / HTTP 상태 폴링 ----------
    private val WIFI_AP_IP = "192.168.4.1"
    private val WIFI_STATUS_URL = "http://$WIFI_AP_IP/status"
    private var wifiStatusJob: Job? = null
    private val ENABLE_WIFI_STATUS_POLL = true   // 필요 없으면 false로 꺼도 됨

    // ---------- 알람 / ViewModel ----------
    private val vm: MainViewModel by viewModels()
    private val alarmAdapter = AlarmAdapter(
        onAcknowledge = { /* 필요시 서버 업로드 등 */ },
        onDetails = { event ->
            startActivity(
                Intent(this, BarricadeDetailActivity::class.java)
                    .putExtra("device_id", event.deviceId)
            )
        },
        onDismiss = { event ->
            val removed = vm.removeAlarm(event.id)
            recyclerAlarms?.let { rv ->
                Snackbar.make(rv, "알람 제거됨: ${event.title}", Snackbar.LENGTH_LONG)
                    .setAction("되돌리기") { removed?.let { vm.restoreAlarm(it) } }
                    .show()
            }
        }
    )

    // ---------- 권한 ----------
    private lateinit var perm: PermissionHelper

    // ---------- BLE 스캔 콜백 ----------
    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val device: BluetoothDevice = result?.device ?: return
            val name = device.name ?: ""

            Log.d("BLE_SCAN", "발견: $name / ${device.address}")

            if (discoveredDevices.any { it.address == device.address }) return
            discoveredDevices.add(device)
        }
    }

    // ---------- GATT 콜백 ----------
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt?,
            status: Int,
            newState: Int
        ) {
            super.onConnectionStateChange(gatt, status, newState)

            Log.d("BLE_GATT", "onConnectionStateChange status=$status, newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w("BLE_GATT", "GATT 오류 발생: status=$status")
                runOnUiThread {
                    toast("BLE 오류 발생 (status=$status)")
                    chipConn.text = "오류: $status"
                    chipConn.setTextColor(Color.RED)
                }
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BLE_GATT", "연결됨: ${gatt?.device?.address}")
                    bluetoothGatt = gatt
                    reconnectAttempts = 0

                    runOnUiThread {
                        chipConn.text = "연결됨 (BLE)"
                        chipConn.setTextColor(Color.BLUE)
                        toast("BLE 기기 연결 성공")
                        detailsExpanded = true
                        applyExpandState(animated = true)

                        // 🔹 BLE 모드 진입 → WiFi 폴링 중단
                        enterBleMode()
                    }
                    gatt?.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLE_GATT", "연결 끊김, status=$status")
                    bluetoothGatt?.close()
                    bluetoothGatt = null

                    runOnUiThread {
                        chipConn.text = "연결 끊김"
                        chipConn.setTextColor(Color.GRAY)
                        toast("BLE 연결 끊김")
                        detailsExpanded = false
                        applyExpandState(animated = true)

                        // 🔹 BLE 끊기면 WiFi 모드로 복귀
                        if (ENABLE_WIFI_STATUS_POLL) {
                            enterWifiMode()
                        }
                    }

                    val device = lastConnectedDevice
                    if (device != null &&
                        reconnectAttempts < MAX_RECONNECT_ATTEMPTS &&
                        hasBlePermissions()
                    ) {
                        reconnectAttempts++
                        Log.d("BLE_GATT", "재연결 시도 #$reconnectAttempts")

                        runOnUiThread {
                            chipConn.text =
                                "재연결 시도 중... ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)"
                            chipConn.setTextColor(Color.DKGRAY)
                        }

                        Handler(Looper.getMainLooper()).postDelayed({
                            connectToDevice(device)
                        }, RECONNECT_DELAY_MS)
                    } else {
                        Log.d("BLE_GATT", "재연결 포기")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE_GATT", "서비스 발견됨 → RSSI 루프 + Notify 설정")
                gatt?.readRemoteRssi()
                startRssiLoop()
                enableFsrNotify(gatt)
            } else {
                Log.w("BLE_GATT", "서비스 발견 실패: status=$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onReadRemoteRssi(
            gatt: BluetoothGatt?,
            rssi: Int,
            status: Int
        ) {
            super.onReadRemoteRssi(gatt, rssi, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE_RSSI", "RSSI 콜백: $rssi dBm")
                runOnUiThread {
                    txtDeviceInfo?.text =
                        "신호 ${-rssi} dBm | 마지막 통신: ${formatTime(System.currentTimeMillis())} | 상태: 정상"
                }
            } else {
                Log.w("BLE_RSSI", "RSSI 읽기 실패: status=$status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.d("BLE_WRITE", "onCharacteristicWrite status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { toast("BLE 명령 전송 실패 (status=$status)") }
            }
        }

        // ▼ ESP32에서 넘어온 센서 문자열 처리
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (characteristic == null) return
            if (characteristic.uuid != CHAR_UUID_NOTIFY) return

            val raw = characteristic.value ?: return
            val text = String(raw, Charsets.UTF_8).trim()
            Log.d("BLE_NOTIFY", "수신 문자열: $text")

            // ESP32 포맷: "W,W1,W2,W3,overloaded,autoMode,actuatorState"
            val parts = text.split(",")
            if (parts.size < 7) {
                Log.w("BLE_NOTIFY", "포맷 이상: $text (parts.size=${parts.size})")
                runOnUiThread { chipRtt.text = "수신 포맷 오류" }
                return
            }

            if (parts[0] != "W") {
                Log.w("BLE_NOTIFY", "헤더 이상: ${parts[0]}")
                runOnUiThread { chipRtt.text = "수신 헤더 오류" }
                return
            }

            val w1 = parts.getOrNull(1)?.toFloatOrNull()
            val w2 = parts.getOrNull(2)?.toFloatOrNull()
            val w3 = parts.getOrNull(3)?.toFloatOrNull()
            val overloaded = (parts.getOrNull(4)?.toIntOrNull() == 1)
            val autoMode = (parts.getOrNull(5)?.toIntOrNull() == 1)
            val actuatorExtended = (parts.getOrNull(6)?.toIntOrNull() == 1)

            if (w1 == null || w2 == null || w3 == null) {
                Log.w("BLE_NOTIFY", "weight 파싱 실패: $text")
                runOnUiThread { chipRtt.text = "데이터 파싱 실패" }
                return
            }

            runOnUiThread {
                handleSensorUpdateFromSource(
                    source = "BLE",
                    w1 = w1,
                    w2 = w2,
                    w3 = w3,
                    overloaded = overloaded,
                    autoMode = autoMode,
                    actuatorExtended = actuatorExtended
                )
            }
        }
    }

    // ---------- Chart 세팅 (W1/W2/W3 3개 라인) ----------
    private fun setupChart() {
        val setW1 = LineDataSet(mutableListOf<Entry>(), "W1(g)").apply {
            lineWidth = 2f
            color = Color.parseColor("#1E88E5")
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
            setDrawFilled(true)
            fillAlpha = 60
            fillColor = Color.parseColor("#1E88E5")
        }

        val setW2 = LineDataSet(mutableListOf<Entry>(), "W2(g)").apply {
            lineWidth = 2f
            color = Color.parseColor("#43A047")
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
            setDrawFilled(true)
            fillAlpha = 40
            fillColor = Color.parseColor("#43A047")
        }

        val setW3 = LineDataSet(mutableListOf<Entry>(), "W3(g)").apply {
            lineWidth = 2f
            color = Color.parseColor("#F4511E")
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
            setDrawFilled(true)
            fillAlpha = 40
            fillColor = Color.parseColor("#F4511E")
        }

        chartPressure.apply {
            resetViewPortOffsets()
            data = LineData(setW1, setW2, setW3)

            description.isEnabled = false
            legend.isEnabled = true         // 어떤 색이 W1/W2/W3인지 보이게

            setDrawGridBackground(false)
            setTouchEnabled(false)
            setScaleEnabled(false)
            setPinchZoom(false)

            axisRight.isEnabled = false

            setExtraLeftOffset(12f)
            setMinOffset(12f)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                setDrawAxisLine(false)
                setDrawLabels(false)
            }

            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = LOAD_THRESHOLD * 1.5f
                setDrawAxisLine(false)
                setDrawGridLines(true)
                enableGridDashedLine(10f, 10f, 0f)
                textSize = 10f
            }

            val limit = LimitLine(LOAD_THRESHOLD, "임계값").apply {
                lineWidth = 1.5f
                lineColor = Color.RED
                textColor = Color.RED
                textSize = 10f
                enableDashedLine(12f, 10f, 0f)
            }
            axisLeft.removeAllLimitLines()
            axisLeft.addLimitLine(limit)

            animateX(300)
            invalidate()
        }
    }

    // ---------- 공통 센서 UI 업데이트 (BLE / WiFi 공용) ----------
    private fun handleSensorUpdateFromSource(
        source: String,
        w1: Float,
        w2: Float,
        w3: Float,
        overloaded: Boolean,
        autoMode: Boolean,
        actuatorExtended: Boolean
    ) {
        val now = System.currentTimeMillis()
        lastBleUpdateMs = now  // WiFi도 같이 씀

        // 🌟 하중 합계와 평균 계산 (추가된 부분)
        val totalWeight = w1 + w2 + w3
        val averageWeight = totalWeight / 3f

        val weightText = "%.2f".format(averageWeight) // 🌟 평균 하중을 출력 텍스트로 사용

        // 상단 카드 텍스트 (🌟 평균 기준 표시)
        txtSensorStatus?.text =
            "하중($source 평균): ${weightText} g" + if (overloaded) " (과부하)" else " (정상)"

        // 실시간 대시보드 (🌟 합계를 평균으로 변경)
        txtFsrValue?.text =
            "평균: ${weightText} g\nW1=%.1f, W2=%.1f, W3=%.1f".format(w1, w2, w3)
        txtLedState?.text    = "모드: ${if (autoMode) "AUTO" else "MANUAL"}"
        txtBuzzerState?.text = "과부하 플래그: ${if (overloaded) "예" else "아니오"}"
        txtMotorState?.text  = "게이트: ${if (actuatorExtended) "게이트 오픈" else "게이트 클로즈"}"
        txtLastUpdated?.text = "마지막 수신: ${formatTime(now)}"

        // BLE 상태에 맞춰 스위치 동기화 (WiFi도 autoMode 그대로 반영)
        switchAuto.isChecked = autoMode

        val (label, color) = when {
            overloaded -> "위험" to Color.parseColor("#D32F2F")
            totalWeight >= LOAD_THRESHOLD * 0.7f -> "주의" to Color.parseColor("#F9A825")
            else -> "대기" to Color.parseColor("#388E3C")
        }
        txtSensorLevel?.text = label
        txtSensorLevel?.setBackgroundColor(color)

        chipRtt.text = "$source 업데이트: ${formatTime(now)}"

        // 그래프: W1/W2/W3 3개 라인
        appendPressureValue(w1, w2, w3)

        // 과부하 알람 (아무 소스나 기준)
        if (overloaded) {
            pushPresetAlarm(
                level = AlarmLevel.WARN,
                title = "하중 임계 초과",
                // 🌟 상세 알람 메시지 수정: 출력은 평균이지만 임계값은 합계 기준임을 명시
                detail = "현재 평균 하중 ${weightText} g / (합계 임계값 ${LOAD_THRESHOLD.toInt()} g)",
                device = serialOrDefault("A-10")
            )
        }
    }

    // ---------- WiFi /status 폴링 ----------
    private fun startWifiStatusLoop() {
        if (!ENABLE_WIFI_STATUS_POLL) return

        wifiStatusJob?.cancel()
        wifiStatusJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val url = URL(WIFI_STATUS_URL)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1000
                        readTimeout = 1000
                        requestMethod = "GET"
                    }
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        Log.d("WIFI_STATUS", "응답: $body")

                        val json = JSONObject(body)
                        val w1 = json.optDouble("W1", 0.0).toFloat()
                        val w2 = json.optDouble("W2", 0.0).toFloat()
                        val w3 = json.optDouble("W3", 0.0).toFloat()
                        val over1 = json.optInt("over1", 0) == 1
                        val over2 = json.optInt("over2", 0) == 1
                        val over3 = json.optInt("over3", 0) == 1
                        val overloaded = json.optInt("overloaded", 0) == 1
                        val autoMode = json.optInt("autoMode", 1) == 1
                        val actuatorExtended = json.optInt("actuatorState", 0) == 1

                        withContext(Dispatchers.Main) {
                            handleSensorUpdateFromSource(
                                source = "WiFi",
                                w1 = w1,
                                w2 = w2,
                                w3 = w3,
                                overloaded = overloaded,
                                autoMode = autoMode,
                                actuatorExtended = actuatorExtended
                            )
                        }
                    } else {
                        Log.w("WIFI_STATUS", "HTTP $code")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.w("WIFI_STATUS", "요청 실패: ${e.message}")
                }

                delay(1000) // 1초마다 폴링
            }
        }
    }

    private fun stopWifiStatusLoop() {
        wifiStatusJob?.cancel()
        wifiStatusJob = null
    }

    // 🔹 모드 전환 헬퍼: BLE 모드
    private fun enterBleMode() {
        if (currentMode == ConnectionMode.BLE) return
        currentMode = ConnectionMode.BLE

        // BLE 모드에서는 WiFi 폴링 중단 (펌웨어도 AP 끔)
        stopWifiStatusLoop()
        chipRtt.text = "BLE 모드 사용 중"
    }

    // 🔹 모드 전환 헬퍼: WiFi 모드
    private fun enterWifiMode() {
        if (currentMode == ConnectionMode.WIFI) return
        currentMode = ConnectionMode.WIFI

        if (ENABLE_WIFI_STATUS_POLL) {
            startWifiStatusLoop()
            chipRtt.text = "WiFi 모드 (/status 폴링)"
        }
    }

    // ---------- 그래프에 점 추가 (W1/W2/W3) ----------
    private fun appendPressureValue(w1: Float, w2: Float, w3: Float) {
        val now = System.currentTimeMillis()
        if (now - lastChartUpdateMs < CHART_INTERVAL_MS) return
        lastChartUpdateMs = now

        pressureX += 1f

        val data = chartPressure.data ?: LineData().also {
            chartPressure.data = it
        }

        fun ensureDataSet(index: Int, label: String, colorStr: String): LineDataSet {
            val existing = data.getDataSetByIndex(index) as? LineDataSet
            if (existing != null) return existing

            val set = LineDataSet(mutableListOf(), label).apply {
                lineWidth = 2f
                color = Color.parseColor(colorStr)
                setDrawCircles(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
                setDrawFilled(true)
                fillAlpha = 40
                fillColor = Color.parseColor(colorStr)
            }
            data.addDataSet(set)
            return set
        }

        val setW1 = ensureDataSet(0, "W1(g)", "#1E88E5")
        val setW2 = ensureDataSet(1, "W2(g)", "#43A047")
        val setW3 = ensureDataSet(2, "W3(g)", "#F4511E")

        setW1.addEntry(Entry(pressureX, w1))
        setW2.addEntry(Entry(pressureX, w2))
        setW3.addEntry(Entry(pressureX, w3))

        listOf(setW1, setW2, setW3).forEach { set ->
            if (set.entryCount > 60) {
                set.removeFirst()
            }
        }

        data.notifyDataChanged()
        chartPressure.notifyDataSetChanged()
        chartPressure.moveViewToX(pressureX)
        chartPressure.invalidate()
    }

    // ---------- 생명주기 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PermissionHelper.DEV_BYPASS = false

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        bindViews()
        setupRecycler()
        setupSwipeToDelete()
        setupPermissions()
        wireUi()
        bindViewModel()
        setupChart()
        applyExpandState(animated = false)

        // 기본: WiFi 모드로 시작 (AP에 붙어 있으면 /status 폴링)
        currentMode = ConnectionMode.WIFI
        if (ENABLE_WIFI_STATUS_POLL) {
            startWifiStatusLoop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectBle()
        stopWifiStatusLoop()
    }

    // -------------------- View 바인딩 --------------------
    private fun bindViews() {
        serialEdit    = findViewById(R.id.serialEdit)
        btnConnect    = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        switchAuto    = findViewById(R.id.switchAuto)
        btnGateOpen   = findViewById(R.id.btnGateOpen)
        btnGateClose  = findViewById(R.id.btnGateClose)

        btnPresetLoad    = findViewById(R.id.btnPresetLoad)
        btnPresetDensity = findViewById(R.id.btnPresetDensity)
        btnPresetBattery = findViewById(R.id.btnPresetBattery)

        chipConn    = findViewById(R.id.chipConn)
        chipRtt     = findViewById(R.id.chipRtt)

        chipBattery = findViewById(R.id.chipBattery)

        txtDeviceInfo   = findViewById(R.id.txtDeviceInfo)
        txtSensorStatus = findViewById(R.id.txtSensorStatus)

        cardDevice = findViewById(R.id.cardDevice)
        scroll     = findViewById(R.id.scroll)

        chartPressure = findViewById(R.id.chartPressure)

        recyclerAlarms = findViewById(R.id.recyclerAlarms)

        txtSensorTitle  = findViewById(R.id.txtSensorTitle)
        txtSensorLevel  = findViewById(R.id.txtSensorLevel)
        txtFsrValue     = findViewById(R.id.txtFsrValue)
        txtLedState     = findViewById(R.id.txtLedState)
        txtBuzzerState  = findViewById(R.id.txtBuzzerState)
        txtMotorState   = findViewById(R.id.txtMotorState)
        txtLastUpdated  = findViewById(R.id.txtLastUpdated)
    }

    private fun setupRecycler() {
        recyclerAlarms?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = alarmAdapter
            setHasFixedSize(false)
        }
    }

    private fun setupSwipeToDelete() {
        recyclerAlarms?.let { rv ->
            val swipe = object : ItemTouchHelper.SimpleCallback(
                0,
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ) = false

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val pos = viewHolder.bindingAdapterPosition
                    val item = alarmAdapter.currentList.getOrNull(pos) ?: return
                    val removed = vm.removeAlarm(item.id)

                    Snackbar.make(rv, "알람 제거됨: ${item.title}", Snackbar.LENGTH_LONG)
                        .setAction("되돌리기") { removed?.let { vm.restoreAlarm(it) } }
                        .show()
                }
            }
            ItemTouchHelper(swipe).attachToRecyclerView(rv)
        }
    }

    private fun setupPermissions() {
        perm = PermissionHelper(this) { granted ->
            if (granted) proceedConnect()
            else Toast.makeText(this, "권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------- UI 이벤트 --------------------
    private fun wireUi() {
        serialEdit.addTextChangedListener {
            btnConnect.isEnabled = hasSerial()
        }
        btnConnect.isEnabled = hasSerial()

        btnConnect.setOnClickListener {
            if (!hasSerial()) {
                toast(getString(R.string.toast_need_serial))
                return@setOnClickListener
            }
            chipConn.text = "🔗 연결 시도 중..."
            chipConn.setTextColor(Color.DKGRAY)
            perm.requestBlePermissions()
        }

        btnDisconnect.setOnClickListener {
            vm.disconnect()
            disconnectBle()
            detailsExpanded = false
            applyExpandState(animated = true)
            toast(getString(R.string.toast_disconnected))

            // 수동으로 BLE 끊었을 때도 WiFi 모드로 복귀
            if (ENABLE_WIFI_STATUS_POLL) {
                enterWifiMode()
            }
        }

        cardDevice?.setOnClickListener {
            val serial = serialOrDefault()
            startActivity(
                Intent(this, BarricadeDetailActivity::class.java)
                    .putExtra("device_id", serial)
            )
        }

        cardDevice?.setOnLongClickListener {
            val serial = serialOrDefault("A-12")
            startActivity(
                Intent(this, BarricadeDetailActivity::class.java)
                    .putExtra("serial", serial)
            )
            true
        }

        // 모드 스위치 → ESP32로 AUTO / MANUAL 전송
        switchAuto.setOnCheckedChangeListener { _, isChecked ->
            if (!hasBlePermissions()) {
                perm.requestBlePermissions()
                switchAuto.isChecked = !isChecked
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                sendBleCommand("MODE_AUTO")
                toast("AUTO 모드 전환 요청")
            } else {
                sendBleCommand("MODE_MANUAL")
                toast("MANUAL 모드 전환 요청")
            }
        }

        // 게이트 제어 버튼
        btnGateOpen.setOnClickListener {
            if (hasBlePermissions()) {
                sendBleCommand("EXTEND")
            } else {
                perm.requestBlePermissions()
            }
        }

        btnGateClose.setOnClickListener {
            if (hasBlePermissions()) {
                sendBleCommand("RETRACT")
            } else {
                perm.requestBlePermissions()
            }
        }

        // 프리셋 버튼 (테스트 알람용)
        btnPresetLoad?.setOnClickListener {
            pushPresetAlarm(
                level = AlarmLevel.WARN,
                title = "하중 임계 근접",
                detail = "현재 하중 150kg / 임계값 200kg / LED 경고 점등",
                device = serialOrDefault("A-10")
            )
        }
        btnPresetDensity?.setOnClickListener {
            pushPresetAlarm(
                level = AlarmLevel.WARN,
                title = "밀집도 급상승",
                detail = "2m 구간 인원 밀집도 95% / 접근 제한 필요",
                device = serialOrDefault("B-03")
            )
        }
        btnPresetBattery?.setOnClickListener {
            pushPresetAlarm(
                level = AlarmLevel.ERROR,
                title = "배터리 부족",
                detail = "현재 전압 3.1V / 충전 필요 / 절전 모드 전환 예정",
                device = serialOrDefault("C-02")
            )
        }
    }

    // -------------------- ViewModel 바인딩 --------------------
    private fun bindViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.conn.collectLatest { s ->
                        Log.d("VM_CONN", "collect: $s")
                        btnConnect.isEnabled = !s.connected && hasSerial()
                        btnDisconnect.isEnabled = s.connected
                    }
                }

                launch {
                    vm.device.collectLatest { d ->
                        txtDeviceTitle?.text = d.title
                    }
                }

                launch {
                    vm.alarms.collectLatest { list ->
                        recyclerAlarms?.let { alarmAdapter.submitList(list) }
                    }
                }
            }
        }
    }

    // -------------------- 배터리 표시 --------------------
    fun updateBattery(level: Int) {
        chipBattery.text = "$level%"
        val color = when {
            level >= 75 -> Color.parseColor("#4CAF50")
            level >= 40 -> Color.parseColor("#FFC107")
            else        -> Color.parseColor("#F44336")
        }
        chipBattery.chipIconTint = ColorStateList.valueOf(color)
        chipBattery.setTextColor(color)
    }

    // -------------------- BLE 권한 체크 --------------------
    private fun hasBlePermissions(): Boolean {
        val scanGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

        val connectGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        return scanGranted && connectGranted
    }

    // -------------------- BLE 동작 --------------------
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!hasBlePermissions()) {
            toast("BLE 권한이 아직 허용되지 않았어요.")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            toast("블루투스를 켜주세요.")
            return
        }

        if (isScanning) return

        discoveredDevices.clear()

        chipConn.text = "스캔 중..."
        chipConn.setTextColor(Color.DKGRAY)

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            toast("BLE 스캐너를 사용할 수 없습니다.")
            return
        }

        isScanning = true
        scanner.startScan(leScanCallback)

        scanHandler.postDelayed({
            if (isScanning) {
                stopBleScan()
                showDeviceSelectDialog()
            }
        }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        if (!isScanning) return

        scanner.stopScan(leScanCallback)
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectDialog() {
        if (discoveredDevices.isEmpty()) {
            chipConn.text = "기기 없음"
            chipConn.setTextColor(Color.RED)
            toast("주변에서 연결 가능한 기기를 찾지 못했어요.")
            return
        }

        val items = discoveredDevices.map { device ->
            val name = device.name ?: "(이름 없음)"
            "$name\n${device.address}"
        }.toTypedArray()

        chipConn.text = "기기 선택 대기 중"
        chipConn.setTextColor(Color.DKGRAY)

        AlertDialog.Builder(this)
            .setTitle("연결할 BLE 기기를 선택하세요")
            .setItems(items) { _, which ->
                val device = discoveredDevices[which]
                connectToDevice(device)
            }
            .setNegativeButton("취소") { _, _ ->
                chipConn.text = "연결 안 됨"
                chipConn.setTextColor(Color.GRAY)
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        chipConn.text = "연결 중... (${device.name ?: "알 수 없음"})"
        chipConn.setTextColor(Color.DKGRAY)

        lastConnectedDevice = device
        reconnectAttempts = 0

        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectBle() {
        stopBleScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    // -------------------- 기타 헬퍼 --------------------
    private fun proceedConnect() {
        val serial = serialOrDefault()
        vm.connect(serial)
        startBleScan()
        toast("BLE 기기 검색 시작 (시리얼: $serial)")
    }

    private fun applyExpandState(animated: Boolean) {
        val container = scroll ?: return
        if (animated) {
            TransitionManager.beginDelayedTransition(
                container,
                AutoTransition().setDuration(180)
            )
        }
    }

    private fun pushPresetAlarm(
        level: AlarmLevel,
        title: String,
        detail: String,
        device: String
    ) {
        val now = System.currentTimeMillis()
        vm.pushAlarm(
            AlarmEvent(
                id = UUID.randomUUID().toString(),
                level = level,
                timeMillis = now,
                deviceId = device,
                title = title,
                detail = detail
            )
        )
        recyclerAlarms?.let { rv ->
            Snackbar.make(rv, "테스트 알림 생성: $title", Snackbar.LENGTH_SHORT).show()
            rv.scrollToPosition(0)
        }
    }

    private fun hasSerial(): Boolean =
        !serialEdit.text?.toString().isNullOrBlank()

    private fun serialOrDefault(def: String = "A-10"): String =
        serialEdit.text?.toString()?.trim().orEmpty().ifEmpty { def }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // -------------------- RSSI 루프 --------------------
    @SuppressLint("MissingPermission")
    private fun startRssiLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                bluetoothGatt?.let { gatt ->
                    val ok = gatt.readRemoteRssi()
                    Log.d("BLE_RSSI", "readRemoteRssi() 요청: $ok")
                }
                delay(1500)
            }
        }
    }

    // -------------------- Notify 설정 --------------------
    @SuppressLint("MissingPermission")
    private fun enableFsrNotify(gatt: BluetoothGatt?) {
        if (gatt == null) return

        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.w("BLE_NOTIFY", "서비스 못 찾음: $SERVICE_UUID")
            return
        }

        val chNotify = service.getCharacteristic(CHAR_UUID_NOTIFY)
        if (chNotify == null) {
            Log.w("BLE_NOTIFY", "Notify 특성 못 찾음: $CHAR_UUID_NOTIFY")
            return
        }

        val ok = gatt.setCharacteristicNotification(chNotify, true)
        Log.d("BLE_NOTIFY", "setCharacteristicNotification 결과: $ok")

        val descriptor: BluetoothGattDescriptor? = chNotify.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeOk = gatt.writeDescriptor(descriptor)
            Log.d("BLE_NOTIFY", "CCCD writeDescriptor: $writeOk")
        } else {
            Log.w("BLE_NOTIFY", "CCCD 디스크립터(0x2902) 없음")
        }
    }

    // -------------------- BLE Write (모드 / 게이트 명령 전송) --------------------
    @SuppressLint("MissingPermission")
    private fun sendBleCommand(payload: String) {
        if (!hasBlePermissions()) {
            toast("BLE 권한이 없습니다.")
            return
        }

        val gatt = bluetoothGatt
        if (gatt == null) {
            toast("BLE가 아직 연결되지 않았어요.")
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.w("BLE_WRITE", "서비스를 찾지 못했습니다: $SERVICE_UUID")
            toast("BLE 서비스 없음")
            return
        }

        val ch = service.getCharacteristic(CHAR_UUID_WRITE)
        if (ch == null) {
            Log.w("BLE_WRITE", "WRITE 특성을 찾지 못했습니다: $CHAR_UUID_WRITE")
            toast("BLE WRITE 특성 없음")
            return
        }

        ch.value = payload.toByteArray(Charsets.UTF_8)
        val ok = gatt.writeCharacteristic(ch)
        Log.d("BLE_WRITE", "writeCharacteristic($payload) = $ok")

        if (!ok) {
            toast("BLE 전송 실패")
        } else {
            chipRtt.text = "명령 전송: $payload"
        }
    }
}