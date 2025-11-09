package com.example.myapplication.data.ble

import kotlinx.coroutines.*
import kotlin.random.Random

class FakeBleRepository : BleRepository {
    private var connected = false
    private var metricsJob: Job? = null

    override suspend fun scan(timeoutMs: Long): List<String> {
        delay(400) // 모의 지연
        return listOf("A-10", "A-12", "B-01")
    }

    override suspend fun connect(serial: String): Boolean {
        delay(300)  // 모의 연결 지연
        connected = true
        return true
    }

    override suspend fun disconnect() {
        connected = false
        metricsJob?.cancel()
        metricsJob = null
    }

    override fun startMetrics(onUpdate: (Int, Int) -> Unit) {
        metricsJob?.cancel()
        metricsJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && connected) {
                val rtt = 40 + Random.nextInt(0, 30)     // 40~69ms
                val loss = Random.nextInt(0, 5)           // 0~4%
                withContext(Dispatchers.Main) { onUpdate(rtt, loss) }
                delay(1000)
            }
        }
    }

    override suspend fun readCharacteristic(path: String): ByteArray {
        delay(100)
        return when (path) {
            "battery" -> byteArrayOf(95) // 95%
            "signal"  -> byteArrayOf(0xD8.toByte()) // -40dBm 예시
            else      -> byteArrayOf()
        }
    }

    override suspend fun writeCharacteristic(path: String, payload: ByteArray): Boolean {
        delay(80)
        return true
    }
}
