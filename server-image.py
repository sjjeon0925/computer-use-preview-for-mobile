# server-image.py
import uvicorn
import os
import argparse
import xml.etree.ElementTree as ET
import cv2
import re
import numpy as np
from datetime import datetime
from fastapi import FastAPI, File, UploadFile, Form
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

    screenshot_bytes = await screenshot.read()
    marked_image_bytes, ui_description = generate_som_image(screenshot_bytes, activity, SCREENSHOT_RESOLUTION_SCALE)

    # Activity Logging
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")

    os.makedirs("captures", exist_ok=True)
    
    # 기존 원본 저장 방식 유지
    orig_filename = f"captures/{session_id}_{timestamp}_orig.jpg"
    with open(orig_filename, "wb") as f:
        f.write(screenshot_bytes)
        
    # [추가] SoM 이미지 저장 (기존 방식과 동일하게 저장)
    som_filename = f"captures/{session_id}_{timestamp}_som.jpg"
    with open(som_filename, "wb") as f:
        f.write(marked_image_bytes)

    # ChatAgent에 작업 계속/시작 요청
    response_data = chat_agent.process_step(marked_image_bytes, ui_description or "unknown_activity")

    print(f"[Server] Response: {response_data}")
    return response_data

def generate_som_image(screenshot_bytes, activity_info, scale = 1.0):
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
        is_clickable = node.get('clickable', '').strip()
        is_editable = node.get('editable', '').strip()
    
        if text or content_desc:
            bounds = parse_bounds(node.get('bounds'), scale)
            left, top, right, bottom = bounds
            
            if is_clickable or is_editable:
                continue
            
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
            ui_elements_desc.append(f"""Index {index}: {{\"text\": \"{text}\", \"content_description\": \"{content_desc}\", \"bounds\": \"[{left}, {top}][{right}, {bottom}]\", \"editable\": \"{is_editable}\"}}""")
            index += 1

    _, buffer = cv2.imencode('.jpg', marked_img)
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

def debug_gen(screenshot_bytes, activity_info: str, scale: 1.0):
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
    print("\n".join(ui_elements_desc))
    return buffer.tobytes(), "\n".join(ui_elements_desc)

def do_debug(scale: float = 1.0):
    print(f"scale: {scale}")

    test_xml = ""
    with open("test.xml", "r") as f:
        test_xml = f.readlines()
        test_xml = "".join(test_xml)

    screenshot_bytes = None
    with open("test.jpg", "rb") as f:
        screenshot_bytes = f.read()

    marked_image_bytes, ui_description = debug_gen(screenshot_bytes, test_xml, scale)
    
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")

    som_filename = f"./test_{timestamp}_som.jpg"
    with open(som_filename, "wb") as f:
        f.write(marked_image_bytes)
    print(ui_description)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run the server with optional screenshot saving.")
    parser.add_argument("--save-dir", type=str, help="Directory to save received screenshots")
    parser.add_argument("--port", type=int, default=8000, help="Port to run the server on")
    parser.add_argument("-d","--debug", action='store_true')
    args = parser.parse_args()

    if (args.debug):
        do_debug(SCREENSHOT_RESOLUTION_SCALE)
    else:
        uvicorn.run(app, host="0.0.0.0", port=args.port)