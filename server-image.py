import uvicorn
import os
import argparse
import json
import base64
import asyncio
from datetime import datetime
from fastapi import FastAPI, File, UploadFile, Form
from fastapi import WebSocket
from typing import Optional, Dict, Any

from chat_agent import ChatAgent
from cu_agent import CUAgent
from voice_agent import VoiceAgentManager
import som_utils as SOM


app = FastAPI()

# 세션 ID를 기반으로 에이전트 인스턴스를 저장하는 딕셔너리
# 프로덕션에서는 Redis나 DB 사용 권장 (서버 재시작 시 초기화됨)
SESSION_STORE: Dict[str, ChatAgent] = {}
VA_SESSION_STORE: Dict[str, VoiceAgentManager] = {}

SCREENSHOT_SAVE_DIR = "captures"


def get_or_create_chat_agent(session_id: str) -> ChatAgent:
    """세션 ID에 해당하는 ChatAgent를 반환하거나 새로 생성합니다."""
    if session_id not in SESSION_STORE:
        print(f"\n[Server] New chat session created: {session_id}")
        cu_agent = CUAgent(
            model_name="gemini-2.5-computer-use-preview-10-2025", verbose=True
        )
        chat_agent = ChatAgent(
            cu_agent=cu_agent, model_name="gemini-2.5-flash", verbose=True
        )
        SESSION_STORE[session_id] = chat_agent

    return SESSION_STORE[session_id]


def get_or_create_voice_agent(session_id: str) -> VoiceAgentManager:
    """세션 ID에 해당하는 VoiceAgentManager를 반환하거나 새로 생성합니다."""
    if session_id not in VA_SESSION_STORE:
        print(f"\n[Server] New voice session created: {session_id}")
        cu_agent = CUAgent(
            model_name="gemini-2.5-computer-use-preview-10-2025", verbose=True
        )
        voice_agent = VoiceAgentManager(cu_agent=cu_agent)
        VA_SESSION_STORE[session_id] = voice_agent

    return VA_SESSION_STORE[session_id]


@app.post("/chat/query")
async def chat_query(
    query: str = Form(...), session_id: str = Form(...)
) -> Dict[str, Any]:
    """
    텍스트 쿼리를 처리합니다.
    - 일상 채팅: 텍스트 응답 반환
    - 작업 요청: 스크린샷 요청 반환
    """
    print(f"\n[Server] /chat/query (Session: {session_id}): Query='{query}'")

    chat_agent = get_or_create_chat_agent(session_id)
    response_data = chat_agent.process_query(query)

    print(f"[Server] Response: {response_data}")
    return response_data


@app.post("/chat/step")
async def chat_step(
    screenshot: UploadFile = File(...),
    activity: Optional[str] = Form(None),
    session_id: str = Form(...),
    scale: float = Form(...),
) -> Dict[str, Any]:
    """
    클라이언트가 스크린샷을 전송하면 CUAgent가 다음 액션 또는 최종 응답을 반환합니다.
    VoiceAgent 세션에서 호출됩니다.
    """
    print(f"\n[Server] /chat/step (Session: {session_id}): New screenshot received.")

    voice_agent = VA_SESSION_STORE.get(session_id)
    if not voice_agent:
        print(f"[Server] Error: No voice session found for ID {session_id}")
        return {
            "type": "ERROR",
            "message": f"세션을 찾을 수 없습니다 (ID: {session_id}). WebSocket 연결 후 사용하세요.",
        }

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    screenshot_bytes = await screenshot.read()

    marked_image_bytes, ui_description = SOM.generate_som_image(
        screenshot_bytes, activity, scale
    )

    os.makedirs(SCREENSHOT_SAVE_DIR, exist_ok=True)

    # XML 파싱 실패 시 디버깅용 로그 저장
    if ui_description == "UI info not available":
        os.makedirs("xml_log", exist_ok=True)
        with open(
            f"xml_log/{session_id}_{timestamp}_som.txt", "w", encoding="utf-16"
        ) as f:
            f.write(f"{activity}")

    with open(f"{SCREENSHOT_SAVE_DIR}/{session_id}_{timestamp}.jpg", "wb") as f:
        f.write(screenshot_bytes)
    with open(f"{SCREENSHOT_SAVE_DIR}/{session_id}_{timestamp}_som.jpg", "wb") as f:
        f.write(marked_image_bytes)

    response_data = voice_agent.process_step(
        marked_image_bytes, ui_description or "unknown_activity"
    )

    print(f"[Server] Response: {response_data}")
    return response_data


@app.websocket("/ws/voice/{session_id}")
async def voice_agent_endpoint(websocket: WebSocket, session_id: str):
    """음성 인식을 통해 일상 대화와 Task 실행을 분기하는 엔드포인트"""
    await websocket.accept()

    voice_agent = get_or_create_voice_agent(session_id=session_id)
    await voice_agent.run(websocket)

    print(f"[WS] Session {session_id}: Client disconnected")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run the FastAPI server.")
    parser.add_argument(
        "--port", type=int, default=8000, help="Port to run the server on"
    )
    parser.add_argument("-d", "--debug", action="store_true")
    args = parser.parse_args()

    uvicorn.run(app, host="0.0.0.0", port=args.port)
