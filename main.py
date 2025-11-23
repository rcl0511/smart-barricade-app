# main.py
from fastapi import FastAPI
from pydantic import BaseModel
from datetime import datetime, timezone

app = FastAPI()

class SensorPayload(BaseModel):
    device_id: str | None = None
    value: int
    led: int | None = None

latest_data: dict | None = None

@app.post("/sensor")
def receive_sensor(payload: SensorPayload):
    global latest_data
    latest_data = {
        # 서버가 센서 데이터를 "받은 정확한 시간"
        "received_at": datetime.now(timezone.utc).isoformat(),  
        "device_id": payload.device_id,
        "value": payload.value,
        "led": payload.led,
    }
    print("센서 수신:", latest_data)
    return {"ok": True}

@app.get("/sensor/latest")
def get_latest_sensor():
    return latest_data or {"message": "아직 데이터 없음"}
