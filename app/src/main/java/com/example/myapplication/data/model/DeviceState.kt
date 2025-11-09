package com.example.myapplication.data.model

/**
 * 장치(바리케이드) 요약 상태 모델.
 * - deviceId  : 장치 식별자(시리얼 등)
 * - title     : 화면에 노출할 이름
 * - batteryPct: 배터리(%)
 * - signalDbm : 신호 세기(dBm)
 * - lastSeen  : 마지막 통신 시각(epoch millis)
 * - status    : 임의 상태 텍스트(ONLINE/OFFLINE/IDLE 등)
 */
data class DeviceState(
    val deviceId: String = "",
    val title: String = "바리케이드 #A-10",
    val batteryPct: Int = 100,
    val signalDbm: Int? = null,
    val lastSeen: Long? = null,
    val status: String = "IDLE"
)
