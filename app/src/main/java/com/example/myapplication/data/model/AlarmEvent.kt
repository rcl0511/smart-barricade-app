package com.example.myapplication.data.model

enum class AlarmLevel { INFO, WARN, ERROR }

/**
 * 알람(로그/이벤트) 모델.
 * - id       : 고유 ID(예: UUID)
 * - level    : INFO/WARN/ERROR
 * - timeMillis: 발생 시각(epoch millis)
 * - deviceId : 발생 장치
 * - title    : 알람 제목
 * - detail   : 상세 메시지
 */
data class AlarmEvent(
    val id: String,
    val level: AlarmLevel,
    val timeMillis: Long,
    val deviceId: String,
    val title: String,
    val detail: String
)
