package com.example.myapplication.data.net

import retrofit2.Response
import retrofit2.http.GET

interface ApiService {

    @GET("sensor/latest")
    suspend fun getLatestSensor(): Response<SensorLatestResponse>
}