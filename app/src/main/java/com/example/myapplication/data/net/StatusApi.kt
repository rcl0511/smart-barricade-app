package com.example.myapplication.data.net

import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.DeviceState
import com.example.myapplication.data.model.AlarmEvent

interface StatusApi {
    suspend fun postStatus(device: DeviceState, conn: ConnectionState): Boolean
    suspend fun postLog(event: AlarmEvent): Boolean
}
