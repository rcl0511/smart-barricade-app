package com.example.myapplication.data.net

data class SensorLatestResponse(
    val received_at: String? = null,
    val device_id: String? = null,
    val value: Int? = null,
    val led: Int? = null,
    val message: String? = null
)
