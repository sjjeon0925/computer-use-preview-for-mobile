import asyncio
import base64
import inspect
import json
from typing import Optional, Dict, Any, AsyncGenerator

from fastapi import WebSocket, WebSocketDisconnect
from google import genai
from google.genai import types
from google.genai.types import LiveConnectConfig, Content, Part
from dotenv import load_dotenv

from cu_agent import CUAgent
import os

load_dotenv()


# Live API용 함수 선언
def execute_mobile_task(task_description: str) -> dict:
    """
    사용자가 앱 실행, 검색, 설정 변경 등 모바일 기기 조작을 요청했을 때 호출합니다.
    parameters:
        task_description: 수행해야 할 작업의 구체적인 내용
    """
    print("[VoiceAgent task] execute_mobile_task")
    return {"status": "TASK_RECEIVED", "task": task_description}


def abort_mobile_task() -> dict:
    """
    사용자가 현재 진행 중인 모바일 작업을 중단하거나 취소하고 싶을 때 호출합니다.
    작업 진행 중 사용자가 멈춰, 취소, 중단, 그만 등의 표현을 사용하면 즉시 호출하십시오.
    """
    print("[VoiceAgent task] abort_mobile_task")
    return {"status": "TASK_ABORTED"}


class VoiceAgentManager:
    """
    참고 프로젝트(GoogleCloudPlatform Gemini Live API demo) 구조 기반:

    _start_session() — async generator
      ├─ send_audio task:   audio_input_queue → session.send_realtime_input(audio)
      ├─ send_text  task:   text_input_queue  → session.send_realtime_input(text)
      ├─ receive_loop task: session.receive() → event_queue (+ audio는 callback으로 직행)
      └─ 메인 루프:         event_queue → yield event

    run() — WebSocket 진입점
      ├─ receive_from_client task: ws → queue에 적재
      └─ async for event in _start_session() → ws.send_json(event)
    """

    PREDEFINED_USER_APP = [
        "Chrome",
        "메세지",
        "캘린더",
        "지도",
        "카메라",
        "녹음",
        "갤러리",
        "브라우저",
        "설정",
    ]

    def __init__(self, cu_agent: CUAgent, verbose: bool = True):
        self._cu_agent = cu_agent
        self._verbose = verbose

        # CUAgent 상태 변수
        self._cu_task_in_progress: bool = False
        self._last_cu_action_json: Optional[Dict[str, Any]] = None
        self._pending_instruction: Optional[str] = None
        self._gemini_session = None
        self._loop = None

        project = os.environ.get("GOOGLE_CLOUD_PROJECT")
        location = os.environ.get("GOOGLE_CLOUD_LOCATION")
        self._model_id = (
            f"projects/{project}/locations/{location}"
            f"/publishers/google/models/gemini-live-2.5-flash-native-audio"
        )

        if self._verbose:
            print(f"[VoiceAgent] project={project}, location={location}")

        cu_abilities = (
            ", ".join(self._cu_agent.PREDEFINED_COMPUTER_USE_FUNCTIONS) + " 등"
        )
        user_app_list = ", ".join(self.PREDEFINED_USER_APP) + " 등"

        self._system_instruction = "\n".join(
            [
                "당신은 사용자의 일상 대화를 처리하는 친절하고 유능한 AI 비서입니다.",
                "사용자의 의도를 파악하여 앱 조작이 필요한지 여부를 판단하는 것이 핵심 임무입니다.",
                "당신이 직접 휴대폰을 조작하는 것이 아니고, CU Agent에게 구체적인 앱 조작 명령을 전달해야 합니다.",
                "CU Agent는 휴대폰 앱 조작을 담당합니다.",
                f"휴대폰에는 {user_app_list}과 같은 기본 앱들이 설치되어 있습니다.",
                "사용자가 휴대폰 앱을 조작하도록 요청하면 지체 없이 execute_mobile_task 함수를 호출하십시오.",
                f"execute_mobile_task 함수는 CU Agent에게 작업을 토스합니다. CU Agent는 {cu_abilities}과 같은 다양한 UI 조작을 수행할 수 있습니다.",
                "execute_mobile_task 결과를 받으면 사용자에게 친절한 최종 요약 응답을 제공하십시오.",
                "작업이 진행 중일 때 사용자가 '멈춰', '취소', '중단', '그만' 등의 표현을 사용하면 abort_mobile_task 함수를 즉시 호출하십시오.",
            ]
        )

        self._client = genai.Client(vertexai=True, project=project, location=location)
        self._config = LiveConnectConfig(
            response_modalities=["AUDIO"],
            tools=[execute_mobile_task, abort_mobile_task],
            system_instruction=self._system_instruction,
            input_audio_transcription={},
            output_audio_transcription={},
        )

    def _reset_task_state(self):
        """CU 작업 상태를 초기화합니다."""
        self._cu_task_in_progress = False
        self._last_cu_action_json = None
        self._pending_instruction = None

    # = Gemini 세션 관리 (async generator) =========
    async def _start_session(
        self,
        audio_input_queue: asyncio.Queue,
        text_input_queue: asyncio.Queue,
        audio_output_callback,
    ) -> AsyncGenerator[Dict[str, Any], None]:
        """
        Gemini Live API 세션을 열고 이벤트를 yield합니다.
        - 오디오 출력: audio_output_callback으로 즉시 처리 (event_queue 우회)
        - 나머지 이벤트: event_queue → yield
        """
        async with self._client.aio.live.connect(
            model=self._model_id, config=self._config
        ) as session:
            self._gemini_session = session
            self._loop = asyncio.get_running_loop()

            # = sender tasks ===================
            async def send_audio():
                try:
                    while True:
                        chunk: bytes = await audio_input_queue.get()
                        await session.send_realtime_input(
                            media=types.Blob(
                                data=chunk, mime_type="audio/pcm;rate=16000"
                            )
                        )
                except asyncio.CancelledError:
                    pass

            async def send_text():
                try:
                    while True:
                        text: str = await text_input_queue.get()
                        await session.send_realtime_input(text=text)
                except asyncio.CancelledError:
                    pass

            # = receiver =====================
            event_queue: asyncio.Queue = asyncio.Queue()

            async def receive_loop():
                try:
                    while True:
                        async for response in session.receive():
                            server_content = response.server_content
                            tool_call = response.tool_call

                            # Setup 완료
                            if response.setup_complete:
                                await event_queue.put({"type": "SETUP_COMPLETE"})

                            if server_content:
                                # 오디오 — callback으로 즉시 전달 (event_queue 우회)
                                if server_content.model_turn:
                                    for part in server_content.model_turn.parts:
                                        if part.inline_data:
                                            if inspect.iscoroutinefunction(
                                                audio_output_callback
                                            ):
                                                await audio_output_callback(
                                                    part.inline_data.data
                                                )
                                            else:
                                                audio_output_callback(
                                                    part.inline_data.data
                                                )

                                # 입력 전사
                                if (
                                    server_content.input_transcription
                                    and server_content.input_transcription.text
                                ):
                                    await event_queue.put(
                                        {
                                            "type": "INPUT_TRANSCRIPTION",
                                            "text": server_content.input_transcription.text,
                                            "finished": getattr(
                                                server_content.input_transcription,
                                                "finished",
                                                False,
                                            ),
                                        }
                                    )

                                # 출력 전사
                                if (
                                    server_content.output_transcription
                                    and server_content.output_transcription.text
                                ):
                                    await event_queue.put(
                                        {
                                            "type": "OUTPUT_TRANSCRIPTION",
                                            "text": server_content.output_transcription.text,
                                            "finished": getattr(
                                                server_content.output_transcription,
                                                "finished",
                                                False,
                                            ),
                                        }
                                    )

                                # Turn 완료
                                if server_content.turn_complete:
                                    await event_queue.put({"type": "TURN_COMPLETE"})

                                # 인터럽트
                                if server_content.interrupted:
                                    await event_queue.put({"type": "INTERRUPTED"})

                            # Function Call
                            if tool_call:
                                function_responses = []
                                for fc in tool_call.function_calls:
                                    if fc.name == "execute_mobile_task":
                                        task_desc = fc.args.get("task_description", "")
                                        # 이미 task 진행 중이면 중복 호출 무시
                                        if (
                                            self._pending_instruction
                                            or self._cu_task_in_progress
                                        ):
                                            if self._verbose:
                                                print(
                                                    f"[VoiceAgent] Task already in progress, ignoring duplicate call"
                                                )
                                            function_responses.append(
                                                types.FunctionResponse(
                                                    name=fc.name,
                                                    id=fc.id,
                                                    response={
                                                        "status": "TASK_ALREADY_IN_PROGRESS"
                                                    },
                                                )
                                            )
                                            continue
                                        if self._verbose:
                                            print(
                                                f"[VoiceAgent] Task Triggered: {task_desc}"
                                            )
                                        self._pending_instruction = task_desc
                                        await event_queue.put(
                                            {
                                                "type": "CONTROL",
                                                "action": "START_TASK",
                                                "message": task_desc,
                                            }
                                        )
                                        function_responses.append(
                                            types.FunctionResponse(
                                                name=fc.name,
                                                id=fc.id,
                                                response={
                                                    "status": "SCREENSHOT_REQUESTED"
                                                },
                                            )
                                        )

                                    elif fc.name == "abort_mobile_task":
                                        if self._verbose:
                                            print(f"[VoiceAgent] Task Aborted by user")
                                        self._reset_task_state()
                                        await event_queue.put(
                                            {
                                                "type": "CONTROL",
                                                "action": "ABORT_TASK",
                                                "message": "작업이 중단되었습니다.",
                                            }
                                        )
                                        function_responses.append(
                                            types.FunctionResponse(
                                                name=fc.name,
                                                id=fc.id,
                                                response={"status": "TASK_ABORTED"},
                                            )
                                        )

                                if function_responses:
                                    await session.send_tool_response(
                                        function_responses=function_responses
                                    )

                except Exception as e:
                    await event_queue.put({"type": "error", "error": str(e)})
                finally:
                    await event_queue.put(None)  # sentinel: 세션 종료 신호

            # = 태스크 시작 ====================
            send_audio_task = asyncio.create_task(send_audio())
            send_text_task = asyncio.create_task(send_text())
            receive_task = asyncio.create_task(receive_loop())

            try:
                while True:
                    event = await event_queue.get()
                    if event is None:  # sentinel
                        break
                    if event.get("type") == "error":
                        if self._verbose:
                            print(f"[VoiceAgent] receive_loop 에러: {event['error']}")
                        break
                    yield event
            finally:
                send_audio_task.cancel()
                send_text_task.cancel()
                receive_task.cancel()
                self._gemini_session = None
                self._loop = None

    # = 메인 진입점 =======================
    async def run(self, client_ws: WebSocket):
        """
        Android WebSocket ↔ Gemini Live API 세션을 연결합니다.

        클라이언트 메시지 포맷:
          - {"type": "AUDIO", "data": "<base64 PCM 16kHz>"}
          - {"type": "TEXT",  "data": "..."}
        """
        audio_input_queue: asyncio.Queue = asyncio.Queue()
        text_input_queue: asyncio.Queue = asyncio.Queue()

        async def audio_output_callback(data: bytes):
            audio_b64 = base64.b64encode(data).decode("utf-8")
            await client_ws.send_json({"type": "AUDIO", "data": audio_b64})

        async def receive_from_client():
            try:
                async for raw in client_ws.iter_text():
                    try:
                        msg = json.loads(raw)
                    except json.JSONDecodeError:
                        print(f"[VoiceAgent] ⚠️ JSON 파싱 실패: {raw[:80]}")
                        continue

                    msg_type = msg.get("type")
                    if self._verbose and msg_type != "AUDIO":
                        print(f"[VoiceAgent] ← {msg_type}: {str(msg)[:120]}")

                    if msg_type == "AUDIO":
                        audio_bytes = base64.b64decode(msg.get("data", ""))
                        await audio_input_queue.put(audio_bytes)
                    elif msg_type == "TEXT":
                        await text_input_queue.put(msg.get("data", ""))

            except WebSocketDisconnect:
                if self._verbose:
                    print("[VoiceAgent] 클라이언트 연결 종료")
            except Exception as e:
                if self._verbose:
                    print(
                        f"[VoiceAgent] receive_from_client 오류: {type(e).__name__} - {e}"
                    )

        receive_task = asyncio.create_task(receive_from_client())
        try:
            async for event in self._start_session(
                audio_input_queue=audio_input_queue,
                text_input_queue=text_input_queue,
                audio_output_callback=audio_output_callback,
            ):
                if self._verbose:
                    print(f"[VoiceAgent] → {event.get('type')}")
                await client_ws.send_json(event)

        except Exception as e:
            print(f"[VoiceAgent] run() 오류: {type(e).__name__} - {e}")
            raise
        finally:
            receive_task.cancel()

    # = process_step (CUAgent REST 콜백) =============
    def process_step(self, screenshot_bytes: bytes, activity: str) -> Dict[str, Any]:
        """CUAgent로 다음 액션 결정 후, 완료 시 Gemini에 결과 전달"""
        if self._cu_task_in_progress and self._last_cu_action_json:
            if self._verbose:
                print(
                    f"[VoiceAgent] 작업 계속 (이전 액션: {self._last_cu_action_json['action']})"
                )
            cu_result = self._cu_agent.step(
                previous_action=self._last_cu_action_json,
                current_screenshot_data=screenshot_bytes,
                current_activity=activity,
            )
        elif self._pending_instruction:
            if self._verbose:
                print(f"[VoiceAgent] CUAgent 작업 시작: {self._pending_instruction}")
            cu_result = self._cu_agent.init_task(
                self._pending_instruction, screenshot_bytes, activity
            )
            self._pending_instruction = None
        else:
            return {
                "type": "ERROR",
                "message": "스크린샷을 받았으나 대기 중인 작업이 없습니다.",
            }

        if cu_result["type"] == "ACTION":
            self._cu_task_in_progress = True
            self._last_cu_action_json = cu_result
        else:
            self._reset_task_state()

            if self._gemini_session is not None and self._loop is not None:
                result_msg = cu_result.get("message", "")
                feedback = (
                    f"[시스템] 앱 조작 완료. 결과: {result_msg}"
                    if cu_result["type"] == "RESPONSE"
                    else f"[시스템] 앱 조작 중 오류 발생: {result_msg}"
                )
                if self._verbose:
                    print(f"[VoiceAgent] Gemini에 결과 전달: {feedback}")
                asyncio.run_coroutine_threadsafe(
                    self._gemini_session.send_client_content(
                        turns=Content(role="user", parts=[Part(text=feedback)])
                    ),
                    self._loop,
                )

        return cu_result

    # = 테스트 ==========================
    async def test(self):
        """
        텍스트 3턴을 queue에 넣고 이벤트를 출력하는 테스트.
        3번째 TURN_COMPLETE 수신 후 종료.
        """
        audio_input_queue: asyncio.Queue = asyncio.Queue()
        text_input_queue: asyncio.Queue = asyncio.Queue()

        async def audio_output_callback(data: bytes):
            print(f"[test] 🔊 AUDIO {len(data)} bytes")

        async def sender():
            await asyncio.sleep(1)  # 세션 연결 대기
            for i, text in enumerate(["안녕!", "네 이름은 뭐니?", "기분이 어때?"]):
                print(f"[test][send] #{i+1}: {text}")
                await text_input_queue.put(text)
                await asyncio.sleep(5)

        sender_task = asyncio.create_task(sender())
        turn_count = 0
        try:
            async for event in self._start_session(
                audio_input_queue=audio_input_queue,
                text_input_queue=text_input_queue,
                audio_output_callback=audio_output_callback,
            ):
                event_type = event.get("type")
                if event_type == "TURN_COMPLETE":
                    turn_count += 1
                    print(f"[test] TURN_COMPLETE #{turn_count} ✅")
                    if turn_count >= 3:
                        break
                elif event_type in ("INPUT_TRANSCRIPTION", "OUTPUT_TRANSCRIPTION"):
                    print(f"[test] {event_type}: {event.get('text', '')}")
                elif event_type == "SETUP_COMPLETE":
                    print("[test] SETUP_COMPLETE ✅")
                else:
                    print(f"[test] {event_type}")

        except Exception as e:
            print(f"[test] ❌ Error: {type(e).__name__} - {e}")
            raise
        finally:
            sender_task.cancel()
            print(f"\n[test] 완료 (총 {turn_count}턴)")


if __name__ == "__main__":
    cu_agent = CUAgent(
        model_name="gemini-2.5-computer-use-preview-10-2025", verbose=True
    )
    voice_agent = VoiceAgentManager(cu_agent=cu_agent)
    asyncio.run(voice_agent.test())
