package com.example.myapplication.data.ble

interface BleRepository {
    suspend fun scan(timeoutMs: Long = 3000L): List<String> // 예: ["A-10","A-12"]
    suspend fun connect(serial: String): Boolean
    suspend fun disconnect()
    fun startMetrics(onUpdate: (rttMs: Int, lossPct: Int) -> Unit)
    suspend fun readCharacteristic(path: String): ByteArray // 예: "battery"
    suspend fun writeCharacteristic(path: String, payload: ByteArray): Boolean
}
