package com.example.myapplication

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.doOnLayout
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.data.ble.BleRepository
import com.example.myapplication.data.ble.FakeBleRepository
import com.example.myapplication.data.ble.RealBleRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.time.LocalTime

class BarricadeDetailActivity : AppCompatActivity() {

    // --- BLE ---
    private lateinit var bleRepo: BleRepository
    private var currentSerial: String = "A-12"

    // --- 히트맵/스팟 ---
    private lateinit var imgBase: ImageView
    private lateinit var imgHeatSpot: ImageView
    private lateinit var heatContainer: FrameLayout
    private lateinit var txtHeatOverlay: TextView

    // --- 조작 버튼/LED 미리보기 ---
    private lateinit var btnOpenNow: MaterialButton
    private lateinit var btnClose: MaterialButton
    private lateinit var btnOpenStep: MaterialButton
    private lateinit var btnLedPanel: MaterialButton
    private lateinit var switchAuto: MaterialSwitch

    private lateinit var edtLedMessage: TextInputEditText
    private lateinit var btnLedRed: MaterialButton
    private lateinit var btnLedYellow: MaterialButton
    private lateinit var btnLedGreen: MaterialButton
    private lateinit var ledPreview: FrameLayout
    private lateinit var txtLedPreview: TextView

    // --- 센서 대시보드( cardSensor 안 텍스트뷰 ) ---
    private lateinit var txtSensorTitle: TextView
    private lateinit var txtSensorLevel: TextView
    private lateinit var txtFsrValue: TextView
    private lateinit var txtLedState: TextView
    private lateinit var txtBuzzerState: TextView
    private lateinit var txtMotorState: TextView
    private lateinit var txtLastUpdated: TextView



    // 사진 내 고정 좌표 (0~1) – PREVIEW 오버레이 위치
    private val overlayNX = 0.5f
    private val overlayNY = 0.2f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_barricade_detail)

        val serial = intent.getStringExtra("serial") ?: "A-12"
        currentSerial = serial

        // 🔌 실제 / 페이크 선택 (실기 테스트 시 RealBleRepository 사용)
        bleRepo = RealBleRepository(this)
        // 개발용 더미 데이터 쓰고 싶으면 아래로 교체
        // bleRepo = FakeBleRepository()

        // 상단 바
        findViewById<MaterialToolbar?>(R.id.topBar)?.apply {
            title = "바리케이드 상세"
            subtitle = "SN: $serial"
            setNavigationOnClickListener { finish() }
        }

        // --- View 바인딩 ---
        imgBase        = findViewById(R.id.imgBarricadeBase)
        imgHeatSpot    = findViewById(R.id.imgHeatSpot)
        heatContainer  = findViewById(R.id.heatmapContainer)
        txtHeatOverlay = findViewById(R.id.txtHeatOverlay)

        btnOpenNow   = findViewById(R.id.btnOpenNow)
        btnOpenStep  = findViewById(R.id.btnOpenStep)
        btnClose     = findViewById(R.id.btnClose)
        btnLedPanel  = findViewById(R.id.btnLedPanel)
        switchAuto   = findViewById(R.id.switchAuto)

        edtLedMessage = findViewById(R.id.edtLedMessage)
        btnLedRed     = findViewById(R.id.btnLedRed)
        btnLedYellow  = findViewById(R.id.btnLedYellow)
        btnLedGreen   = findViewById(R.id.btnLedGreen)
        ledPreview    = findViewById(R.id.ledPreview)
        txtLedPreview = findViewById(R.id.txtLedPreview)

        // --- 센서 카드 뷰 바인딩 ---
        txtSensorTitle  = findViewById(R.id.txtSensorTitle)
        txtSensorLevel  = findViewById(R.id.txtSensorLevel)
        txtFsrValue     = findViewById(R.id.txtFsrValue)
        txtLedState     = findViewById(R.id.txtLedState)
        txtBuzzerState  = findViewById(R.id.txtBuzzerState)
        txtMotorState   = findViewById(R.id.txtMotorState)
        txtLastUpdated  = findViewById(R.id.txtLastUpdated)

        // --- LED 텍스트 PREVIEW 동기화 ---
        val initText = edtLedMessage.text?.toString().orEmpty().ifBlank { "PREVIEW" }
        txtLedPreview.text = initText
        txtHeatOverlay.text = initText

        // 기본 글씨색 (흰색)
        applyLedTextColor(android.R.color.white)

        // 입력 → 아래 패널 + 히트맵 오버레이 텍스트 동기화
        edtLedMessage.addTextChangedListener {
            val t = it?.toString().orEmpty().ifBlank { "PREVIEW" }
            txtLedPreview.text = t
            txtHeatOverlay.text = t
        }

        // --- LED 색 버튼: UI 색만 바꾸고, ESP32에는 LED_ON만 보냄 ---
        btnLedRed.setOnClickListener {
            applyLedTextColor(R.color.led_red)
            sendCmd("LED_ON") // ESP32: LED 켜기
        }

        btnLedYellow.setOnClickListener {
            applyLedTextColor(R.color.led_yellow)
            sendCmd("LED_ON")
        }

        btnLedGreen.setOnClickListener {
            applyLedTextColor(R.color.led_green)
            sendCmd("LED_ON")
        }

        // 보안 요원 패널 버튼 (지금은 토스트만, 필요하면 BLE도 추가 가능)
        btnLedPanel.setOnClickListener {
            val msg = edtLedMessage.text?.toString().orEmpty()
            Toast.makeText(
                this,
                "보안요원 연결 ${if (msg.isBlank()) "김 눈송" else msg}",
                Toast.LENGTH_SHORT
            ).show()
            // 예: 나중에 sendCmd("PANEL_CALL") 추가 가능
        }

        // --- 조작 패널: 게이트 개방/폐쇄 + ESP32 모터 제어 ---
        btnOpenNow.setOnClickListener {
            setImage(
                resId = R.drawable.illust_barricade,
                showHeatSpot = false,
                showOverlay = false
            )
            Toast.makeText(this, "긴급 개방", Toast.LENGTH_SHORT).show()
            // ESP32: MOTOR_ON → 게이트 완전 개방
            sendCmd("MOTOR_ON")
        }

        btnOpenStep.setOnClickListener {
            setImage(
                resId = R.drawable.illust_barricade,
                showHeatSpot = false,
                showOverlay = false
            )
            Toast.makeText(this, "단계 개방", Toast.LENGTH_SHORT).show()
            // 현재 아두이노에는 MOTOR_STEP이 없으니,
            // 일단은 MOTOR_ON을 쓰고, 필요하면 나중에 별도 명령 추가
            sendCmd("MOTOR_ON")
        }

        btnClose.setOnClickListener {
            setImage(
                resId = R.drawable.heatmap_placeholder,
                showHeatSpot = true,
                showOverlay = true
            )
            Toast.makeText(this, "즉시 폐쇄", Toast.LENGTH_SHORT).show()
            // ESP32: MOTOR_OFF → 게이트 닫기
            sendCmd("MOTOR_OFF")
        }

        // 자동제어 스위치 (지금은 UI만, 나중에 "AUTO_ON"/"AUTO_OFF" 같은 명령 추가 가능)
        switchAuto.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(this, "임계치 초과 시 자동제어 ON", Toast.LENGTH_SHORT).show()
                // 필요하면 sendCmd("AUTO_ON")
            } else {
                Toast.makeText(this, "임계치 초과 시 자동제어 OFF", Toast.LENGTH_SHORT).show()
                // 필요하면 sendCmd("AUTO_OFF")
            }
        }

        // 히트맵 오버레이 위치 세팅
        imgBase.doOnLayout {
            placeOverlayAtNormalized(imgBase, txtHeatOverlay, overlayNX, overlayNY)
        }

        // 🔗 액티비티 시작 시 BLE 연결 시도
        lifecycleScope.launch {
            val ok = bleRepo.connect(currentSerial)
            if (ok) {
                Toast.makeText(
                    this@BarricadeDetailActivity,
                    "BLE 연결 완료",
                    Toast.LENGTH_SHORT
                ).show()

                // ✅ 임시: BleRepository.startMetrics 를 사용해서
                // 더미 센서값을 센서 카드에 반영 (RealBleRepository에서는
                // 실제 Notify 기반 업데이트로 바꾸면 됨)
                bleRepo.startMetrics { rttMs, lossPct ->
                    // 여기서는 rttMs를 FSR 값처럼 사용하고,
                    // lossPct를 간단히 LED ON/OFF 기준으로 활용
                    val fsr = rttMs          // 40~70 정도 범위
                    val led = if (lossPct > 0) 1 else 0
                    val buzzer = 0
                    val motor = 0

                    runOnUiThread {
                        updateSensorDashboard(fsr, led, buzzer, motor)
                    }
                }
            } else {
                Toast.makeText(
                    this@BarricadeDetailActivity,
                    "BLE 연결 실패",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 액티비티 종료 시 BLE 연결 정리
        lifecycleScope.launch {
            bleRepo.disconnect()
        }
    }

    /** 공통 BLE 명령 전송 함수 */
    private fun sendCmd(cmd: String) {
        lifecycleScope.launch {
            val ok = bleRepo.writeCharacteristic("cmd", cmd.toByteArray())
            if (ok) {
                // ✅ 명령 전송 성공: 토스트 + 진동(짧게)로 피드백
                Toast.makeText(
                    this@BarricadeDetailActivity,
                    "명령 전송: $cmd",
                    Toast.LENGTH_SHORT
                ).show()
                vibrateShort()
            } else {
                Toast.makeText(
                    this@BarricadeDetailActivity,
                    "BLE 연결 안 됨",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 짧은 진동 (명령 전송 성공 피드백) */
    private fun vibrateShort() {
        try {
            // Android 12+ 기준 VibratorManager 사용
            val vm = getSystemService<VibratorManager>()
            val vibrator: Vibrator? = vm?.defaultVibrator ?: getSystemService()
            vibrator?.vibrate(
                VibrationEffect.createOneShot(
                    60L,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } catch (e: Exception) {
            // 진동 권한 없거나 태블릿/에뮬레이터인 경우 무시
        }
    }

    /** 배경 이미지 / 히트스팟 / 오버레이 표시 여부를 한 번에 제어 */
    private fun setImage(resId: Int, showHeatSpot: Boolean, showOverlay: Boolean) {
        imgBase.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                imgBase.setImageResource(resId)
                imgBase.animate().alpha(1f).setDuration(120).start()

                if (showOverlay) {
                    txtHeatOverlay.visibility = View.VISIBLE
                    imgBase.doOnLayout {
                        placeOverlayAtNormalized(imgBase, txtHeatOverlay, overlayNX, overlayNY)
                    }
                } else {
                    txtHeatOverlay.visibility = View.GONE
                }
            }
            .start()

        imgHeatSpot.animate()
            .alpha(if (showHeatSpot) 0.75f else 0f)
            .setDuration(120)
            .start()
    }

    /** 텍스트 색만 변경: 아래 프리뷰 + 이미지 오버레이 동시 적용 */
    private fun applyLedTextColor(colorRes: Int) {
        val c = ContextCompat.getColor(this, colorRes)
        txtLedPreview.setTextColor(c)
        if (txtHeatOverlay.visibility == View.VISIBLE) {
            txtHeatOverlay.setTextColor(c)
        }
    }

    /** 정규화 좌표(0~1)를 현재 이미지 뷰 좌표로 변환해 오버레이 배치 */
    private fun placeOverlayAtNormalized(
        imageView: ImageView,
        overlay: View,
        nx: Float,
        ny: Float
    ) {
        val d = imageView.drawable ?: return

        val dx = nx.coerceIn(0f, 1f) * d.intrinsicWidth
        val dy = ny.coerceIn(0f, 1f) * d.intrinsicHeight
        val pts = floatArrayOf(dx, dy)

        imageView.imageMatrix.mapPoints(pts)

        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val halfW = overlay.measuredWidth / 2f
        val halfH = overlay.measuredHeight / 2f

        overlay.translationX = imageView.left + pts[0] - halfW
        overlay.translationY = imageView.top  + pts[1] - halfH
    }

    /**
     * ESP32 → BLE Notify 로 들어온 값( val, led, buzzer, motor )을
     * 센서 카드(cardSensor)에 반영하는 함수
     *
     * @param fsr    0~4095 센서 값
     * @param led    0 / 1
     * @param buzzer 0 / 1
     * @param motor  0 / 1
     */
    fun updateSensorDashboard(fsr: Int, led: Int, buzzer: Int, motor: Int) {
        // 1) 기본 텍스트 업데이트
        txtFsrValue.text    = "FSR 값: $fsr"
        txtLedState.text    = "LED: ${if (led == 1) "ON" else "OFF"}"
        txtBuzzerState.text = "부저: ${if (buzzer == 1) "ON" else "OFF"}"
        txtMotorState.text  = "모터: ${if (motor == 1) "ON" else "OFF"}"

        // 2) 위험도 레벨 표시 (임계값은 ESP32 쪽 THRESHOLD = 700 과 맞춰줌)
        val levelText: String
        val levelColorRes: Int

        when {
            fsr >= 1200 -> { // 심한 압력
                levelText = "위험"
                levelColorRes = android.R.color.holo_red_dark
            }
            fsr >= 700 -> {  // 임계 근처
                levelText = "주의"
                levelColorRes = android.R.color.holo_orange_dark
            }
            fsr > 0 -> {     // 약한 압력
                levelText = "감지"
                levelColorRes = android.R.color.holo_green_dark
            }
            else -> {
                levelText = "대기"
                levelColorRes = android.R.color.darker_gray
            }
        }

        fun updateSensorDashboard(fsr: Int, led: Int, buzzer: Int, motor: Int) {
            txtFsrValue.text    = "FSR 값: $fsr"
            txtLedState.text    = "LED: ${if (led == 1) "ON" else "OFF"}"
            txtBuzzerState.text = "부저: ${if (buzzer == 1) "ON" else "OFF"}"
            txtMotorState.text  = "모터: ${if (motor == 1) "ON" else "OFF"}"

            val levelText: String
            val levelColorRes: Int

            when {
                fsr >= 1200 -> {
                    levelText = "위험"
                    levelColorRes = android.R.color.holo_red_dark
                }
                fsr >= 700 -> {
                    levelText = "주의"
                    levelColorRes = android.R.color.holo_orange_dark
                }
                fsr > 0 -> {
                    levelText = "감지"
                    levelColorRes = android.R.color.holo_green_dark
                }
                else -> {
                    levelText = "대기"
                    levelColorRes = android.R.color.darker_gray
                }
            }

            txtSensorLevel.text = levelText
            txtSensorLevel.setTextColor(
                ContextCompat.getColor(this, levelColorRes)
            )

            val now = java.time.LocalTime.now()
            val formatted = now.toString().substring(0, 8)
            txtLastUpdated.text = "마지막 수신: $formatted"
        }


        // 3) 마지막 수신 시간 표시 (HH:mm:ss)
        val now = LocalTime.now()
        val formatted = now.toString().substring(0, 8)
        txtLastUpdated.text = "마지막 수신: $formatted"
    }
}
