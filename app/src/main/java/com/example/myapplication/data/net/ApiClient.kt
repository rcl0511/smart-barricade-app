package com.example.myapplication.data.net

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {

    // ✅ 에뮬레이터에서 PC의 localhost:8000 접근할 때
    private const val BASE_URL = "https://android-qdfu.onrender.com/"

    // ⚠️ 진짜 핸드폰으로 테스트하면:
    // PC IP를 직접 써야 함 (예: "http://192.168.0.12:8000/")
    // private const val BASE_URL = "http://192.168.0.12:8000/"

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}