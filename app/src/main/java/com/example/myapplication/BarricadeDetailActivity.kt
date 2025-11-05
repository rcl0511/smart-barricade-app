package com.example.myapplication

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class BarricadeDetailActivity : AppCompatActivity() {

    private lateinit var imgBase: ImageView
    private lateinit var imgHeatSpot: ImageView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_barricade_detail)

        val serial = intent.getStringExtra("serial") ?: "A-12"

        findViewById<MaterialToolbar?>(R.id.topBar)?.apply {
            title = "바리케이드 상세"
            subtitle = "SN: $serial"
            setNavigationOnClickListener { finish() }
        }

        imgBase     = findViewById(R.id.imgBarricadeBase)
        imgHeatSpot = findViewById(R.id.imgHeatSpot)

        btnOpenNow  = findViewById(R.id.btnOpenNow)
        btnOpenStep = findViewById(R.id.btnOpenStep)
        btnClose    = findViewById(R.id.btnClose)
        btnLedPanel = findViewById(R.id.btnLedPanel)

        edtLedMessage = findViewById(R.id.edtLedMessage)
        btnLedRed     = findViewById(R.id.btnLedRed)
        btnLedYellow  = findViewById(R.id.btnLedYellow)
        btnLedGreen   = findViewById(R.id.btnLedGreen)
        ledPreview    = findViewById(R.id.ledPreview)
        txtLedPreview = findViewById(R.id.txtLedPreview)

        btnOpenNow.setOnClickListener {
            setImage(R.drawable.illust_barricade, showHeatSpot = false)
            Toast.makeText(this, "긴급 개방", Toast.LENGTH_SHORT).show()
        }
        btnOpenStep.setOnClickListener {
            setImage(R.drawable.illust_barricade, showHeatSpot = false)
            Toast.makeText(this, "단계 개방", Toast.LENGTH_SHORT).show()
        }
        btnClose.setOnClickListener {
            setImage(R.drawable.heatmap_placeholder, showHeatSpot = true)
            Toast.makeText(this, "즉시 폐쇄", Toast.LENGTH_SHORT).show()
        }

        txtLedPreview.text = edtLedMessage.text?.toString().orEmpty().ifEmpty { "PREVIEW" }
        edtLedMessage.addTextChangedListener {
            val t = it?.toString().orEmpty()
            txtLedPreview.text = if (t.isBlank()) "PREVIEW" else t
        }

        btnLedRed.setOnClickListener    { applyLedTextColor(R.color.led_red) }
        btnLedYellow.setOnClickListener { applyLedTextColor(R.color.led_yellow) }
        btnLedGreen.setOnClickListener  { applyLedTextColor(R.color.led_green) }

        btnLedPanel.setOnClickListener {
            val msg = edtLedMessage.text?.toString().orEmpty()
            Toast.makeText(this, "LED 전송: ${if (msg.isBlank()) "(텍스트 없음)" else msg}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setImage(resId: Int, showHeatSpot: Boolean) {
        imgBase.animate().alpha(0f).setDuration(150).withEndAction {
            imgBase.setImageResource(resId)
            imgBase.animate().alpha(1f).setDuration(150).start()
        }.start()

        imgHeatSpot.animate()
            .alpha(if (showHeatSpot) 0.75f else 0f)
            .setDuration(150)
            .start()
    }

    private fun applyLedTextColor(colorResId: Int) {
        txtLedPreview.setTextColor(ContextCompat.getColor(this, colorResId))
    }
}
