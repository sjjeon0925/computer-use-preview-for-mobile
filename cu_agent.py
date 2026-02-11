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
# 4. "summary" : 매 step 마다 기록 요약 -> 단일 step만 히스토리에 남김

HISTORY_STRATEGY = "summary"  # <--- 여기를 수정해서 테스트하세요! ("none" | "sliding" | "abstract" | "summary")

KEEP_RECENT_TURNS = 1           # (공통) 생생하게 유지할 최근 턴 수 (스크린샷 포함)
SUMMARY_THRESHOLD = 2          # (공통) 관리가 시작될 최소 히스토리 길이
LOG_DIR = "logs"                # 로그 파일이 저장될 폴더
# ==============================================================================

ANDROID_SYSTEM_PROMPT = """
### Execution Rules (STRICT)
1. **NO COORDINATE LISTING:** NEVER list UI elements' coordinates like "{ 'point': ... }" in your reasoning.
2. **START WITH OPEN_APP:** The very first step of any task MUST be calling the `open_app` function to launch the target application. 
3. **BREVITY:** Keep reasoning concise and under 100 characters.
4. **ACT IMMEDIATELY:** If you see the target, call the function. Do not double-check or iterate options in text.
5. **AFC MODE:** You must trigger a function call. If you don't, the task fails.
6. **Caution on Termination:** When the task is nearly complete, terminate immediately without redundant confirmation steps.

You are an intelligent agent tasked with operating an Android phone to complete user instructions.

### Your Environment and Capabilities
1.  **Device View:** All interactions occur on a **mobile-sized screen** (normalized coordinates 0-1000).
2.  **Core Actions (Predefined):** You can perform basic UI interactions like **click_at**, **type_text_at**, **scroll_at**, **wait_5_seconds**, and **go_back**.
3.  **Advanced Actions (Custom):** You have access to specialized functions: open_app, go_home, long_press_at, scroll_to_text, swipe.
4.  **Browser Conventions:** Ignore browser conventions unless in a browser app.
"""
# 3.  **Advanced Actions (Custom):** You have access to specialized functions: open_app, go_home, long_press_at, scroll_to_text, swipe, set_device_setting, close_current_app, go_recent_apps.

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

# 벤치마크 실행시 동일한 환경 세팅을 위해 비활성화
# def set_device_setting(setting_name: str, value: Any) -> Dict[str, Any]:
#     """Changes a specific device setting (e.g., WIFI, Bluetooth)."""
#     return {"status": "requested_set_setting", "setting_name": setting_name, "value": value}

# def close_current_app() -> Dict[str, str]:
#     """Closes the currently active application."""
#     return {"status": "requested_close_app"}

# def go_recent_apps() -> Dict[str, str]:
#     """Navigates to the device's recent applications screen."""
#     return {"status": "requested_recent_apps"}

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
        # "set_device_setting",
        # "close_current_app",
        # "go_recent_apps",
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
        # model_name: str = 'gemini-2.5-computer-use-preview-10-2025',
        model_name: str = 'gemini-3-flash-preview',
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
        self.history_summary = ""
        self._instruction = ""

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
            # types.FunctionDeclaration.from_callable(client=self._client, callable=set_device_setting),
            # types.FunctionDeclaration.from_callable(client=self._client, callable=close_current_app),
            # types.FunctionDeclaration.from_callable(client=self._client, callable=go_recent_apps),
        ]

        self._generate_content_config = GenerateContentConfig(
            temperature=0.1,
            top_p=0.95,
            top_k=40,
            max_output_tokens=2048, 
            system_instruction=ANDROID_SYSTEM_PROMPT,
            thinking_config=types.ThinkingConfig(
                # thinking_level="minimal",
                thinking_budget=512
            ),
            tools=[
                types.Tool(
                    computer_use=types.ComputerUse(
                        environment=types.Environment.ENVIRONMENT_BROWSER,
                        excluded_predefined_functions=excluded_predefined_functions,
                    ),
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
                role_str = content.role if content.role else "UNKNOWN"
                role = role_str.upper()
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

        # 모든 텍스트 파트를 합쳐서 반환
        texts = [part.text for part in candidate.content.parts if part.text]
        return "\n".join(texts) if texts else None

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
                "args": dict(fc.args),
                "message": reasoning
            }
        
        # function call이 없는데 UI 나열이 감지되는 경우 TOKEN_OVERFLOW
        if reasoning:
            # 모델이 UI 객체를 나열하는 패턴이 발견되면 -> TOKEN_OVERFLOW 반환
            if "{'point':" in reasoning or "'label':" in reasoning or "box_2d" in reasoning:
                if self._verbose:
                    termcolor.cprint("[CUAgent] 🚨 FAIL: UI Listing Loop w/o Function Call (Token Overflow)", "red")
                
                return {
                    "type": "TOKEN_OVERFLOW", 
                    "message": "Model wasted tokens listing UI elements and failed to generate a Function Call.",
                    "raw_reasoning": reasoning
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
    def _manage_history(self, prev_action_name, action_args, current_screenshot_data):
        """
        [M3A Style History Management]
        히스토리 요약 및 관리를 수행합니다.
        M3A 방식의 'Before/After 비교 요약'을 위해 _generate_step_summary를 호출합니다.
        """
        
        # 1. M3A 스타일의 Step-wise 요약 생성 (Before vs After 비교)
        # 내부에서 self.last_screenshot_data를 사용하여 비교하고 리스트에 추가함
        self._generate_step_summary(
            prev_action_name=prev_action_name, 
            action_args=action_args, 
            current_screenshot_data=current_screenshot_data
        )

        # 2. (옵션) 디버깅용 로그
        if self._verbose:
            print(f"[CUAgent] History updated. Current turns: {len(self._contents)}")
    
    def _generate_step_summary(self, prev_action_name, action_args, current_screenshot_data):
        """
        1. Before/After 스크린샷 비교: 직전 화면(self.last_screenshot)과 현재 화면을 LLM에 전송.
        2. Step-wise 요약: 각 스텝별 요약을 리스트(self.step_history)에 누적 저장.
        3. History Injection: 누적된 요약 리스트를 합쳐서 모델 턴에 주입.
        """
        
        # 0. 히스토리 리스트 초기화 (없으면 생성)
        if not hasattr(self, 'step_history'):
            self.step_history = []
        
        # 1. Before 스크린샷 확보 (이전 턴의 스크린샷)
        # self.last_screenshot_data는 메서드 끝에서 갱신됨. 첫 턴에는 None.
        before_screenshot = getattr(self, 'last_screenshot_data', None)
        after_screenshot = current_screenshot_data
        
        # 첫 번째 턴이라서 Before 이미지가 없으면 요약 스킵 (다음 턴을 위해 현재 이미지만 저장)
        if before_screenshot is None:
            self.last_screenshot_data = current_screenshot_data
            return

        # ----------------------------------------------------------------------
        # 2. 요약 프롬프트 및 LLM 호출
        # ----------------------------------------------------------------------
        
        # M3A 프롬프트 스타일 참고 (Goal + Action + Comparison Request)
        args_str = ", ".join([f"{k}='{v}'" for k, v in action_args.items()])
        
        summary_prompt = f"""
        The user's goal is: {self._instruction}
        
        [Action Performed]: {prev_action_name} (Args: {args_str})
        
        I have provided two screenshots:
        1. Before the action was executed.
        2. After the action was executed.
        
        ### Assignment
        Compare the two screenshots and the action performed.
        Summarize this single step in 1-2 sentences.
        - Did the screen change as expected?
        - If the action failed or the screen didn't change, explicitly state it.
        """
        
        # [핵심] 텍스트 + 이미지 2장(Before, After) 구성
        summary_contents = [
            summary_prompt,
            types.Part.from_bytes(data=before_screenshot, mime_type="image/png"), # Before
            types.Part.from_bytes(data=after_screenshot, mime_type="image/png")   # After
        ]
        
        new_step_summary = ""
        
        try:
            # 별도의 요약 모델 호출 (생략 없이 구현)
            res = self._client.models.generate_content(
                model='gemini-2.0-flash',
                contents=summary_contents,
                config=types.GenerateContentConfig(
                    max_output_tokens=1024,
                )
            )
            
            if res.text:
                new_step_summary = res.text.strip()
            else:
                new_step_summary = f"Executed {prev_action_name}, but the summary model returned no text."
                
        except Exception as e:
            self._log_to_file(f"-> Summary Generation Failed: {e}")
            new_step_summary = f"Executed {prev_action_name} ({args_str}). (Summary generation failed)"

        # ----------------------------------------------------------------------
        # 3. 히스토리 리스트 업데이트
        # ----------------------------------------------------------------------
        
        # "Step N: 요약문" 형식으로 저장
        step_idx = len(self.step_history) + 1
        formatted_summary = f"Step {step_idx}: {new_step_summary}"
        self.step_history.append(formatted_summary)
        
        self._log_to_file(f"-> New Step Summary Added: {formatted_summary}")
        
        # 다음 비교를 위해 현재 스크린샷을 Before로 저장
        self.last_screenshot_data = current_screenshot_data

        # ----------------------------------------------------------------------
        # 4. 히스토리 재구성 (리스트 합쳐서 제공)
        # ----------------------------------------------------------------------
        
        # [Turn 0: User] 목표
        turn_0_user = Content(
            role="user", 
            parts=[Part(text=f"Original Goal: {self._instruction}")]
        )

        # [Turn 1: Model] (전체 히스토리 텍스트) + (원본 Function Call)
        
        # [핵심] 리스트를 합쳐서 전체 히스토리 텍스트 생성 (주석 처리 안 함)
        history_text = "\n".join(self.step_history)
        
        full_summary_text = f"### Execution History (Step-by-Step)\n{history_text}"
        
        # 기존 모델 턴(-2) 가져오기 (Thought Signature 보존)
        original_model_turn = self._contents[-2]
        
        # 요약 파트 생성
        summary_part = Part(text=full_summary_text)
        
        # [요약 파트 + 기존 파트] 결합
        new_model_parts = [summary_part]
        new_model_parts.extend(original_model_turn.parts)
        
        turn_1_model = Content(
            role="model",
            parts=new_model_parts
        )

        # [Turn 2: User] 관측 (기존 유지)
        turn_2_observation = self._contents[-1]

        # 재조립
        self._contents = [turn_0_user, turn_1_model, turn_2_observation]
        
        self._log_to_file(f"-> History rebuilt with Step-wise List. Length: {len(self._contents)}")
    # ==========================================================================

    def init_task(self, instruction: str, screenshot_data: Optional[bytes], url_or_activity: Optional[str]) -> Dict[str, Any]:
        """[서버] 새 작업을 시작합니다."""

        # 새로운 작업이 시작되면 새 로그 파일을 엽니다.
        self._start_new_log_file()

        # 이전 작업의 기록이 남지 않도록 리스트와 이미지를 비워줍니다.
        self.step_history = [] 
        self.last_screenshot_data = None
        self.history_summary = ""

        # 지시사항 저장
        self._instruction = instruction

        if self._verbose:
            print(f"[CUAgent] init_task: 새 작업 시작 (Instruction: {instruction})")
            
        parts = [Part(text=instruction)]
        if screenshot_data:
            # 첫 턴의 이미지를 Before 이미지로 쓰기 위해 저장해둡니다.
            self.last_screenshot_data = screenshot_data
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
        
        prev_action_name = previous_action.get('action', 'init_task')
        prev_action_args = previous_action.get('args', {})

        if self._verbose:
            print(f"[CUAgent] step: 이전 액션 '{prev_action_name}'의 결과 수신")

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
                            name=prev_action_name,
                            response=function_response_data
                        )
                    ),
                    Part(inline_data=types.Blob(mime_type="image/png", data=current_screenshot_data)),
                    Part(text=f"액션 수행 후 현재 화면 상태: {current_activity or 'unknown'}. 다음 행동 결정.")
                ]
            )
        )

        self._manage_history(
            prev_action_name=prev_action_name,
            action_args=prev_action_args,
            current_screenshot_data=current_screenshot_data
        )

        return self._run_and_parse_response()
