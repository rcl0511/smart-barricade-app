package com.example.myapplication.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.AlarmEvent
import com.example.myapplication.data.model.AlarmLevel
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.DeviceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel : ViewModel() {

    private val _conn = MutableStateFlow(ConnectionState())
    val conn: StateFlow<ConnectionState> = _conn.asStateFlow()

    private val _device = MutableStateFlow(DeviceState())
    val device: StateFlow<DeviceState> = _device.asStateFlow()

    private val _alarms = MutableStateFlow<List<AlarmEvent>>(emptyList())
    val alarms: StateFlow<List<AlarmEvent>> = _alarms.asStateFlow()

    fun connect(serial: String) {
        viewModelScope.launch {
            _conn.value = ConnectionState(connected = true, rttMs = 18, lossPct = 0)
            _device.value = _device.value.copy(
                deviceId = serial,
                title = "바리케이드 #$serial",
                batteryPct = 97,
                signalDbm = -71,
                status = "ONLINE"
            )
            // 모의 RTT/LOSS 갱신
            repeat(1000) {
                if (!_conn.value.connected) return@repeat
                delay(1500)
                _conn.update {
                    it.copy(
                        rttMs = (14..28).random(),
                        lossPct = listOf(0, 0, 1).random()
                    )
                }
            }
        }
    }

    fun disconnect() {
        _conn.value = ConnectionState(connected = false, rttMs = null, lossPct = null)
        _device.update { it.copy(status = "IDLE", signalDbm = null) }
    }

    fun pushAlarm(e: AlarmEvent) {
        _alarms.update { listOf(e) + it }   // 최신이 위로
    }

    fun removeAlarm(id: String): AlarmEvent? {
        var removed: AlarmEvent? = null
        _alarms.update { list ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                removed = list[idx]
                list.toMutableList().apply { removeAt(idx) }
            } else list
        }
        return removed
    }

    fun restoreAlarm(e: AlarmEvent) {
        _alarms.update { listOf(e) + it }
    }

    // 데모 알람 주기 생성 (옵션)
    fun startDemoAlarms() {
        viewModelScope.launch {
            while (true) {
                delay(5000)
                val lvl = listOf(AlarmLevel.INFO, AlarmLevel.WARN).random()
                pushAlarm(
                    AlarmEvent(
                        id = UUID.randomUUID().toString(),
                        level = lvl,
                        timeMillis = System.currentTimeMillis(),
                        deviceId = device.value.deviceId.ifEmpty { "A-10" },
                        title = if (lvl == AlarmLevel.WARN) "밀집도 상승" else "상태 점검",
                        detail = if (lvl == AlarmLevel.WARN) "혼잡도 지수 상승, 접근 제한 검토" else "주기 점검 완료"
                    )
                )
            }
        }
    }
}
