package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
import android.view.View
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
import com.example.myapplication.data.net.ApiClient
import com.example.myapplication.data.net.SensorLatestResponse
import com.example.myapplication.ui.main.AlarmAdapter
import com.example.myapplication.ui.main.MainViewModel
import com.example.myapplication.ui.util.PermissionHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.LimitLine

class MainActivity : AppCompatActivity() {

    // ---------- 상태 ----------
    private var detailsExpanded = false   // 지금은 cardSensor 안 쓰지만, 나중 확장용으로 남겨둠

    // ---------- View refs ----------
    private lateinit var serialEdit: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton

    // 프리셋 버튼 (레이아웃에서 없어질 수도 있으니 nullable)
    private var btnPresetLoad: MaterialButton? = null
    private var btnPresetDensity: MaterialButton? = null
    private var btnPresetBattery: MaterialButton? = null

    private lateinit var chipConn: TextView   // Chip이지만 TextView로 받음
    private lateinit var chipRtt: TextView    // E2E(ESP32→서버→앱) 지연 표시용

    private lateinit var chipBattery: Chip

    private var txtDeviceTitle: TextView? = null
    private var txtDeviceInfo: TextView? = null
    private var txtSensorStatus: TextView? = null   // 서버 센서값 표시

    private var cardDevice: MaterialCardView? = null
    private var scroll: ViewGroup? = null

    private var recyclerAlarms: RecyclerView? = null

    // ---------- BLE 관련 ----------
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD = 10_000L  // 10초 스캔

    // ---------- Chart ----------
    private lateinit var chartPressure: LineChart
    private val pressureEntries = ArrayList<Entry>()   // 압력 데이터
    private var pressureX = 0f
    private val PRESSURE_THRESHOLD = 700

    // 스캔 중 발견한 기기 리스트
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    // ---------- 알람 어댑터 ----------
    private val vm: MainViewModel by viewModels()
    private val alarmAdapter = AlarmAdapter(
        onAcknowledge = { /* 서버 업로드 등 필요시 */ },
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

            // 이미 추가된 기기면 스킵
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
                    // 서비스 탐색 시작
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
                Log.d("BLE_GATT", "서비스 발견됨 → RSSI 루프 시작")
                gatt?.readRemoteRssi()
                startRssiLoop()
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
    }

    // ---------- Chart 세팅 ----------
    private fun setupChart() {
        // 1) 데이터셋 스타일
        val dataSet = LineDataSet(pressureEntries, "압력 센서 값").apply {
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
            // ⭐ 여백 자동 계산 초기화 (이게 제일 중요)
            resetViewPortOffsets()

            data = LineData(dataSet)

            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)

            setTouchEnabled(false)
            setScaleEnabled(false)
            setPinchZoom(false)

            axisRight.isEnabled = false

            // 왼쪽 숫자 잘 안 짤리게 살짝만 추가 여백
            // (너무 크면 또 줄어들 수 있으니까 8~16 정도만)
            setExtraLeftOffset(12f)
            setMinOffset(12f)

            // X축
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                setDrawAxisLine(false)
                setDrawLabels(false)   // 실시간이면 라벨 안보여도 됨
            }

            // Y축
            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = 1500f   // 갑자기 큰 값 나와도 항상 보이게 고정

                setDrawAxisLine(false)
                setDrawGridLines(true)
                enableGridDashedLine(10f, 10f, 0f)

                textSize = 10f
            }

            // 임계값 라인
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

    private fun updateChartWithSensor(body: SensorLatestResponse?) {
        val v = body?.value ?: return
        appendPressureValue(v)
    }

    private fun appendPressureValue(value: Int) {
        pressureX += 1f
        pressureEntries.add(Entry(pressureX, value.toFloat()))

        if (pressureEntries.size > 60) {
            pressureEntries.removeAt(0)
        }

        val dataSet = chartPressure.data.getDataSetByIndex(0) as LineDataSet
        dataSet.notifyDataSetChanged()
        chartPressure.data.notifyDataChanged()
        chartPressure.notifyDataSetChanged()

        chartPressure.moveViewToX(pressureX)
        chartPressure.animateX(500)

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
        startSensorPolling()
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

    // -------------------- FastAPI 폴링 --------------------
    private fun startSensorPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val response = ApiClient.api.getLatestSensor()
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            val body: SensorLatestResponse? = response.body()
                            txtSensorStatus?.text = formatSensorStatus(body)
                            updateEndToEndLatency(body)
                            updateChartWithSensor(body)
                        } else {
                            txtSensorStatus?.text =
                                "압력 센서 상태: 서버 오류 (${response.code()})"
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SENSOR_POLL", "네트워크 예외", e)
                    withContext(Dispatchers.Main) {
                        txtSensorStatus?.text = "압력 센서 상태: 네트워크 에러"
                    }
                }

                delay(1000) // 1초마다
            }
        }
    }

    // 센서 상태 텍스트 포맷
    private fun formatSensorStatus(body: SensorLatestResponse?): String {
        return when {
            body == null -> "압력 센서 상태: 응답 없음"
            body.value != null -> {
                val v = body.value
                val led = body.led
                "압력 센서 값: $v (LED: ${if (led == 1) "ON" else "OFF"})"
            }
            body.message != null -> "압력 센서 상태: ${body.message}"
            else -> "압력 센서 상태: 데이터 없음"
        }
    }

    // ESP32 → 서버 → 앱까지 E2E 지연 계산 (received_at 사용)
// ESP32 → 서버 → 앱까지 E2E 지연 계산 (received_at 사용)
    private fun updateEndToEndLatency(body: SensorLatestResponse?) {
        val ts = body?.received_at ?: return

        try {
            val serverTimeMs = OffsetDateTime.parse(ts)
                .toInstant()
                .toEpochMilli()

            val nowMs = System.currentTimeMillis()
            val e2e = nowMs - serverTimeMs

            chipRtt.text = if (e2e >= 0) {
                val seconds = e2e / 1000.0
                String.format("Delay: %.2f s", seconds)
            } else {
                "Delay: - s"
            }
        } catch (e: Exception) {
            Log.e("LATENCY", "timestamp 파싱 실패: $ts", e)
            chipRtt.text = "Delay: - s"
        }
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

        vm.connect(serial)   // Fake repo 쪽 상태
        startBleScan()       // 실제 BLE 스캔

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
        // 현재는 숨길 cardSensor가 없어서 아무것도 안 함.
        // 나중에 상세 카드 추가하면 여기에서 VISIBLE/GONE 처리하면 됨.
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
}
