package com.example.myapplication.data.net

import com.example.myapplication.data.model.*
import kotlinx.coroutines.delay
import android.util.Log

class FakeStatusApi : StatusApi {
    override suspend fun postStatus(device: DeviceState, conn: ConnectionState): Boolean {
        delay(80)
        Log.d("FakeStatusApi","postStatus: $device / $conn")
        return true
    }

    override suspend fun postLog(event: AlarmEvent): Boolean {
        delay(50)
        Log.d("FakeStatusApi","postLog: $event")
        return true
    }
}
