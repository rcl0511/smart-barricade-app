package com.example.myapplication.data.model

/**
 * 통신 연결 상태를 나타내는 모델.
 * - connected : 연결 여부
 * - rttMs     : 왕복지연(RTT, ms)
 * - lossPct   : 패킷 손실률(%)
 */
data class ConnectionState(
    val connected: Boolean = false,
    val rttMs: Int? = null,
    val lossPct: Int? = null
)