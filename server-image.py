# server-image.py
from websockets import ClientConnection
from argparse import Action
import uvicorn
import os
import argparse
import ssl
import certifi
import websockets
import json
import base64
import asyncio
import google.auth
from google.auth.transport.requests import Request
from datetime import datetime
from fastapi import FastAPI, File, UploadFile, Form 
from fastapi import WebSocket, WebSocketDisconnect
from typing import Optional, Dict, Any

from chat_agent import ChatAgent
from cu_agent import CUAgent
import som_utils as SOM

app = FastAPI()

# 세션 ID를 기반으로 ChatAgent 인스턴스를 저장할 딕셔너리
# 프로덕션에서는 Redis나 DB 사용
# 서버 재시작 시 초기화되는 임시 메모리 저장소
SESSION_STORE: Dict[str, ChatAgent] = {}
SCREENSHOT_SAVE_DIR = "captures"
SCREENSHOT_RESOLUTION_SCALE = 0.5

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
MODEL_ID = "gemini-live-2.5-flash-native-audio"
LOCATION = "us-central1"

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
    session_id: str = Form(...),
    scale: float = Form(...)
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

    SCREENSHOT_RESOLUTION_SCALE = scale
    marked_image_bytes, ui_description = SOM.generate_som_image(screenshot_bytes, activity, SCREENSHOT_RESOLUTION_SCALE)

    os.makedirs(SCREENSHOT_SAVE_DIR, exist_ok=True)

    # xml 디버깅용 로그 파일
    if ui_description == "UI info not available":
        os.makedirs("xml_log", exist_ok=True)
        som_filename = f"xml_log/{session_id}_{timestamp}_som.txt"
        with open(som_filename, "w", encoding="utf-16") as f:
            f.write(f"{activity}")
            
    # 이미지 저장
    filename = f"{SCREENSHOT_SAVE_DIR}/{session_id}_{timestamp}.jpg"
    with open(filename, "wb") as f:
        f.write(screenshot_bytes)

    # [추가] SoM 이미지 저장 (기존 방식과 동일하게 저장)
    som_filename = f"{SCREENSHOT_SAVE_DIR}/{session_id}_{timestamp}_som.jpg"
    with open(som_filename, "wb") as f:
        f.write(marked_image_bytes)

    # ChatAgent에 작업 계속/시작 요청
    response_data = chat_agent.process_step(marked_image_bytes, ui_description or "unknown_activity")

    print(f"[Server] Response: {response_data}")
    return response_data

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

@app.websocket("/ws/voice/{session_id}")
async def voice_agent_endpoint(websocket: WebSocket, session_id: str):
    """음성 인식을 통해 일상 대화와 Task 실행을 분기하는 엔드포인트"""
    await websocket.accept()
    
    # Gemini Live API 연결 설정 (Vertex AI 기준)
    bearer_token = generate_access_token()
    
    service_url = f"wss://{LOCATION}-aiplatform.googleapis.com/ws/google.cloud.aiplatform.v1beta1.LlmBidiService/BidiGenerateContent"
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {bearer_token}"
        }
    ssl_context = ssl.create_default_context(cafile=certifi.where())

    try:
        async with websockets.connect(service_url, additional_headers=headers, ssl=ssl_context) as gemini_ws:
            print(f"[WS] Session {session_id}: Connected to Gemini Live API")

            # 초기 설정 전송 (도구 포함)
            setup_msg = {
                "setup": {
                    "model": f"projects/{PROJECT_ID}/locations/{LOCATION}/publishers/google/models/{MODEL_ID}",
                    "generation_config": {
                        "response_modalities": ["AUDIO"]
                    },
                    "tools": [MOBILE_TOOL_DEFINITION],
                    "input_audio_transcription": {},
                    "output_audio_transcription": {}
                }
            }
            await gemini_ws.send(json.dumps(setup_msg))

            # gemini 실행
            await asyncio.gather(
                handle_client_to_gemini(gemini_ws=gemini_ws, ws=websocket), 
                handle_gemini_to_client(gemini_ws=gemini_ws, ws=websocket, session_id=session_id))

    except WebSocketDisconnect:
        print(f"[WS] Session {session_id}: Client disconnected")
    except Exception as e:
        print(f"[WS] Session {session_id}: Error Type - {type(e).__name__}")
        print(f"[WS] Session {session_id}: Error Detail - {e}")
    finally:
        if 'gemini_ws' in locals() and gemini_ws.close_code is None:
            await gemini_ws.close()
        print(f"[WS] Session {session_id}: Resources cleaned up")
        
async def handle_client_to_gemini(gemini_ws: ClientConnection, ws: WebSocket):
    """
    클라이언트 메시지를 Gemini로 그대로 proxy
        - 음성: {"realtime_input": {"media_chunks": [{"data": "<base64>", "mime_type": "audio/pcm"}]}}
        - 텍스트: {"client_content": {"turns": [...], "turn_complete": true}}
    """
    async for message in ws.iter_text():
        await gemini_ws.send(message)

async def handle_gemini_to_client(gemini_ws: ClientConnection, ws: WebSocket, session_id: str):
    """
    Gemini 응답을 파싱하여 타입별로 클라이언트에 전달
    toolCall - chat_agent를 통해 task 실행
    """
    
    chat_agent = get_or_create_agent(session_id)

    async for response in gemini_ws:
        if isinstance(response, bytes):
            decoded_message = response.decode('utf-8')
        else:
            decoded_message = response

        try:
            data = json.loads(decoded_message)
        except json.JSONDecodeError:
            print(f"[WS] Non-JSON response: {decoded_message}")
            continue

        print(f"[WS] Raw: {data}")

        # ── 메시지 타입 파싱 (geminilive.js의 MultimodalLiveResponseMessage와 동일한 로직) ──

        server_content = data.get("serverContent", {})

        if data.get("setupComplete"):
            print("[WS] SETUP COMPLETE")
            await ws.send_json({"type": "SETUP_COMPLETE"})

        elif server_content.get("turnComplete"):
            print("[WS] TURN COMPLETE")
            await ws.send_json({"type": "TURN_COMPLETE"})

        elif server_content.get("interrupted"):
            print("[WS] INTERRUPTED")
            await ws.send_json({"type": "INTERRUPTED"})

        elif server_content.get("inputTranscription"):
            # 사용자 음성 → 텍스트 전사 (내가 한 말)
            transcription = server_content["inputTranscription"]
            text = transcription.get("text", "")
            finished = transcription.get("finished", False)
            print(f"[WS] INPUT TRANSCRIPTION: {text} (finished={finished})")
            await ws.send_json({
                "type": "INPUT_TRANSCRIPTION",
                "text": text,
                "finished": finished
            })

        elif server_content.get("outputTranscription"):
            # 에이전트 음성 → 텍스트 전사 (에이전트가 한 말)
            transcription = server_content["outputTranscription"]
            text = transcription.get("text", "")
            finished = transcription.get("finished", False)
            print(f"[WS] OUTPUT TRANSCRIPTION: {text} (finished={finished})")
            await ws.send_json({
                "type": "OUTPUT_TRANSCRIPTION",
                "text": text,
                "finished": finished
            })

        elif data.get("toolCall"):
            # Function Call 처리 (Task 실행 트리거)
            tool_call = data["toolCall"]
            print(f"[WS] TOOL CALL: {tool_call}")
            function_calls = tool_call.get("functionCalls", [])
            for call in function_calls:
                if call.get("name") == "execute_mobile_task":
                    task_msg = call["args"].get("task_description", "")
                    print(f"[WS] Task Triggered: {task_msg}")
                    await ws.send_json({
                        "type": "CONTROL",
                        "action": "START_TASK",
                        "message": "작업을 시작합니다. 잠시만 기다려주세요."
                    })
                    # 작업 실행
                    response_data_in_dict = chat_agent.process_query(task_msg)
                    response_text = response_data_in_dict["message"]
                    json_object = {
                        "client_content": {
                            "turns": [
                                {
                                    "role":"system",
                                    "parts": [
                                        { "text": f"{response_text}"}
                                    ]
                                }
                            ],
                            "turn_complete":"true"
                        }
                    }
                    gemini_ws.send_data(json_object)

        else:
            # 오디오 데이터 (inlineData) 또는 텍스트 파트
            parts = server_content.get("modelTurn", {}).get("parts", [])
            if parts and parts[0].get("inlineData"):
                audio_b64 = parts[0]["inlineData"]["data"]
                print("[WS] 🔊 AUDIO chunk")
                await ws.send_json({
                    "type": "AUDIO",
                    "data": audio_b64
                })
            elif parts and parts[0].get("text"):
                text = parts[0]["text"]
                print(f"[WS] 💬 TEXT: {text}")
                await ws.send_json({
                    "type": "TEXT",
                    "text": text
                })

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run the server with optional screenshot saving.")
    parser.add_argument("--save-dir", type=str, help="Directory to save received screenshots")
    parser.add_argument("--port", type=int, default=8000, help="Port to run the server on")
    parser.add_argument("-d","--debug", action='store_true')
    args = parser.parse_args()

    uvicorn.run(app, host="0.0.0.0", port=args.port)