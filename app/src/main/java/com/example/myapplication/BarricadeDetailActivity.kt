package com.example.myapplication

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.addTextChangedListener
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class BarricadeDetailActivity : AppCompatActivity() {

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

    private lateinit var edtLedMessage: TextInputEditText
    private lateinit var btnLedRed: MaterialButton
    private lateinit var btnLedYellow: MaterialButton
    private lateinit var btnLedGreen: MaterialButton
    private lateinit var ledPreview: FrameLayout
    private lateinit var txtLedPreview: TextView

    // 사진 내 고정 좌표 (0~1)
    private val overlayNX = 0.5f
    private val overlayNY = 0.2f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_barricade_detail)

        val serial = intent.getStringExtra("serial") ?: "A-12"

        findViewById<MaterialToolbar?>(R.id.topBar)?.apply {
            title = "바리케이드 상세"
            subtitle = "SN: $serial"
            setNavigationOnClickListener { finish() }
        }

        // 바인딩
        imgBase        = findViewById(R.id.imgBarricadeBase)
        imgHeatSpot    = findViewById(R.id.imgHeatSpot)
        heatContainer  = findViewById(R.id.heatmapContainer)
        txtHeatOverlay = findViewById(R.id.txtHeatOverlay)

        btnOpenNow   = findViewById(R.id.btnOpenNow)
        btnOpenStep  = findViewById(R.id.btnOpenStep)
        btnClose     = findViewById(R.id.btnClose)
        btnLedPanel  = findViewById(R.id.btnLedPanel)

        edtLedMessage = findViewById(R.id.edtLedMessage)
        btnLedRed     = findViewById(R.id.btnLedRed)
        btnLedYellow  = findViewById(R.id.btnLedYellow)
        btnLedGreen   = findViewById(R.id.btnLedGreen)
        ledPreview    = findViewById(R.id.ledPreview)
        txtLedPreview = findViewById(R.id.txtLedPreview)

        // 초기 PREVIEW 동기화
        val initText = edtLedMessage.text?.toString().orEmpty().ifBlank { "PREVIEW" }
        txtLedPreview.text = initText
        txtHeatOverlay.text = initText

        // 초기 글씨색(원하면 바꿔도 됨)
        applyLedTextColor(android.R.color.white)

        // 입력 → 두 PREVIEW 텍스트 동기화
        edtLedMessage.addTextChangedListener {
            val t = it?.toString().orEmpty().ifBlank { "PREVIEW" }
            txtLedPreview.text = t
            txtHeatOverlay.text = t
        }

        // ✅ 버튼: "배경" X, "글씨색"만 변경 + 오버레이와 동기화
        btnLedRed.setOnClickListener    { applyLedTextColor(R.color.led_red) }
        btnLedYellow.setOnClickListener { applyLedTextColor(R.color.led_yellow) }
        btnLedGreen.setOnClickListener  { applyLedTextColor(R.color.led_green) }

        btnLedPanel.setOnClickListener {
            val msg = edtLedMessage.text?.toString().orEmpty()
            Toast.makeText(this, "보안요원 연결 ${if (msg.isBlank()) "김 눈송" else msg}", Toast.LENGTH_SHORT).show()
        }

        // 이미지 전환: 오버레이 표시 여부 함께 제어 (개방 시 숨김)
        btnOpenNow.setOnClickListener {
            setImage(R.drawable.illust_barricade, showHeatSpot = false, showOverlay = false)
            Toast.makeText(this, "긴급 개방", Toast.LENGTH_SHORT).show()
        }
        btnOpenStep.setOnClickListener {
            setImage(R.drawable.illust_barricade, showHeatSpot = false, showOverlay = false)
            Toast.makeText(this, "단계 개방", Toast.LENGTH_SHORT).show()
        }
        btnClose.setOnClickListener {
            setImage(R.drawable.heatmap_placeholder, showHeatSpot = true, showOverlay = true)
            Toast.makeText(this, "즉시 폐쇄", Toast.LENGTH_SHORT).show()
        }

        // 레이아웃 완료 후 지정 좌표에 오버레이 배치
        imgBase.doOnLayout {
            placeOverlayAtNormalized(imgBase, txtHeatOverlay, overlayNX, overlayNY)
        }
    }

    /** 배경 이미지 / 히트스팟 / 오버레이 표시 여부를 한 번에 제어 */
    private fun setImage(resId: Int, showHeatSpot: Boolean, showOverlay: Boolean) {
        imgBase.animate().alpha(0f).setDuration(120).withEndAction {
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
        }.start()

        imgHeatSpot.animate()
            .alpha(if (showHeatSpot) 0.75f else 0f)
            .setDuration(120)
            .start()
    }

    /** ✅ 텍스트 색만 변경: 아래 프리뷰 + 이미지 오버레이 동시 적용 */
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
}
