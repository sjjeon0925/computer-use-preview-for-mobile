package com.example.myllm.viewmodel

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myllm.data.AppChatMessage
import com.example.myllm.network.AgentResponseDto
import com.example.myllm.repository.ChatRepository
import kotlinx.coroutines.launch

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

    fun requestScreenshot() {
        isCaptureRequested = true
    }

    fun onCaptureRequestHandled() {
        isCaptureRequested = false
    }
    fun updateUserInput(newInput: String) {
        userInput = newInput
    }

    fun onProjectionPermissionResult(resultCode: Int, data: Intent){
        viewModelScope.launch {
            Log.d("ChatViewModel", "repository.startAgentIteration resultCode: ($resultCode), Data: ($data)")
            repository.startAgentIteration(resultCode, data)
        }
    }

    /**
     * 텍스트 메시지를 처리하고 전송합니다.
     */
    fun processAndSendText(currentInput: String) {
        if (currentInput.isBlank() || isLoading) return

        val userMessage = AppChatMessage(currentInput, true)
        messages = messages + userMessage
        userInput = ""

        viewModelScope.launch {
            isLoading = true
            Log.i("ChatViewModel", "Chat Sending request: ${currentInput}")
            val result = repository.processUserMessage(currentInput)

            result.onSuccess { response ->
                handleLlmResponse(response)
            }.onFailure { error ->
                messages = messages + AppChatMessage("에러 발생: ${error.message}", false)
            }
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
                responseText = response.message ?: "응답 텍스트 없음"
                Log.i("ChatViewModel", "LLM 텍스트 응답: $responseText")
            }
            // 스크린 샷 필요
            // isCaptureRequested를 true로 만들면 ScreenCaptureService에서
            "REQUIRE_SCREENSHOT" -> {
                responseText = response.message ?: "화면을 캡처합니다..."
                Log.i("ChatViewModel", "LLM 텍스트 응답: $responseText")
                requestScreenshot()
            }
            // /chat/step
            // Action은 repository.processUserMessage에서 따로 처리함.
            "ACTION" -> {
                responseText = "요청을 실행 중입니다..."
                Log.d("ChatViewModel", "기능 호출: ${response.action}, 인자: ${response.args}")
            }
            "ERROR" -> {
                responseText = response.message ?: "응답 에러 텍스트 없음"
                Log.e("ChatViewModel", "LLM 텍스트 응답: $responseText")
            }
            else -> {
                responseText = "알 수 없는 응답 유형: ${response.type}"
                Log.e("ChatViewModel", responseText)
            }
        }
        val llmMessage = AppChatMessage(responseText, false)
        messages = messages + llmMessage
    }
}