# main.py
from fastapi import FastAPI
from pydantic import BaseModel
from datetime import datetime

app = FastAPI()

class SensorPayload(BaseModel):
    device_id: str | None = None  # 여러 보드 쓸 거면
    value: int                    # 센서 값 (0~4095)
    led: int | None = None        # 옵션: LED 상태

latest_data: dict | None = None

@app.post("/sensor")
def receive_sensor(payload: SensorPayload):
    global latest_data
    latest_data = {
        "timestamp": datetime.now().isoformat(),
        "device_id": payload.device_id,
        "value": payload.value,
        "led": payload.led,
    }
    print("센서 수신:", latest_data)
    return {"ok": True}

@app.get("/sensor/latest")
def get_latest_sensor():
    return latest_data or {"message": "아직 데이터 없음"}
