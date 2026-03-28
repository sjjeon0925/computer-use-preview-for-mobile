# Voice Agent 코드 구조 및 개발 가이드

> **참고 프로젝트**: [GoogleCloudPlatform — Gemini Multimodal Live API (plain-js-python-sdk-demo-app)](https://github.com/GoogleCloudPlatform/generative-ai/tree/main/gemini/multimodal-live-api/native-audio-websocket-demo-apps/plain-js-python-sdk-demo-app)
>
> 현재 백엔드(`voice_agent.py`)의 `VoiceAgentManager` 구조는 위 참고 프로젝트의 설계 패턴을 기반으로 작성되었습니다.

---

## 1. 전체 아키텍처 개요

Voice agent는 **Android 앱 ↔ Python 백엔드 ↔ Gemini Live API ↔ CUAgent** 의 4계층으로 구성됩니다.

```
[사용자 음성]
    │ (마이크 PCM 16kHz)
    ▼
[Android: VoiceAgentManager]  ─── WebSocket (ws://10.0.2.2:8000/ws/voice/{session_id}) ───►
    │                                                                                         │
    │ (VoiceEvent via SharedFlow)                                                  [Python: VoiceAgentManager]
    ▼                                                                                         │
[ChatRepository]                                                                    ┌─────────┴──────────┐
    │                                                                               │  Gemini Live API   │
    │ (VoiceEvent.TaskTriggered)                                                    │  (Native Audio)    │
    ▼                                                                               └─────────┬──────────┘
[ChatViewModel]                                                                               │ Function Call
    │                                                                                         │ (execute_mobile_task)
    │ (isCaptureRequested = true)                                                             ▼
    ▼                                                                              [CUAgent (REST via /chat/step)]
[ScreenCaptureService]                                                                        │
    │ (bitmap + UI XML)                                                                       │ ACTION / RESPONSE
    └────────────────────── POST /chat/step ─────────────────────────────────────────────────┘
                                                                                              │
[MyAccessibilityService] ◄── ActionController.actionFlow ◄── ChatRepository.processActionAndWait()
```

---

## 2. 백엔드: `voice_agent.py`

### 클래스: `VoiceAgentManager`

Gemini Live API 세션을 관리하는 핵심 백엔드 클래스입니다.

#### 내부 Task 구조 (`_start_session` — async generator)

```
_start_session()
  ├─ send_audio task:    audio_input_queue → session.send_realtime_input(audio)
  ├─ send_text  task:    text_input_queue  → session.send_realtime_input(text)
  ├─ receive_loop task:  session.receive() → event_queue
  │    ├─ 오디오 출력: audio_output_callback으로 즉시 전달 (event_queue 우회)
  │    ├─ INPUT_TRANSCRIPTION / OUTPUT_TRANSCRIPTION → event_queue
  │    ├─ TURN_COMPLETE / INTERRUPTED → event_queue
  │    └─ Function Call (execute_mobile_task / abort_mobile_task) → CONTROL 이벤트
  └─ 메인 루프: event_queue → yield event

run()  ← WebSocket 진입점 (server-image.py의 /ws/voice/{session_id})
  ├─ receive_from_client task: 클라이언트 → audio_input_queue / text_input_queue
  └─ async for event in _start_session() → ws.send_json(event)
```

#### Gemini에 등록된 Tool 함수

| 함수명 | 호출 조건 | 동작 |
|--------|----------|------|
| `execute_mobile_task(task_description)` | 앱 조작 요청 감지 시 | `CONTROL/START_TASK` 이벤트 발행 → Android가 스크린샷 전송 → CUAgent 루프 시작 |
| `abort_mobile_task()` | "멈춰/취소/중단" 감지 시 - 아직 작동 잘 안됨 | `CONTROL/ABORT_TASK` 이벤트 발행 → Android 태스크 중단 |

#### `process_step(screenshot_bytes, activity)` — REST 콜백

`POST /chat/step` 요청 시 `server-image.py`에서 호출됩니다.

- `_pending_instruction` 있으면 → `CUAgent.init_task()` 호출 (첫 스텝)
- `_cu_task_in_progress` 이면 → `CUAgent.step()` 호출 (이후 스텝)
- CUAgent가 `RESPONSE` 반환하면 → Gemini 세션에 결과 피드백 후 상태 초기화

#### CUAgent 상태 변수

| 변수 | 설명 |
|------|------|
| `_pending_instruction` | Gemini가 `execute_mobile_task` 호출 후, 첫 스크린샷이 오기를 기다리는 작업 지시 |
| `_cu_task_in_progress` | CUAgent가 ACTION을 반환해 다음 스텝이 필요한 상태 |
| `_last_cu_action_json` | 직전 CUAgent ACTION (다음 `step()` 호출 시 전달) |
| `_gemini_session` / `_loop` | 완료 후 Gemini에 결과를 push하기 위해 유지 |

---

## 3. Android 클라이언트

### 3-1. `VoiceAgentService.kt` — `VoiceAgentManager` 클래스

> **주의**: 파일명은 `VoiceAgentService.kt`이지만 Android `Service`를 상속하지 않습니다.
> `ChatRepository`가 직접 인스턴스를 생성하여 보유하는 일반 클래스입니다.
> (이전에는 `repository/VoiceAgentManager.kt`였다가 `service/` 패키지로 이동한 리팩토링 결과물)

#### 주요 역할
- OkHttp WebSocket으로 서버 연결 (`ws://10.0.2.2:8000/ws/voice/{userId}`)
- `AudioRecord`로 마이크 입력을 PCM 16kHz로 캡처해 Base64 인코딩 후 서버 전송
- 서버에서 오는 JSON 이벤트 파싱 → 콜백으로 `ChatRepository`에 전달
- `AudioTrack`으로 서버의 오디오 응답 재생 (TURN_COMPLETE 시 일괄 재생)

#### 오디오 설정

| 항목 | 값 | 이유 |
|------|-----|------|
| 입력 샘플레이트 | 16,000 Hz | Gemini Live API 권장 |
| 출력 샘플레이트 | 24,000 Hz | Gemini Live API 기본 출력 |
| 포맷 | PCM 16bit Mono | Gemini Live API 요구사항 |
| 재생 방식 | `MODE_STATIC` + TURN_COMPLETE 시 일괄 재생 | 오디오 끊김 방지 |

#### 서버 수신 이벤트 처리 (`parsingResponse`)

| 이벤트 `type` | 처리 |
|--------------|------|
| `SETUP_COMPLETE` | 전사(transcript) 초기화 |
| `CONTROL/START_TASK` | `onTaskTriggered(taskDesc)` 콜백 |
| `CONTROL/ABORT_TASK` | 누적 오디오 버림 + `onAbortTask()` 콜백 |
| `INTERRUPTED` | 누적 오디오 버림 (사용자가 말을 끊은 경우) |
| `INPUT_TRANSCRIPTION` | 사용자 발화 텍스트 누적 → finished 시 `onChatMessage(text, isUser=true)` |
| `OUTPUT_TRANSCRIPTION` | 모델 응답 텍스트 누적 → TURN_COMPLETE 시 함께 처리 |
| `AUDIO` | Base64 PCM 디코딩 → `pendingAudioChunks`에 누적 |
| `TURN_COMPLETE` | `pendingAudioChunks` 일괄 재생 + `onChatMessage(transcript, isUser=false)` |

#### 클라이언트 → 서버 메시지 포맷

```json
{ "type": "AUDIO", "data": "<Base64 PCM>" }
{ "type": "TEXT",  "data": "사용자 텍스트" }
```

### 3-2. `VoiceEvent` sealed class

`VoiceAgentManager` → `ChatRepository` → `ChatViewModel` 간 이벤트 버스 역할.

```kotlin
sealed class VoiceEvent {
    data class TaskTriggered(val taskDesc: String) : VoiceEvent()  // 앱 조작 트리거
    data class ChatMessage(val response: String, val isUser: Boolean) : VoiceEvent()  // 채팅 메시지 표시
    object StopStreaming : VoiceEvent()   // 스트리밍 종료
    object AbortTask : VoiceEvent()       // 태스크 중단
}
```

---

## 4. 서포트 컴포넌트

### 4-1. `ActionController.kt` (object — 싱글톤)

ViewModel/Repository ↔ `MyAccessibilityService` 간 브릿지.

```kotlin
object ActionController {
    val actionFlow: SharedFlow<Action>        // Repository가 emit → Service가 collect
    val actionResultFlow: SharedFlow<Boolean> // Service가 emit → Repository가 await
    var uiDumpProvider: (() -> String)?       // Service가 등록 → Repository가 호출
}
```

### 4-2. `MyAccessibilityService.kt`

`ActionController.actionFlow`를 collect해서 실제 기기 제스처를 실행합니다.

| 액션 | 구현 방식 |
|------|---------|
| `ClickAt` | `GestureDescription` (1ms stroke) |
| `PerformLongPress` | `GestureDescription` (600ms stroke) |
| `ClickAndTypeText` | 클릭 후 700ms delay → `performSmartInput` |
| `PerformSmartInput` | `ACTION_SET_TEXT` → 실패 시 Clipboard PASTE fallback |
| `PerformScroll` | 화면 70%↔30% 수직 스와이프 |
| `PerformHorizontalSwipe` | 화면 20%↔80% 수평 스와이프 |
| `PerformGoBack/Home` | `performGlobalAction()` |
| `PerformOpenApp` | `packageManager.getLaunchIntentForPackage()` |
| `FindTextOnScreen` | 전체 노드 트리 탐색 (contains 검색) |
| `PerformScrollToText` | 최대 10회 스크롤하며 텍스트 탐색 |
| `PerformMacro` | `"action(params)"` 문자열 파싱 후 순차 실행 |

`dumpUiHierarchyInXmlTree()`: 현재 화면의 접근성 노드 트리를 XML로 직렬화. 서버로 보내 CUAgent가 UI 구조를 이해하는 데 사용.

### 4-3. `ScreenCaptureService.kt` (Android Foreground Service)

- `MediaProjection` API로 화면 캡처 (0.5x 스케일)
- `launchAgentLoop()`: `CoroutineScope`에서 CUAgent 반복 루프 실행
- `captureCurrentScreen()`: `ImageReader`에서 최신 `Bitmap` 반환 (thread-safe)

### 4-4. `ChatRepository.kt`

음성 모드에서의 데이터 흐름 조율:

1. `VoiceAgentManager` 인스턴스 보유 + `voiceEvent` SharedFlow 발행
2. `VoiceEvent.TaskTriggered` → `ChatViewModel`이 `isCaptureRequested = true` 설정
3. `startAgentIteration()`: 스크린샷 캡처 → `uploadScreenCaptureAndUiInfo()` → `processActionAndWait()` 루프
4. `processActionAndWait()`: `ActionController.sendAction()` 후 `actionResultFlow`를 10초 타임아웃으로 대기

### 4-5. `ChatViewModel.kt`

| 상태 | 역할 |
|------|------|
| `isRecording` | 음성 스트리밍 활성 여부 |
| `isCaptureRequested` | `true`가 되면 UI에서 MediaProjection 권한 요청 후 `onProjectionPermissionResult()` 호출 |
| `isTaskActive` | abort 경쟁 조건 방지 플래그 |

---

## 5. 전체 데이터 흐름 (음성 모드)

```
1. startStreaming()
   → VoiceAgentManager.startStreaming() → WebSocket 연결 + AudioRecord 시작

2. 마이크 PCM → Base64 → WebSocket → 서버 audio_input_queue → Gemini Live API

3. Gemini가 "앱 조작" 의도 감지
   → execute_mobile_task(task_description) Function Call
   → 서버: CONTROL/START_TASK 이벤트 발행

4. Android VoiceAgentManager: CONTROL/START_TASK 수신
   → onTaskTriggered(taskDesc) 콜백
   → VoiceEvent.TaskTriggered emit
   → ChatViewModel: isCaptureRequested = true

5. UI: MediaProjection 권한 다이얼로그 표시
   → 허용 시 onProjectionPermissionResult(resultCode, data)
   → ScreenCaptureService.launchAgentLoop() 시작

6. 루프 (startAgentIteration):
   a. ScreenCaptureService.captureCurrentScreen() → Bitmap
   b. ActionController.getLatestUiHierarchy() → XML
   c. POST /chat/step (bitmap + xml + sessionId + scale)
   d. 서버: VoiceAgentManager.process_step() → CUAgent.init_task() 또는 step()
   e. 응답 type == "ACTION" → processActionAndWait()
      → ActionController.sendAction()
      → MyAccessibilityService 실행 → actionResultFlow emit
      → 10초 타임아웃으로 결과 대기
   f. 응답 type == "RESPONSE" → 루프 종료

7. CUAgent RESPONSE → 서버가 Gemini 세션에 결과 피드백
   → Gemini가 사용자에게 음성으로 결과 요약
```

---

## 6. 미완성 및 개선 필요 사항

### 코드 내 TODO

| 위치 | 내용 |
|------|------|
| `ChatViewModel.kt:144` | `handleLlmResponse()`는 이전 chat_agent 방식 코드. voice_agent 전환 후 역할이 VoiceAgentManager로 이전되었으므로 정리 검토 필요 |
| `VoiceAgentService.kt:234` | `"RESPONSE"` type 처리 코드가 주석 처리됨 (서버 텍스트 응답 처리 미구현) |
| `MyAccessibilityService.kt:483` | `performMacro` 내 `clickAndTypeText`가 콜백 기반이라 매크로 순차 실행이 보장되지 않음. suspend function 전환 필요 |

### 알려진 구조적 이슈

1. **VoiceAgentManager 파일명 혼란**: 파일은 `VoiceAgentService.kt`이지만 Android Service가 아닌 일반 클래스. 파일명/클래스명 일치 검토 필요.

2. **중복 실행 방지 미흡**: `isStreaming` 플래그가 `VoiceAgentManager` 내부에만 있어, `ChatRepository`에서 중복 `startStreaming()` 호출에 대한 보호 없음.

3. **AudioTrack MODE_STATIC 한계**: 현재 TURN_COMPLETE 시 전체 오디오를 한 번에 재생하는 방식(`MODE_STATIC`). 응답이 길 경우 재생 전 대기시간이 발생. 스트리밍 재생(`MODE_STREAM`)으로 전환하면 지연 개선 가능.

4. **agentJob 단일 관리**: `ScreenCaptureService`의 `agentJob`이 단일 `Job`이라 여러 태스크가 동시에 트리거되면 이전 것이 취소됨. 현재는 `isTaskActive` 플래그로 방지하고 있으나 경쟁 조건 가능성 있음.

---

## 7. 추가 개발 시 참고사항

### WebSocket 메시지 프로토콜 (클라이언트 ↔ 서버)

**클라이언트 → 서버**
```json
{ "type": "AUDIO", "data": "<Base64 PCM 16kHz>" }
{ "type": "TEXT",  "data": "텍스트 메시지" }
```

**서버 → 클라이언트**
```json
{ "type": "SETUP_COMPLETE" }
{ "type": "CONTROL", "action": "START_TASK", "message": "작업 설명" }
{ "type": "CONTROL", "action": "ABORT_TASK" }
{ "type": "INTERRUPTED" }
{ "type": "INPUT_TRANSCRIPTION", "text": "...", "finished": true/false }
{ "type": "OUTPUT_TRANSCRIPTION", "text": "..." }
{ "type": "AUDIO", "data": "<Base64 PCM 24kHz>" }
{ "type": "TURN_COMPLETE" }
```

### 서버 엔드포인트 (server-image.py)

| 엔드포인트 | 메서드 | 역할 |
|-----------|--------|------|
| `/chat/query` | POST | 텍스트 쿼리 (chat_agent) - voice agent 사용시 필요없음 |
| `/chat/step` | POST multipart | 스크린샷 + UI XML 전송 (cu_agent / voice_agent) |
| `/ws/voice/{session_id}` | WebSocket | 음성 에이전트 연결 |

### 환경변수 (.env)

```
GEMINI_API_KEY=...
GOOGLE_CLOUD_PROJECT=...
GOOGLE_CLOUD_LOCATION=us-central1
```

### 모델 ID

| 용도 | 모델 |
|------|------|
| Computer Use (앱 조작) | `gemini-2.5-computer-use-preview-10-2025` |
| Voice (Live API) | `gemini-live-2.5-flash-native-audio` (Vertex AI) |
| Chat | `gemini-2.5-flash` |

### Android 앱 실행 전 체크리스트

- [ ] 접근성 서비스 활성화 (`MyAccessibilityService`)
- [ ] 화면 녹화 권한 허용 (앱 최초 실행 시)
- [ ] 마이크 권한 허용
- [ ] 서버 실행 중 확인 (`python server-image.py`)
- [ ] 에뮬레이터 사용 시: `10.0.2.2` = 호스트 PC localhost
