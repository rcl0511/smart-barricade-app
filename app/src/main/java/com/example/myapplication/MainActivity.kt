package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.graphics.Color
import android.content.res.ColorStateList

class MainActivity : AppCompatActivity() {

    // ---------- 상태 ----------
    private var detailsExpanded = false

    // ---------- View refs ----------
    private lateinit var serialEdit: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton

    // 프리셋 테스트 버튼 3종
    private lateinit var btnPresetLoad: MaterialButton
    private lateinit var btnPresetDensity: MaterialButton
    private lateinit var btnPresetBattery: MaterialButton

    private lateinit var chipConn: TextView
    private lateinit var chipRtt: TextView
    private lateinit var chipLoss: TextView
    private lateinit var chipBattery: Chip

    private var txtDeviceTitle: TextView? = null
    private var txtDeviceInfo: TextView? = null

    private var cardDevice: MaterialCardView? = null
    private var cardSensor: MaterialCardView? = null
    private var cardPresets: MaterialCardView? = null   // ✅ 여기로 변경
    private var scroll: ViewGroup? = null

    private var recyclerAlarms: RecyclerView? = null

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

    // ---------- VM & 권한 ----------
    private val vm: MainViewModel by viewModels()
    private lateinit var perm: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 개발단계 우회 스위치 (실기기 붙일 때 false)
        PermissionHelper.DEV_BYPASS = true

        bindViews()
        setupRecycler()
        setupSwipeToDelete()
        setupPermissions()
        wireUi()
        bindViewModel()
        applyExpandState(animated = false)
    }

    // -------------------- 초기 바인딩 --------------------
    private fun bindViews() {
        serialEdit    = findViewById(R.id.serialEdit)
        btnConnect    = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        // 프리셋 버튼
        btnPresetLoad    = findViewById(R.id.btnPresetLoad)
        btnPresetDensity = findViewById(R.id.btnPresetDensity)
        btnPresetBattery = findViewById(R.id.btnPresetBattery)

        chipConn = findViewById(R.id.chipConn)
        chipRtt  = findViewById(R.id.chipRtt)
        chipLoss = findViewById(R.id.chipLoss)
        chipBattery = findViewById(R.id.chipBattery)

        txtDeviceTitle = findViewById(R.id.txtDeviceTitle)
        txtDeviceInfo  = findViewById(R.id.txtDeviceInfo)

        cardDevice  = findViewById(R.id.cardDevice)
        cardSensor  = findViewById(R.id.cardSensor)
        cardPresets = findViewById(R.id.cardPresets)   // ✅ 여기로 변경
        scroll      = findViewById(R.id.scroll)

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
                0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
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
            val hasSerial = !serialEdit.text?.toString().isNullOrBlank()
            btnConnect.isEnabled = hasSerial
        }
        btnConnect.isEnabled = !serialEdit.text?.toString().isNullOrBlank()

        btnConnect.setOnClickListener {
            if (serialEdit.text?.toString().isNullOrBlank()) {
                toast(getString(R.string.toast_need_serial))
                return@setOnClickListener
            }
            chipConn.text = "🔗 연결 시도 중..."
            perm.requestBlePermissions()
        }

        btnDisconnect.setOnClickListener {
            vm.disconnect()
            detailsExpanded = false
            applyExpandState(animated = true)
            toast(getString(R.string.toast_disconnected))
        }

        // 카드 탭 → 상세
        cardDevice?.setOnClickListener {
            val serial = serialOrDefault()
            startActivity(
                Intent(this, BarricadeDetailActivity::class.java)
                    .putExtra("device_id", serial)
            )
        }
        // 카드 롱탭 → 시리얼 전달 방식
        cardDevice?.setOnLongClickListener {
            val serial = serialOrDefault("A-12")
            startActivity(
                Intent(this, BarricadeDetailActivity::class.java)
                    .putExtra("serial", serial)
            )
            true
        }

        // 프리셋 알람 3종
        btnPresetLoad.setOnClickListener {
            pushPresetAlarm(
                level = AlarmLevel.WARN,
                title = "하중 임계 근접",
                detail = "현재 하중 150kg / 임계값 200kg / LED 경고 점등",
                device = serialOrDefault("A-10")
            )
        }

        btnPresetDensity.setOnClickListener {
            pushPresetAlarm(
                level = AlarmLevel.WARN,
                title = "밀집도 급상승",
                detail = "2m 구간 인원 밀집도 95% / 접근 제한 필요",
                device = serialOrDefault("B-03")
            )
        }

        btnPresetBattery.setOnClickListener {
            pushPresetAlarm(
                level = AlarmLevel.ERROR,
                title = "배터리 부족",
                detail = "현재 전압 3.1V / 충전 필요 / 절전 모드 전환 예정",
                device = serialOrDefault("C-02")
            )
        }
    }

    // -------------------- VM 바인딩 --------------------
    private fun bindViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.conn.collectLatest { s ->
                        chipConn.text = if (s.connected)
                            getString(R.string.chip_connected)
                        else
                            getString(R.string.chip_disconnected)

                        chipRtt.text  = s.rttMs?.let { "RTT: ${it} ms" } ?: "RTT: - ms"
                        chipLoss.text = s.lossPct?.let { "Loss: ${it} %" } ?: "Loss: - %"

                        val hasSerial = !serialEdit.text?.toString().isNullOrBlank()
                        btnConnect.isEnabled = !s.connected && hasSerial
                        btnDisconnect.isEnabled = s.connected
                    }
                }
                launch {
                    vm.device.collectLatest { d ->
                        txtDeviceTitle?.text = d.title
                        val last = formatTime(System.currentTimeMillis())
                        val sig  = d.signalDbm ?: -71
                        txtDeviceInfo?.text = "신호 ${sig} dBm | 마지막 통신: $last | 상태: ${d.status}"
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
            else -> Color.parseColor("#F44336")
        }
        chipBattery.chipIconTint = ColorStateList.valueOf(color)
        chipBattery.setTextColor(color)
    }

    // -------------------- 동작 함수 --------------------
    private fun proceedConnect() {
        val serial = serialOrDefault()
        vm.connect(serial)  // FakeBleRepository 사용 중이면 모의 연결
        detailsExpanded = true
        applyExpandState(animated = true)
        toast(getString(R.string.toast_connected, serial))
    }

    private fun applyExpandState(animated: Boolean) {
        val container = scroll ?: return
        if (animated) {
            TransitionManager.beginDelayedTransition(
                container,
                AutoTransition().setDuration(180)
            )
        }
        val vis = if (detailsExpanded) View.VISIBLE else View.GONE
        cardSensor?.visibility  = vis
        cardPresets?.visibility = vis   // ✅ 여기로 변경
    }

    // 프리셋 공용 함수
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

    private fun serialOrDefault(def: String = "A-10"): String =
        serialEdit.text?.toString()?.trim().orEmpty().ifEmpty { def }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
