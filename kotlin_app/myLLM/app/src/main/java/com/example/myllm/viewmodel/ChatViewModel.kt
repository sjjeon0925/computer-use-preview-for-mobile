package com.example.myllm.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myllm.data.AppChatMessage
import com.example.myllm.network.AgentResponseDto
import com.example.myllm.repository.ChatRepository
import com.example.myllm.service.VoiceEvent
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.S)
class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    companion object {
        class ChatViewModelFactory(private val context: Context, val isPreview: Boolean): ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = ChatRepository(context)
                return ChatViewModel(repository) as T
            }
        }
    }

    // task가 활성화되었는지 추적 — abort 타이밍 경쟁 방지
    private var isTaskActive = false

    init {
        viewModelScope.launch {
            repository.voiceEvent.collect { voiceEvent ->
                when(voiceEvent) {
                    is VoiceEvent.TaskTriggered -> {
                        isTaskActive = true
                        isLoading = true
                        messages = messages + AppChatMessage(voiceEvent.taskDesc, true, isSpeech = true)
                        isCaptureRequested = true
                        Log.d("ChatViewModel", "Voice-triggered Task: ${voiceEvent.taskDesc}")
                    }
                    is VoiceEvent.ChatMessage -> {
                        val response = voiceEvent.response
                        val isUser = voiceEvent.isUser
                        messages = messages + AppChatMessage(response, isUser, true)
                    }
                    is VoiceEvent.StopStreaming -> {
                        stopStreaming()
                    }
                    is VoiceEvent.AbortTask -> {
                        abortTask()
                    }
                    else -> {
                        Log.e("voiceEvent Collect", "Unknown VoiceEvent")
                    }
                }
            }
        }
    }

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

    fun startStreaming(){
        isRecording = true
        repository.startStreaming()
    }

    fun stopStreaming(){
        isRecording = false
        repository.stopStreaming()
    }

    fun onProjectionPermissionResult(resultCode: Int, data: Intent){
        if (!isTaskActive) return
        val captureService = repository.getCaptureService()
        captureService?.launchAgentLoop(
            onIterate = { repository.startAgentIteration(resultCode, data) },
            onResult = { result ->
                viewModelScope.launch {
                    result.onSuccess { handleLlmResponse(it) }
                    isLoading = false
                    isTaskActive = false
                }
            }
        )
    }

    private fun abortTask() {
        isTaskActive = false
        isLoading = false
        isCaptureRequested = false
        repository.abortAgentLoop()
        Log.d("ChatViewModel", "Task aborted by user")
    }

    /**
     * 텍스트 메시지를 처리하고 전송합니다.
     * 음성 모드(isRecording=true): VoiceAgent에게 텍스트를 전달합니다.
     */
    fun processAndSendText(currentInput: String) {
        if (currentInput.isBlank() || isLoading) return

        messages = messages + AppChatMessage(currentInput, true)
        userInput = ""

        if(isRecording){
            repository.sendVoiceText(currentInput)
            return
        }
        viewModelScope.launch {
            isLoading = true
            Log.i("ChatViewModel", "Chat Sending request: ${currentInput}")
            val result = repository.processUserMessage(currentInput)

            result.onSuccess { response ->
                handleLlmResponse(response)
            }.onFailure { error ->
                Log.e("CharViewModel", "")
            }
            isLoading = false
        }
    }

    // --- 네트워크/데이터 처리 함수 ---
    // TODO: chat_agent를 쓸 때 텍스트 응답 처리용 함수였음. voice_agent를 쓰면서 voice_manager로 역할이 옮겨져서 아마도 삭제하는 것이 프로젝트 역할 분담에서 좋을 것 같음.
    private fun handleLlmResponse(response: AgentResponseDto) {
        Log.d("ChatViewModel", "LLM 응답 수신: Type=${response.type}")
        val responseText: String

        when (response.type) {
            // 1. 일상 답변
            // 2. iteration 종료 후 결론
            "RESPONSE" -> {
                // TODO: 결론 메세지 ChatScreen에 보여주기
                responseText = response.message ?: "응답 텍스트 없음"
                Log.d("ChatViewModel", "LLM 텍스트 응답: $responseText")
            }
            // iteration 진입포인트
            "REQUIRE_SCREENSHOT" -> {
                responseText = response.message ?: "화면을 캡처합니다..."
                Log.d("ChatViewModel", "LLM 텍스트 응답: $responseText")
                isCaptureRequested = true
            }
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
        if(!isRecording){
            val llmMessage = AppChatMessage(responseText, false)
            messages = messages + llmMessage
        }
    }

}
