# server-image.py
from argparse import Action
import uvicorn
import os
import argparse
import xml.etree.ElementTree as ET
import cv2
import re
import numpy as np
import ssl
import certifi
import websockets
import json
import base64
import asyncio
import google.auth
from google.auth.transport.requests import Request
from datetime import datetime
from fastapi import FastAPI, File, UploadFile, Form, WebSocket, WebSocketDisconnect
from typing import Optional, Dict, Any

from chat_agent import ChatAgent
from cu_agent import CUAgent

app = FastAPI()

# 세션 ID를 기반으로 ChatAgent 인스턴스를 저장할 딕셔너리
# 프로덕션에서는 Redis나 DB 사용
# 서버 재시작 시 초기화되는 임시 메모리 저장소
SESSION_STORE: Dict[str, ChatAgent] = {}
SCREENSHOT_SAVE_DIR = None
SCREENSHOT_RESOLUTION_SCALE = 0.5

def get_or_create_agent(session_id: str) -> ChatAgent:
    """세션 ID에 해당하는 에이전트를 반환하거나 새로 생성합니다."""
    if session_id not in SESSION_STORE:
        print(f"\n[Server] New session created: {session_id}")
        # 세션별로 독립적인 CUAgent와 ChatAgent를 생성
        cu_agent = CUAgent(
            model_name='gemini-2.5-computer-use-preview-10-2025',
            verbose=True
        )
        chat_agent = ChatAgent(
            cu_agent=cu_agent,
            model_name='gemini-2.5-flash',
            verbose=True
        )
        SESSION_STORE[session_id] = chat_agent
    
    return SESSION_STORE[session_id]

@app.post("/chat/query")
async def chat_query(
    query: str = Form(...),
    session_id: str = Form(...)
) -> Dict[str, Any]:
    """
    클라이언트의 모든 '초기 텍스트 쿼리'를 처리합니다.
    - 일상 채팅: 텍스트 응답 반환
    - 작업 요청: 스크린샷 요청 반환
    """
    print(f"\n[Server] /chat/query (Session: {session_id}): Query='{query}'")

    # 세션 ID에 맞는 에이전트를 가져오거나 생성
    chat_agent = get_or_create_agent(session_id)
    
    # ChatAgent에 쿼리 처리 요청
    response_data = chat_agent.process_query(query)

    print(f"[Server] Response: {response_data}")
    return response_data

@app.post("/chat/step")
async def chat_step(
    screenshot: UploadFile = File(...),
    activity: Optional[str] = Form(None),
    session_id: str = Form(...)
) -> Dict[str, Any]:
    """
    클라이언트가 서버의 요청에 따라 '스크린샷'을 전송하면,
    ChatAgent/CUAgent가 다음 액션 또는 최종 응답을 반환합니다.
    """
    print(f"\n[Server] /chat/step (Session: {session_id}): New screenshot received.")

    # 세션 ID로 해당 에이전트를 찾음
    chat_agent = SESSION_STORE.get(session_id)
    
    if not chat_agent:
        print(f"[Server] Error: No session found for ID {session_id}")
        return {
            "type": "ERROR", 
            "message": f"세션을 찾을 수 없습니다 (ID: {session_id}). /chat/query를 먼저 호출해주세요."
        }

    # Activity Logging
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")

    screenshot_bytes = await screenshot.read()
    marked_image_bytes, ui_description = generate_som_image(screenshot_bytes, activity, SCREENSHOT_RESOLUTION_SCALE)

    os.makedirs("captures", exist_ok=True)

    # xml 디버깅용 로그 파일
    if ui_description == "UI info not available":
        os.makedirs("xml_log", exist_ok=True)
        som_filename = f"xml_log/{session_id}_{timestamp}_som.txt"
        with open(som_filename, "w", encoding="utf-16") as f:
            f.write(f"{activity}")
            
    # 이미지 저장
    filename = f"captures/{session_id}_{timestamp}.jpg"
    with open(filename, "wb") as f:
        f.write(screenshot_bytes)

    # [추가] SoM 이미지 저장 (기존 방식과 동일하게 저장)
    som_filename = f"captures/{session_id}_{timestamp}_som.jpg"
    with open(som_filename, "wb") as f:
        f.write(marked_image_bytes)

    # ChatAgent에 작업 계속/시작 요청
    response_data = chat_agent.process_step(marked_image_bytes, ui_description or "unknown_activity")

    print(f"[Server] Response: {response_data}")
    return response_data

def generate_som_image(screenshot_bytes, activity_info: str, scale = 1.0):
    nparr = np.frombuffer(screenshot_bytes, np.uint8)
    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    marked_img = img.copy()

    try:
        root = ET.fromstring(activity_info)
    except Exception as e:
        print(f"XML Parsing Error: {e}")
        return screenshot_bytes, "UI info not available"
    
    ui_elements_desc = []
    index = 0

    for node in root.iter('node'):
        text = node.get('text', '').strip()
        content_desc = node.get('content-desc', '').strip()
    
        if text or content_desc:
            bounds = parse_bounds(node.get('bounds'), scale)
            left, top, right, bottom = bounds
                        
            # 너무 작은 요소(노이즈) 제외
            if (right - left) < 10 or (bottom - top) < 10:
                continue

            # 마킹 (사각형 + 숫자 인덱스)
            cv2.rectangle(marked_img, (left, top), (right, bottom), (0, 255, 0), 2) # 초록색 박스
            
            label = str(index)
            # 숫자 가독성을 위한 빨간색 배경 박스 추가
            (w, h), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
            cv2.rectangle(marked_img, (left, top - h - 5), (left + w, top), (0, 0, 255), -1)
            cv2.putText(marked_img, label, (left, top - 5), 
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)

            # 에이전트에게 보낼 텍스트 설명 리스트
            ui_elements_desc.append(f"""Index {index}: {{\"text\": \"{text}\", \"content_description\": \"{content_desc}\", \"bounds\": \"[{left}, {top}][{right}, {bottom}]\"}}""")
            index += 1

    _, buffer = cv2.imencode('.jpg', marked_img)
    print(f"ui_elements_desc: {"\n".join(ui_elements_desc)}")
    return buffer.tobytes(), "\n".join(ui_elements_desc)

def parse_bounds(bounds_str: str, scale=1.0):
    if not bounds_str:
        return [0, 0, 0, 0]
    pattern = r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]"
    match = re.search(pattern, bounds_str)
    if match:
        return [int(int(x) * scale) for x in match.groups()]
    else:
        return [0, 0, 0, 0]

def generate_access_token():
    """Retrieves an access token using Google Cloud default credentials."""
    try:
        creds, _ = google.auth.default()
        if not creds.valid:
            creds.refresh(Request())
        return creds.token
    except Exception as e:
        print(f"Error generating access token: {e}")
        print("Make sure you're logged in with: gcloud auth application-default login")
        return None

# Gemini Live API에서 사용할 도구(Tool) 정의
MOBILE_TOOL_DEFINITION = {
    "function_declarations": [
        {
            "name": "execute_mobile_task",
            "description": "사용자가 앱 실행, 검색, 설정 변경 등 모바일 기기 조작을 요청했을 때 호출합니다.",
            "parameters": {
                "type": "object",
                "properties": {
                    "task_description": {
                        "type": "string",
                        "description": "수행해야 할 작업의 구체적인 내용"
                    }
                },
                "required": ["task_description"]
            }
        }
    ]
}
PROJECT_ID = "studious-ethos-462600-n0"

@app.websocket("/ws/voice/{session_id}")
async def voice_agent_endpoint(websocket: WebSocket, session_id: str):
    """음성 인식을 통해 일상 대화와 Task 실행을 분기하는 엔드포인트"""
    await websocket.accept()
    chat_agent = get_or_create_agent(session_id)
    
    # Gemini Live API 연결 설정 (Vertex AI 기준)
    bearer_token = generate_access_token()
    location = "us-central1" # 환경에 맞게 수정
    service_url = f"wss://{location}-aiplatform.googleapis.com/ws/google.cloud.aiplatform.v1beta1.LlmBidiService/BidiGenerateContent"
    
    headers = {"Authorization": f"Bearer {bearer_token}"}
    ssl_context = ssl.create_default_context(cafile=certifi.where())

    try:
        async with websockets.connect(service_url, additional_headers=headers, ssl=ssl_context) as gemini_ws:
            print(f"[WS] Session {session_id}: Connected to Gemini Live API")

            # 초기 설정 전송 (도구 포함)
            setup_msg = {
                "setup": {
                    "model": f"projects/{PROJECT_ID}/locations/{location}/publishers/google/models/gemini-live-2.5-flash-native-audio",
                    "tools": [MOBILE_TOOL_DEFINITION]
                }
            }
            await gemini_ws.send(json.dumps(setup_msg))

            async def handle_client_to_gemini():
                """클라이언트 음성 데이터를 Gemini로 전달"""
                async for message in websocket.iter_bytes():
                    # message는 안드로이드에서 보낸 PCM 오디오 바이트
                    b64_data = base64.b64encode(message).decode('utf-8')

                    audio_frame = {
                        "realtime_input": {
                            "media_chunks": [{
                                "data": b64_data, # 예시 포맷
                                "mime_type": "audio/pcm"
                            }]
                        }
                    }
                    await gemini_ws.send(json.dumps(audio_frame))

            async def handle_gemini_to_client():
                """Gemini 응답 분석 및 Task 트리거"""
                async for response in gemini_ws:
                    data = json.loads(response)
                    print(data)
                    
                    # 1. Function Call 확인 (Task 실행 트리거)
                    # if "tool_call" in data:
                    #     call = data["tool_call"]["function_calls"][0]
                    #     if call["name"] == "execute_mobile_task":
                    #         task_msg = call["args"]["task_description"]
                    #         print(f"[WS] Task Triggered: {task_msg}")
                            
                    #         # 안드로이드에 알림: 녹음 중단 및 스크린샷 전송 요청
                    #         await websocket.send_json({
                    #             "type": "CONTROL",
                    #             "action": "START_TASK",
                    #             "message": "작업을 시작합니다. 잠시만 기다려주세요."
                    #         })
                            
                    #         chat_agent.process_query(task_msg)
                    #         continue

                    # 2. 일반 음성/텍스트 응답 전달
                    await websocket.send_text(response)

            await asyncio.gather(handle_client_to_gemini(), handle_gemini_to_client())

    except WebSocketDisconnect:
        print(f"[WS] Session {session_id}: Client disconnected")
    except Exception as e:
        print(f"[WS] Session {session_id}: Error Type - {type(e).__name__}")
        print(f"[WS] Session {session_id}: Error Detail - {e}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run the server with optional screenshot saving.")
    parser.add_argument("--save-dir", type=str, help="Directory to save received screenshots")
    parser.add_argument("--port", type=int, default=8000, help="Port to run the server on")
    parser.add_argument("-d","--debug", action='store_true')
    args = parser.parse_args()

    uvicorn.run(app, host="0.0.0.0", port=args.port)