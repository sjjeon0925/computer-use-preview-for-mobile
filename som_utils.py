import xml.etree.ElementTree as ET
import cv2
import re
import json
import numpy as np

def generate_som_image(screenshot_bytes, activity_info: str, scale = 0.5):
    nparr = np.frombuffer(screenshot_bytes, np.uint8)
    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    marked_img = img.copy()

    # activity_info 끝에 붙어 있는 window_size JSON suffix 분리
    screen_w, screen_h = None, None
    try:
        json_start = activity_info.rfind('{"width"')
        if json_start != -1:
            window_json = json.loads(activity_info[json_start:])
            screen_w = int(window_json.get("width", 0))
            screen_h = int(window_json.get("height", 0))
            activity_info = activity_info[:json_start]  # XML 부분만 남김
    except Exception as e:
        print(f"[SOM] window_size 파싱 실패: {e}")

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
            if (right - left) < 5 or (bottom - top) < 5:
                continue

            # 마킹 (사각형 + 숫자 인덱스)
            cv2.rectangle(marked_img, (left, top), (right, bottom), (0, 255, 0), 2) # 초록색 박스
            
            label = str(index)
            # 숫자 가독성을 위한 빨간색 배경 박스 추가
            (w, h), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
            cv2.rectangle(marked_img, (left, top - h - 5), (left + w, top), (0, 0, 255), -1)
            cv2.putText(marked_img, label, (left, top - 5), 
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)

            # bounds를 0~1000 좌표계로 정규화 (모델의 click_at 좌표계와 일치)
            if screen_w and screen_h:
                n_left   = round(left   / (screen_w * scale) * 1000)
                n_top    = round(top    / (screen_h * scale) * 1000)
                n_right  = round(right  / (screen_w * scale) * 1000)
                n_bottom = round(bottom / (screen_h * scale) * 1000)
                bounds_str = f"[{n_left}, {n_top}][{n_right}, {n_bottom}]"
            else:
                bounds_str = f"[{left}, {top}][{right}, {bottom}]"

            # 에이전트에게 보낼 텍스트 설명 리스트 (bounds는 항상 0~1000 기준)
            ui_elements_desc.append(f"""Index {index}: {{\"text\": \"{text}\", \"content_description\": \"{content_desc}\", \"bounds(0-1000)\": \"{bounds_str}\"}}""")
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
