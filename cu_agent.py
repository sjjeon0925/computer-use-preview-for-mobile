# Copyright 2025 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os
import time
import datetime
from typing import Literal, Optional, Union, Any, Dict

import termcolor
from dotenv import load_dotenv
from google import genai
from google.genai import types
from google.genai.types import (
    Content, 
    Part, 
    Candidate, 
    FunctionCall, 
    FunctionResponse, 
    GenerateContentConfig, 
    FinishReason, 
    Blob
)
from rich.console import Console
from rich.table import Table

# load environment variable in .env
load_dotenv()
console = Console()

# ==============================================================================
# [설정] 히스토리 관리 전략 및 로깅 설정
# ==============================================================================
# 1. "none"     : 히스토리 무한 누적 (요약 없음, 삭제 없음) -> 느려짐, 토큰 에러 가능성
# 2. "sliding"  : 오래된 턴 단순 삭제 (기억 상실) -> 가벼움, 문맥 끊김
# 3. "abstract" : 오래된 턴 요약 압축 (기억 보존) -> 토큰 절약 + 문맥 유지

HISTORY_STRATEGY = "abstract"  # <--- 여기를 수정해서 테스트하세요! ("none" | "sliding" | "abstract")

KEEP_RECENT_TURNS = 5           # (공통) 생생하게 유지할 최근 턴 수 (스크린샷 포함)
SUMMARY_THRESHOLD = 10          # (공통) 관리가 시작될 최소 히스토리 길이
LOG_DIR = "logs"                # 로그 파일이 저장될 폴더
# ==============================================================================

ANDROID_SYSTEM_PROMPT = """
You are an intelligent agent tasked with operating an Android phone to complete user instructions.

### Your Environment and Capabilities
1.  **Device View:** All interactions occur on a **mobile-sized screen** (normalized coordinates 0-1000).
2.  **Core Actions (Predefined):** You can perform basic UI interactions like **click_at**, **type_text_at**, **scroll_at**, **wait_5_seconds**, and **go_back**.
3.  **Advanced Actions (Custom):** You have access to the following specialized functions for high-level mobile operations:
    * `open_app(app_name)`: To launch any application (e.g., '카메라', '설정').
    * `go_home()`: To navigate directly to the Android home screen.
    * `long_press_at(x, y)`: For long-press interactions.
    * `scroll_to_text(text)`: To efficiently find and bring specific text into view.
    * `swipe(start_x, start_y, end_x, end_y)`: To perform drag/swipe gestures.
    * `set_device_setting(setting_name, value)`: To change device-level settings (e.g., Wi-Fi, Bluetooth).
    * `close_current_app()`: To close the foreground application.
    * `go_recent_apps()`: To access the recent applications screen.
4.  **Browser Conventions:** Ignore conventions related to web browsers (URLs, forward/backward buttons, document scrolling) unless the current action is explicitly within a dedicated **browser app** (like '크롬').

### Strategy Guidelines
* **Action Clarity:** Always state your **reasoning** before calling a function.
* **Efficiency:** Use the advanced custom functions (e.g., `open_app`, `scroll_to_text`) whenever they offer a clear efficiency advantage over basic actions (e.g., multiple taps or scrolls).
"""

FunctionResponseT = Dict[str, Any]

def open_app(app_name: str, intent: Optional[str] = None) -> Dict[str, Any]:
    """Opens an app by name."""
    return {"status": "requested_open", "app_name": app_name, "intent": intent}

def long_press_at(x: int, y: int) -> Dict[str, int]:
    """Long-press at a specific screen coordinate."""
    return {"x": x, "y": y}

def go_home() -> Dict[str, str]:
    """Navigates to the device home screen."""
    return {"status": "home_requested"}

def scroll_to_text(text: str) -> Dict[str, Any]:
    """Scrolls the current view until the specified text is visible."""
    return {"status": "requested_scroll_to_text", "text": text}

def swipe(start_x: int, start_y: int, end_x: int, end_y: int, duration: float = 0.5) -> Dict[str, Any]:
    """Performs a swipe/drag gesture between two normalized coordinates."""
    return {"status": "requested_swipe", "start_x": start_x, "start_y": start_y, "end_x": end_x, "end_y": end_y, "duration": duration}

def set_device_setting(setting_name: str, value: Any) -> Dict[str, Any]:
    """Changes a specific device setting (e.g., WIFI, Bluetooth)."""
    return {"status": "requested_set_setting", "setting_name": setting_name, "value": value}

def close_current_app() -> Dict[str, str]:
    """Closes the currently active application."""
    return {"status": "requested_close_app"}

def go_recent_apps() -> Dict[str, str]:
    """Navigates to the device's recent applications screen."""
    return {"status": "requested_recent_apps"}

class CUAgent:
    PREDEFINED_COMPUTER_USE_FUNCTIONS = [
        "click_at",
        "type_text_at",
        "scroll_at",
        "wait_5_seconds",
        "go_back",
        "open_app", 
        "long_press_at",
        "go_home",
        "scroll_to_text",
        "swipe",
        "set_device_setting",
        "close_current_app",
        "go_recent_apps",
    ]
    EXCLUDED_PREDEFINED_FUNCTIONS = [
        "open_web_browser",
        "hover_at",
        "scroll_document",
        "go_forward",
        "navigate",
        "key_combination",
        "drag_and_drop",
        "search",
    ]

    def __init__(
        self,
        model_name: str = 'gemini-2.5-computer-use-preview-10-2025',
        verbose: bool = True,
    ):
        self._model_name = model_name
        self._verbose = verbose
        self._client = genai.Client(
            api_key=os.environ.get("GEMINI_API_KEY"),
            vertexai=os.environ.get("USE_VERTEXAI", "0").lower() in ["true", "1"],
            project=os.environ.get("VERTEXAI_PROJECT"),
            location=os.environ.get("VERTEXAI_LOCATION"),
        )
        self._contents: list[Content] = [] # 대화 히스토리

        self._current_log_file = None # 현재 세션의 로그 파일 경로

        # 로그 디렉토리 생성
        if not os.path.exists(LOG_DIR):
            os.makedirs(LOG_DIR)

        # Exclude any predefined functions here.
        excluded_predefined_functions = self.EXCLUDED_PREDEFINED_FUNCTIONS

        # Add your own custom functions here.
        custom_functions = [
            types.FunctionDeclaration.from_callable(client=self._client, callable=open_app),
            types.FunctionDeclaration.from_callable(client=self._client, callable=long_press_at),
            types.FunctionDeclaration.from_callable(client=self._client, callable=go_home),
            types.FunctionDeclaration.from_callable(client=self._client, callable=scroll_to_text),
            types.FunctionDeclaration.from_callable(client=self._client, callable=swipe),
            types.FunctionDeclaration.from_callable(client=self._client, callable=set_device_setting),
            types.FunctionDeclaration.from_callable(client=self._client, callable=close_current_app),
            types.FunctionDeclaration.from_callable(client=self._client, callable=go_recent_apps),
        ]

        self._generate_content_config = GenerateContentConfig(
            temperature=1,
            top_p=0.95,
            top_k=40,
            max_output_tokens=8192,
            system_instruction=ANDROID_SYSTEM_PROMPT,
            tools=[
                types.Tool(
                    computer_use=types.ComputerUse(
                        environment=types.Environment.ENVIRONMENT_BROWSER,
                        excluded_predefined_functions=excluded_predefined_functions,
                    ),
                ),
                # AFC 메시지 해결 필요
                types.Tool(
                    function_declarations=custom_functions
                )
            ],
        )
    # --------------------------------------------------------------------------
    # [Log Helper] 로그 기록 및 히스토리 덤프 함수
    # --------------------------------------------------------------------------
    def _start_new_log_file(self):
        """새로운 작업(Task)이 시작될 때마다 고유한 로그 파일을 생성합니다."""
        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"log_{HISTORY_STRATEGY}_{timestamp}.txt"
        self._current_log_file = os.path.join(LOG_DIR, filename)
        
        with open(self._current_log_file, "w", encoding="utf-8") as f:
            f.write(f"=== New Session Started ===\n")
            f.write(f"Strategy: {HISTORY_STRATEGY}\n")
            f.write(f"Time: {timestamp}\n")
            f.write("="*50 + "\n")
        
        if self._verbose:
            print(f"[CUAgent] Logging to file: {self._current_log_file}")

    def _log_to_file(self, message: str):
        """[로그 추가] 로그 파일에 메시지를 기록하는 헬퍼 함수"""
        if not self._current_log_file:
            return
        
        # 파일에 덧붙이기 모드(a)로 기록
        with open(self._current_log_file, "a", encoding="utf-8") as f:
            timestamp = datetime.datetime.now().strftime("%H:%M:%S")
            f.write(f"[{timestamp}] {message}\n")

    def _log_full_history_state(self, step_name: str):
        """현재 self._contents의 모든 내용을 텍스트로 풀어 로그 파일에 기록합니다."""
        if not self._current_log_file:
            return

        with open(self._current_log_file, "a", encoding="utf-8") as f:
            f.write(f"\n\n>>> [Step: {step_name}] Current History State (Length: {len(self._contents)}) <<<\n")
            f.write("-" * 50 + "\n")
            
            for i, content in enumerate(self._contents):
                role = content.role.upper()
                f.write(f"[{i}] ROLE: {role}\n")
                
                if not content.parts:
                    f.write("    (Empty Content)\n")
                    continue

                for part in content.parts:
                    # 1. 텍스트 출력
                    if part.text:
                        # 줄바꿈이 많을 수 있으니 들여쓰기 처리
                        text_content = part.text.replace('\n', '\n    ')
                        f.write(f"    TEXT: {text_content}\n")
                    
                    # 2. 이미지 개수 출력 (inline_data)
                    if part.inline_data:
                        f.write(f"    [IMAGE DATA: {part.inline_data.mime_type}]\n")

                    # 3. 함수 호출 출력
                    if part.function_call:
                        f.write(f"    FUNCTION CALL: {part.function_call.name}({part.function_call.args})\n")

                    # 4. 함수 응답 출력 (응답 내 이미지 확인)
                    if part.function_response:
                        f.write(f"    FUNCTION RESPONSE: {part.function_response.name}\n")
                        f.write(f"      -> Response Payload: {part.function_response.response}\n")
                        # (참고: FunctionResponse 내부는 구조상 이미지 파트가 별도로 존재할 수 있음, 여기선 Payload만 표기)

            f.write("-" * 50 + "\n")
    # --------------------------------------------------------------------------

    def get_model_response(
        self, max_retries=5, base_delay_s=1
    ) -> types.GenerateContentResponse:
        for attempt in range(max_retries):
            try:
                response = self._client.models.generate_content(
                    model=self._model_name,
                    contents=self._contents,
                    config=self._generate_content_config,
                )
                return response  # Return response on success
            except Exception as e:
                print(e)
                if attempt < max_retries - 1:
                    delay = base_delay_s * (2**attempt)
                    message = (
                        f"Generating content failed on attempt {attempt + 1}. "
                        f"Retrying in {delay} seconds...\n"
                    )
                    termcolor.cprint(
                        message,
                        color="yellow",
                    )
                    time.sleep(delay)
                else:
                    termcolor.cprint(
                        f"Generating content failed after {max_retries} attempts.\n",
                        color="red",
                    )
                    raise

    def get_text(self, candidate: Candidate) -> Optional[str]:
        """Extracts the text from the candidate."""
        if not candidate.content or not candidate.content.parts:
            return None
        text = []
        for part in candidate.content.parts:
            if part.text:
                text.append(part.text)
        return " ".join(text) or None

    def extract_function_calls(self, candidate: Candidate) -> list[types.FunctionCall]:
        """Extracts the function call from the candidate."""
        if not candidate.content or not candidate.content.parts:
            return []
        ret = []
        for part in candidate.content.parts:
            if part.function_call:
                ret.append(part.function_call)
        return ret
    
    def _run_and_parse_response(self) -> Literal["COMPLETE", "CONTINUE"]:
        """[CUAgent 내부] LLM을 호출하고 응답을 파싱합니다."""
        if self._verbose:
            print("[CUAgent] Gemini Thinking...")
        try:
            response = self.get_model_response()
        except Exception as e:
            return {"type": "ERROR", "message": f"Model generation error: {e}"}
        
        if not response.candidates:
            return {"type": "ERROR", "message": "No response from model."}

        # Extract the text and function call from the response.
        candidate = response.candidates[0]
        # Append the model turn to conversation history.
        if candidate.content:
            self._contents.append(candidate.content)

        reasoning = self.get_text(candidate)
        function_calls = self.extract_function_calls(candidate)

        # 모델 응답 후 히스토리 상태 기록
        self._log_full_history_state("After Model Response")

        if function_calls:
            fc = function_calls[0]
            if self._verbose:
                function_call_strs = []
                function_call_str = f"Name: {fc.name}"
                if fc.args:
                    function_call_str += f"\nArgs:"
                    for key, value in fc.args.items():
                        function_call_str += f"\n  {key}: {value}"
                function_call_strs.append(function_call_str)

                table = Table(expand=True)
                table.add_column(
                    "Gemini Computer Use Reasoning", header_style="magenta", ratio=1
                )
                table.add_column("Function Call(s)", header_style="cyan", ratio=1)
                table.add_row(reasoning, "\n".join(function_call_strs))
                console.print(table)
                print()
            
            return {
                "type": "ACTION",
                "action": fc.name,
                "args": dict(fc.args)
            }
        
        # Function Call이 없는 경우 (Reasoning = 최종 응답)
        if reasoning:
            if self._verbose:
                print(f"[CUAgent] Final Response: {reasoning}")
            return {
                "type": "RESPONSE",
                "message": reasoning
            }

        return {"type": "ERROR", "message": "CUAgent: Unknown model response type."}
    
    # ==========================================================================
    # [History Management] 전략 구현
    # ==========================================================================
    def _manage_history(self):
        """설정된 전략에 따라 히스토리를 정리합니다."""
        current_len = len(self._contents)
        
        if current_len <= SUMMARY_THRESHOLD:
            return

        if HISTORY_STRATEGY == "none":
            # 아무것도 안 함
            pass

        elif HISTORY_STRATEGY == "sliding":
            self._prune_sliding_window()

        elif HISTORY_STRATEGY == "abstract":
            self._abstract_history()

        # 정리 후 상태 기록
        self._log_full_history_state(f"After Management ({HISTORY_STRATEGY})")

    def _prune_sliding_window(self):
        """[Sliding Window] 오래된 턴 삭제"""
        start_index = 1
        end_index = len(self._contents) - KEEP_RECENT_TURNS

        if end_index < len(self._contents) and self._contents[end_index].role == "user":
            end_index -= 1
        
        if end_index > start_index:
            self._contents = [self._contents[0]] + self._contents[end_index:]
            if self._verbose:
                print(f"[CUAgent] Sliding Window: Removed {end_index - start_index} turns.")

    def _abstract_history(self):
        """[Abstract] 오래된 턴을 요약합니다. (수정됨: Call-Response 짝 유지)"""
        # 요약 시작 지점 (0번은 시스템 프롬프트/첫 지시이므로 유지)
        start_index = 1
        # 요약 끝 지점 (최근 N개는 남김)
        end_index = len(self._contents) - KEEP_RECENT_TURNS
        
        # [중요 수정 1] 자르는 위치가 'User(응답)'라면, 그 짝인 'Model(호출)'도 같이 남겨야 함
        # 즉, 'Keep' 영역의 시작은 반드시 'Model' 턴이어야 함 (User 응답만 남으면 에러 발생)
        if end_index < len(self._contents) and self._contents[end_index].role == "user":
            end_index -= 1

        turns_to_summarize = self._contents[start_index:end_index]
        if not turns_to_summarize:
            return

        # 요약을 위한 텍스트 추출
        history_text_buffer = []
        for content in turns_to_summarize:
            role = content.role
            texts = [p.text for p in content.parts if p.text]
            for p in content.parts:
                if p.function_call:
                    texts.append(f"[Call: {p.function_call.name}]")
                if p.function_response:
                    texts.append(f"[Result: {p.function_response.name}]")
            
            history_text_buffer.append(f"{role}: {' '.join(texts)}")

        full_history_text = "\n".join(history_text_buffer)
        
        summary_prompt = f"""
        Below is the log of a mobile agent's actions. 
        Summarize "what app was opened, what actions were taken, and what state it reached" in 3 sentences.
        
        [Logs]
        {full_history_text}
        """

        try:
            summary_response = self._client.models.generate_content(
                model='gemini-2.5-flash',
                contents=summary_prompt
            )
            summary_text = summary_response.text
            self._log_to_file(f"-> Generated Summary: {summary_text}")

            # [중요 수정 2] 요약문의 역할을 'user'로 변경하여 대화 흐름(User->Model) 유지
            summary_content = Content(
                role="user",
                parts=[Part(text=f"*** Previous Log Summary ***\n{summary_text}")]
            )
            
            self._contents = [self._contents[0]] + [summary_content] + self._contents[end_index:]
            self._log_to_file(f"-> History Condensed. New Length: {len(self._contents)}")

        except Exception as e:
            self._log_to_file(f"-> Summary Failed: {e}")
    # ==========================================================================

    def init_task(self, instruction: str, screenshot_data: Optional[bytes], url_or_activity: Optional[str]) -> Dict[str, Any]:
        """[서버] 새 작업을 시작합니다."""

        # [중요] 새로운 작업이 시작되면 새 로그 파일을 엽니다.
        self._start_new_log_file()

        if self._verbose:
            print(f"[CUAgent] init_task: 새 작업 시작 (Instruction: {instruction})")
            
        parts = [Part(text=instruction)]
        if screenshot_data:
            parts.append(Part(inline_data=types.Blob(mime_type="image/png", data=screenshot_data)))
            parts.append(Part(text=f"현재 화면 상태: {url_or_activity or 'unknown'}"))
        else:
            parts.append(Part(text="현재 화면 정보 없음."))
            
        self._contents = [Content(role="user", parts=parts)]

        # 초기 상태 기록
        self._log_full_history_state("Init Task")

        return self._run_and_parse_response()

    def step(self, previous_action: Dict[str, Any], current_screenshot_data: bytes, current_activity: Optional[str]) -> Dict[str, Any]:
        """[서버] 이전 액션의 결과(새 스크린샷)를 받아 다음 추론을 수행합니다."""
        if self._verbose:
            print(f"[CUAgent] step: 이전 액션 '{previous_action['action']}'의 결과 수신")

        function_response_data = {
            "result": "Action executed by client successfully.",
            "url": current_activity or "unknown_activity"
        }

        # 만약 이전 요청(args)에 safety_decision이 있었다면, 
        # 응답에도 safety_acknowledgement를 포함시켜야 합니다
        if "args" in previous_action and "safety_decision" in previous_action["args"]:
            function_response_data["safety_acknowledgement"] = True 
            
            if self._verbose:
                print("[CUAgent] Safety Decision 확인 완료 (safety_acknowledgement=True 전송)")
        
        self._contents.append(
            Content(
                role="user",
                parts=[
                    Part(
                        function_response=FunctionResponse(
                            name=previous_action['action'],
                            response=function_response_data
                        )
                    ),
                    Part(inline_data=types.Blob(mime_type="image/png", data=current_screenshot_data)),
                    Part(text=f"액션 수행 후 현재 화면 상태: {current_activity or 'unknown'}. 다음 행동 결정.")
                ]
            )
        )

        self._manage_history()

        return self._run_and_parse_response()
