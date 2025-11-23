# Smart Barricade Android App

실시간 군중 안전 모니터링 · 하중 센서 · LED 패널 제어  
ESP32 기반 센서 모듈과 Android 디바이스 간 BLE/WiFi 통신을 사용하여  
실시간 하중 데이터 수신, LED 제어, 구조 게이트 제어 기능을 제공합니다.

센서 데이터는 FastAPI 서버(`/sensor/latest`)로 업로드되며  
중앙 관리자 페이지에서 실시간 조회할 수 있습니다.


## System Architecture
System Architecture

```mermaid

flowchart TD

A[arduino (FSR / Load Cell)] --> B[ESP32-S3 센서 모듈]

B -->|BLE Notify| C[Android App<br>실시간 모니터링]
B -->|WiFi HTTP POST| D[FastAPI Server<br>/sensor/latest]

C -->|BLE Write| B
C -->|Gate OPEN/CLOSE| B

D -->|상태 조회<br>GET /sensor/latest| C

subgraph Android App
C
end

subgraph FastAPI Server
D
end
```
## 실시간 데이터 흐름

### ▶ ESP32 → Android (BLE Notify)
- 실시간 하중 데이터 (0~4095)
- 배터리 전압(V)
- 밀집도(추가 예정)
- 히트맵 좌표 (x, y normalized)

### ▶ Android → ESP32 (BLE Write)
- LED 색상 제어 (RED / YELLOW / GREEN)
- 구조 게이트 OPEN / CLOSE 명령

### ▶ ESP32 → FastAPI 서버 (WiFi HTTP POST)
```
POST https://android-qdfu.onrender.com/sensor
{
  "device_id": "A-10",
  "value": 235
}
```

---

### ▶ 서버 데이터 확인
- 최신 센서값  
  https://android-qdfu.onrender.com/sensor/latest

- FastAPI 문서  
  https://android-qdfu.onrender.com/docs

---

## 개발 시 유의사항

### 1. ESP32 코드 필수 수정
```cpp
const char* WIFI_SSID     = "YOUR_SSID";
const char* WIFI_PASSWORD = "YOUR_PASSWORD";
const char* SERVER_URL    = "http://your_server/sensor";

// MUST match Android app
#define SERVICE_UUID        "12345678-1234-1234-1234-1234567890ab"
#define CHARACTERISTIC_UUID "abcd1234-1234-5678-9999-abcdef123456"
```

### 2. 통신 딜레이 측정 팁
- BLE보다 WiFi가 더 안정적
- 센서값 → 서버 응답 시간 기반 RTT 측정
- 실시간 전송은 WiFi 통일 추천

### 3. Android 권한 (Android 12+)
- BLUETOOTH_SCAN  
- BLUETOOTH_CONNECT  
- ACCESS_FINE_LOCATION  

---

## 프로젝트 구조

```
app/
├── data/
│   ├── DeviceStatus.kt
│   ├── ConnectionInfo.kt
│   ├── Alert.kt
│   └── AlertsFactory.kt
│
├── ble/
│   ├── BleRepository.kt
│   ├── BleGattCallback.kt
│   └── BleScanner.kt
│
├── ui/
│   ├── MainActivity.kt
│   ├── BarricadeDetailActivity.kt
│   ├── adapters/
│   │     └── AlertAdapter.kt
│   └── components/
│         └── HeatSpotRenderer.kt
│
├── utils/
│   ├── PermissionManager.kt
│   ├── HttpClient.kt
│   └── Extensions.kt
│
└── res/
    ├── layout/
    ├── drawable/
    ├── values/
    └── mipmap/
```

---

## 서버 엔드포인트 요약

| Method | Endpoint        | 설명               |
|--------|------------------|--------------------|
| POST   | /sensor          | ESP32 센서값 업로드 |
| GET    | /sensor/latest   | 최신 센서값 조회     |
| GET    | /docs            | FastAPI 문서        |

---

## 빌드 및 실행 방법

1. Android Studio에서 프로젝트 열기  
2. ESP32 코드 업로드  
   - WiFi SSID/PW 수정  
   - UUID Android와 일치하도록 수정  
3. 앱 실행 후 BLE 장치 연결  
4. FastAPI 서버 데이터 확인  
   - https://android-qdfu.onrender.com/sensor/latest
