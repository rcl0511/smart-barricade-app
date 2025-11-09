package com.example.myapplication.ui.util

import android.Manifest
import android.os.Build
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts

class PermissionHelper(
    caller: ActivityResultCaller,
    private val onResult: (Boolean) -> Unit
) {
    companion object {
        var DEV_BYPASS = true // ★ 개발 단계 우회 스위치
    }

    private val request = caller.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        onResult(allGranted)
    }

    fun requestBlePermissions() {
        if (DEV_BYPASS) {
            onResult(true); return
        }
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            // 스캔 시 위치 권한 필요했던 시절 대응
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        request.launch(perms.toTypedArray())
    }
}
