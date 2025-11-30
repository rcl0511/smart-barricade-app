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
import androidx.annotation.RequiresPermission
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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // ---------- 상태 ----------
    private var detailsExpanded = false

    // ---------- View refs ----------
    private lateinit var serialEdit: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton

    // 🔹 LED / GATE 제어 버튼
    private lateinit var btnLedOn: MaterialButton
    private lateinit var btnLedOff: MaterialButton
    private lateinit var btnGateOpen: MaterialButton
    private lateinit var btnGateClose: MaterialButton

    private var btnPresetLoad: MaterialButton? = null
    private var btnPresetDensity: MaterialButton? = null
    private var btnPresetBattery: MaterialButton? = null

    private lateinit var chipConn: TextView      // 연결 상태
    private lateinit var chipRtt: TextView       // BLE 기준 마지막 수신 시각 표기용

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

    // ESP32-S3 서비스 / 캐릭터리스틱 UUID (아두이노 코드와 반드시 동일해야 함)
    private val SERVICE_UUID = java.util.UUID.fromString(
        "12345678-1234-1234-1234-1234567890ab"
    )
    private val CHAR_UUID_NOTIFY = java.util.UUID.fromString(
        "abcd1234-1234-5678-9999-abcdef123456" // ✅ ESP32 → Android (Notify) - ESP32랑 동일하게!
    )
    private val CHAR_UUID_WRITE = java.util.UUID.fromString(
        "abcd0002-1234-5678-9999-abcdef123456" // Android → ESP32 (Write)
    )
    private val CCCD_UUID = java.util.UUID.fromString(
        "00002902-0000-1000-8000-00805f9b34fb"
    )

    // ---------- Chart ----------
    private lateinit var chartPressure: LineChart
    private val pressureEntries = ArrayList<Entry>()
    private var pressureX = 0f
    private val PRESSURE_THRESHOLD = 700

    // 차트 너무 튀지 않게 → 2초마다 한 점만 추가
    private var lastChartUpdateMs = 0L
    private val CHART_INTERVAL_MS = 2000L
    private var lastBleUpdateMs = 0L  // 디버깅용(필요 없으면 삭제해도 됨)

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

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BLE_GATT", "연결됨: ${gatt?.device?.address}")
                    bluetoothGatt = gatt
                    runOnUiThread {
                        chipConn.text = "연결됨 (BLE)"
                        chipConn.setTextColor(Color.BLUE)
                        toast("BLE 기기 연결 성공")
                        detailsExpanded = true
                        applyExpandState(animated = true)
                    }
                    gatt?.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLE_GATT", "연결 끊김")
                    bluetoothGatt = null
                    runOnUiThread {
                        chipConn.text = "연결 안 됨"
                        chipConn.setTextColor(Color.GRAY)
                        toast("BLE 연결 끊김")
                        detailsExpanded = false
                        applyExpandState(animated = true)
                    }
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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
                        "신호 ${-rssi} dBm | 마지막 통신: ${
                            formatTime(System.currentTimeMillis())
                        } | 상태: 정상"
                }
            } else {
                Log.w("BLE_RSSI", "RSSI 읽기 실패: status=$status")
            }
        }

        // ▼ ESP32에서 넘어온 센서 문자열 처리
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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

            // 예: "235,1,0,0"  → FSR, LED, BUZ, MOT
            val parts = text.split(",")
            val fsr = parts.getOrNull(0)?.toIntOrNull() ?: return
            val led = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val buz = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val mot = parts.getOrNull(3)?.toIntOrNull() ?: 0

            runOnUiThread {
                handleBleSensorUpdate(fsr, led, buz, mot)
            }
        }
    }

    // ---------- Chart 세팅 ----------
    // ---------- Chart 세팅 ----------
    private fun setupChart() {
        // 초기 데이터셋 (비어있는 상태로 생성)
        val dataSet = LineDataSet(mutableListOf<Entry>(), "압력 센서 값").apply {
            lineWidth = 2f
            color = Color.parseColor("#1E88E5")
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
            setDrawFilled(true)
            fillAlpha = 60
            fillColor = Color.parseColor("#1E88E5")
        }

        chartPressure.apply {
            resetViewPortOffsets()
            data = LineData(dataSet)   // ✅ 무조건 LineData 세팅

            description.isEnabled = false
            legend.isEnabled = false
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
                axisMaximum = 1500f
                setDrawAxisLine(false)
                setDrawGridLines(true)
                enableGridDashedLine(10f, 10f, 0f)
                textSize = 10f
            }

            val limit = LimitLine(PRESSURE_THRESHOLD.toFloat(), "임계값").apply {
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


    // BLE 센서값 들어올 때마다 UI 전체 갱신
    private fun handleBleSensorUpdate(fsr: Int, led: Int, buz: Int, mot: Int) {
        val now = System.currentTimeMillis()
        lastBleUpdateMs = now

        // 상단 카드 텍스트
        txtSensorStatus?.text = "압력 센서 값(BLE): $fsr"

        // 실시간 대시보드
        txtFsrValue?.text = "FSR 값: $fsr"
        txtLedState?.text = "LED: ${if (led == 1) "ON" else "OFF"}"
        txtBuzzerState?.text = "부저: ${if (buz == 1) "ON" else "OFF"}"
        txtMotorState?.text = "모터: ${if (mot == 1) "OPEN" else "CLOSE"}"
        txtLastUpdated?.text = "마지막 수신: ${formatTime(now)}"

        // 상태 레벨 배지
        val (label, color) = when {
            fsr >= PRESSURE_THRESHOLD -> "위험" to Color.parseColor("#D32F2F")
            fsr >= PRESSURE_THRESHOLD * 0.7 -> "주의" to Color.parseColor("#F9A825")
            else -> "대기" to Color.parseColor("#388E3C")
        }
        txtSensorLevel?.text = label
        txtSensorLevel?.setBackgroundColor(color)

        chipRtt.text = "BLE 업데이트: ${formatTime(now)}"

        // 차트는 2초마다 한 점만 추가
        appendPressureValue(fsr)
    }

    // 그래프에 점 추가 (2초에 한 번만)
    private fun appendPressureValue(value: Int) {
        val now = System.currentTimeMillis()

        if (now - lastChartUpdateMs < CHART_INTERVAL_MS) {
            return
        }
        lastChartUpdateMs = now

        pressureX += 1f

        // ✅ data / dataset 없으면 안전하게 생성
        val data = chartPressure.data ?: LineData().also {
            chartPressure.data = it
        }

        var dataSet = data.getDataSetByIndex(0) as? LineDataSet
        if (dataSet == null) {
            dataSet = LineDataSet(mutableListOf(), "압력 센서 값").apply {
                lineWidth = 2f
                color = Color.parseColor("#1E88E5")
                setDrawCircles(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
                setDrawFilled(true)
                fillAlpha = 60
                fillColor = Color.parseColor("#1E88E5")
            }
            data.addDataSet(dataSet)
        }

        // 실제 점 추가
        dataSet.addEntry(Entry(pressureX, value.toFloat()))

        // 오래된 점 삭제
        if (dataSet.entryCount > 60) {
            dataSet.removeFirst()
        }

        dataSet.notifyDataSetChanged()
        data.notifyDataChanged()
        chartPressure.notifyDataSetChanged()

        chartPressure.moveViewToX(pressureX)
        chartPressure.invalidate()

        if (value >= PRESSURE_THRESHOLD) {
            toast("⚠ 압력 임계값 초과: $value")
            pushPresetAlarm(
                level = AlarmLevel.WARN,
                title = "압력 임계 초과",
                detail = "현재 압력 $value / 임계값 $PRESSURE_THRESHOLD",
                device = serialOrDefault("A-10")
            )
        }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectBle()
    }

    // -------------------- View 바인딩 --------------------
    private fun bindViews() {
        serialEdit    = findViewById(R.id.serialEdit)
        btnConnect    = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        // 🔹 LED / GATE 제어 버튼
        btnLedOn      = findViewById(R.id.btnLedOn)
        btnLedOff     = findViewById(R.id.btnLedOff)
        btnGateOpen   = findViewById(R.id.btnGateOpen)
        btnGateClose  = findViewById(R.id.btnGateClose)

        btnPresetLoad    = findViewById(R.id.btnPresetLoad)
        btnPresetDensity = findViewById(R.id.btnPresetDensity)
        btnPresetBattery = findViewById(R.id.btnPresetBattery)

        chipConn    = findViewById(R.id.chipConn)
        chipRtt     = findViewById(R.id.chipRtt)

        chipBattery = findViewById(R.id.chipBattery)

        txtDeviceTitle  = findViewById(R.id.txtDeviceTitle)
        txtDeviceInfo   = findViewById(R.id.txtDeviceInfo)
        txtSensorStatus = findViewById(R.id.txtSensorStatus)

        cardDevice = findViewById(R.id.cardDevice)
        scroll     = findViewById(R.id.scroll)

        chartPressure = findViewById(R.id.chartPressure)

        recyclerAlarms = findViewById(R.id.recyclerAlarms)

        // ▼ 실시간 상태 대시보드
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

        // 🔹 LED / GATE 제어 버튼 → ESP32로 명령 전송
        btnLedOn.setOnClickListener {
            if (hasBlePermissions()) {
                sendBleCommand("LED_ON")
            } else {
                perm.requestBlePermissions()
            }
        }

        btnLedOff.setOnClickListener {
            if (hasBlePermissions()) {
                sendBleCommand("LED_OFF")
            } else {
                perm.requestBlePermissions()
            }
        }

        btnGateOpen.setOnClickListener {
            if (hasBlePermissions()) {
                sendBleCommand("MOTOR_ON")   // 게이트 OPEN = 모터 ON
            } else {
                perm.requestBlePermissions()
            }
        }

        btnGateClose.setOnClickListener {
            if (hasBlePermissions()) {
                sendBleCommand("MOTOR_OFF")  // 게이트 CLOSE = 모터 OFF
            } else {
                perm.requestBlePermissions()
            }
        }

        // 프리셋 버튼은 테스트 알람용
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

    // -------------------- 배터리 표시 (BLE에서 배터리 값 들어오면 여기로) --------------------
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
        // 지금은 숨길 카드 없음 (필요하면 cardSensor VISIBLE/GONE 처리)
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
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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

    // -------------------- BLE Write (LED / GATE 명령 전송) --------------------
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendBleCommand(payload: String) {
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

        // WRITE 캐릭터리스틱으로 전송
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
