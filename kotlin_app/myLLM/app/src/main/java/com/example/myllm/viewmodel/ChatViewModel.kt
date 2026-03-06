package com.example.myllm.viewmodel

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myllm.data.AppChatMessage
import com.example.myllm.network.AgentResponseDto
import com.example.myllm.repository.ChatRepository
import com.example.myllm.service.VoiceAgentManager
import kotlinx.coroutines.launch
import kotlin.collections.plus

@RequiresApi(Build.VERSION_CODES.S)
class ChatViewModel(private val repository: ChatRepository) : ViewModel() {

    // 상태 (State) 정의: UI가 관찰할 데이터
    var userInput by mutableStateOf("")
        private set
    var messages by mutableStateOf(listOf<AppChatMessage>())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isCaptureRequested by mutableStateOf(false)
        private set
    var isRecording by mutableStateOf(false)
        private set
    fun onCaptureRequestHandled() {
        isCaptureRequested = false
    }
    fun updateUserInput(newInput: String) {
        userInput = newInput
    }

    private val voiceManager = VoiceAgentManager(
        onTaskTriggered = { taskDesc ->
            viewModelScope.launch {
                isLoading = true
                messages = messages + AppChatMessage(taskDesc, true, true)

                // 2. 이미 권한이 있다면 바로 CUAgent 실행 (REQUIRE_SCREENSHOT 모사)
                // 기존 repository.startAgentIteration을 호출하기 위해 isCaptureRequested 활성화
                isCaptureRequested = true
                Log.d("ChatViewModel", "Voice-triggered Task: $taskDesc")

            }
        },
        OnChatMessage = { response: String, isUser: Boolean->
            viewModelScope.launch {
                messages = messages + AppChatMessage(response, isUser, true)
            }
        },
        OnStopStreaming = {
            stopStreaming()
        }
    )

    fun startStreaming(){
        isRecording = true
        voiceManager.startStreaming()
    }

    fun stopStreaming(){
        isRecording = false
        voiceManager.stopStreaming()
    }

    fun onProjectionPermissionResult(resultCode: Int, data: Intent){
        Log.d("ChatViewModel", "repository.startAgentIteration resultCode: ($resultCode), Data: ($data)")
        val captureService = repository.getCaptureService()
        captureService?.startAgentLoop(repository, resultCode, data)
        viewModelScope.launch {
            captureService?.agentResultFlow?.collect { result ->
                result.onSuccess { response ->
                    handleLlmResponse(response)
                }.onFailure { error ->
                    showError(error)
                }
            }
        }
    }

    /**
     * 텍스트 메시지를 처리하고 전송합니다.
     * 녹음 사용 -> ChatAgent에게 보냄.
     * 녹음 사용 안 함 -> WebSocket으로 멀티모달 에이전트에게 보냄
     */
    fun processAndSendText(currentInput: String) {
        if (currentInput.isBlank() || isLoading) return

        messages = messages + AppChatMessage(currentInput, true)
        userInput = ""

        if(isRecording){
            voiceManager.sendTextOnWebSocket(currentInput)
            return
        }

        viewModelScope.launch {
            isLoading = true
            Log.i("ChatViewModel", "Chat Sending request: ${currentInput}")
            val result = repository.processUserMessage(currentInput)

            result.onSuccess { response ->
                handleLlmResponse(response)
            }.onFailure { error -> showError(error) }
            isLoading = false
        }
    }

    // --- Private 네트워크/데이터 처리 함수 ---

    private fun handleLlmResponse(response: AgentResponseDto) {
        Log.d("ChatViewModel", "LLM 응답 수신: Type=${response.type}")
        val responseText: String

        when (response.type) {
            // /chat/qeury
            // 1. 일상 답변
            // 2. iteration 종료 후 결론
            "RESPONSE" -> {
                // TODO: 결론 메세지 ChatScreen에 보여주기
                responseText = response.message ?: "응답 텍스트 없음"
                Log.d("ChatViewModel", "LLM 텍스트 응답: $responseText")
            }
            // iteration 진입포인트
            // isCaptureRequested를 true로 만들면
            // 1. ChatScreen에서 스크린샷 권한 받음
            // 2. ChatViewModel에서 repository.startAgentIteration()실행
            // 2-1. 즉, iteration은 REQUIRE_SCREENSHOT을 답변으로 받으면 실행됨.
            "REQUIRE_SCREENSHOT" -> {
                responseText = response.message ?: "화면을 캡처합니다..."
                Log.d("ChatViewModel", "LLM 텍스트 응답: $responseText")
                isCaptureRequested = true
            }
            // /chat/step
            // Action은 repository.processUserMessage에서 따로 처리함.
            "ACTION" -> {
                responseText = "요청을 실행 중입니다..."
                Log.d("ChatViewModel", "기능 호출: ${response.action}, 인자: ${response.args}")
            }
            "ERROR" -> {
                responseText = response.message ?: "응답 에러 텍스트 없음"
                Log.e("ChatViewModel", "LLM 텍스트 ERROR 응답: $responseText")
            }
            else -> {
                responseText = "알 수 없는 응답 유형: ${response.type}"
                Log.e("ChatViewModel", responseText)
            }
        }
        val llmMessage = AppChatMessage(responseText, false)
        messages = messages + llmMessage
    }

    private fun showError(error: Throwable){
        messages = messages + AppChatMessage("에러 발생: ${error.message}", false)
    }

}
