package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private var detailsExpanded = false

    private lateinit var serialEdit: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton

    private lateinit var chipConn: TextView
    private lateinit var chipRtt: TextView
    private lateinit var chipLoss: TextView

    // 레이아웃에 없을 수도 있으므로 nullable 로 참조
    private var cardDevice: MaterialCardView? = null
    private var cardSensor: MaterialCardView? = null
    private var cardControl: MaterialCardView? = null
    private var scroll: ViewGroup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        wireUi()
        applyExpandState(animated = false)
    }

    private fun bindViews() {
        serialEdit    = findViewById(R.id.serialEdit)
        btnConnect    = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        chipConn = findViewById(R.id.chipConn)
        chipRtt  = findViewById(R.id.chipRtt)
        chipLoss = findViewById(R.id.chipLoss)

        cardDevice  = findViewById(R.id.cardDevice)
        scroll      = findViewById(R.id.scroll)
        // 다음 두 개는 현재 레이아웃에 없을 수 있음(널 허용)
        cardSensor  = findViewById(R.id.cardSensor)

    }

    private fun wireUi() {
        serialEdit.addTextChangedListener {
            btnConnect.isEnabled = !serialEdit.text.isNullOrBlank()
        }
        btnConnect.isEnabled = !serialEdit.text.isNullOrBlank()

        btnConnect.setOnClickListener {
            if (serialEdit.text.isNullOrBlank()) {
                toast(getString(R.string.toast_need_serial))
                return@setOnClickListener
            }
            chipConn.text = "🔗 연결 시도 중..."
            setConnected(true)
            toast(getString(R.string.toast_connected, serialEdit.text))
            detailsExpanded = true
            applyExpandState(animated = true)
        }

        btnDisconnect.setOnClickListener {
            setConnected(false)
            detailsExpanded = false
            applyExpandState(animated = true)
            toast(getString(R.string.toast_disconnected))
        }

        // cardDevice 클릭 시 상세 화면으로 이동
        cardDevice?.setOnClickListener {
            val intent = Intent(this, BarricadeDetailActivity::class.java)
            // 필요한 데이터가 있다면 putExtra로 전달 가능
            intent.putExtra("device_id", "A-10")
            startActivity(intent)
        }

        cardDevice?.setOnLongClickListener {
            val serial = serialEdit.text?.toString()?.trim().orEmpty()
            startActivity(
                Intent(this, BarricadeDetailActivity::class.java)
                    .putExtra("serial", serial.ifEmpty { "A-12" })
            )
            true
        }
    }

    private fun setConnected(connected: Boolean) {
        chipConn.text = if (connected)
            getString(R.string.chip_connected)
        else
            getString(R.string.chip_disconnected)

        chipRtt.text  = if (connected) "RTT: 18 ms" else "RTT: - ms"
        chipLoss.text = if (connected) "Loss: 0.3 %" else "Loss: - %"

        if (!connected) {
            detailsExpanded = false
            applyExpandState(animated = true)
        }
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
        // 레이아웃에 있을 때만 토글
        cardSensor?.visibility  = vis
        cardControl?.visibility = vis
        // ❌ LED FAB/버튼은 더 이상 다루지 않음 (완전 제거)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
