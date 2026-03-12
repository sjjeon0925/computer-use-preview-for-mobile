package com.example.myllm.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.myllm.network.AgentResponseDto
import com.example.myllm.network.NetworkClient
import com.example.myllm.service.ActionController
import com.example.myllm.service.ScreenCaptureService
import com.example.myllm.service.ScreenCaptureService.Companion.DEFAULT_SCALE
import com.example.myllm.service.UserService
import com.example.myllm.utils.TaskLoopUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class ChatRepository(private val context: Context) {
    private var captureService: ScreenCaptureService? = null
    fun getCaptureService(): ScreenCaptureService? { return captureService }

    private var isBound = false
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenCaptureService.LocalBinder
            captureService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    init {
        // Repository 생성 시점에 서비스를 바인딩
        val intent = Intent(context, ScreenCaptureService::class.java)
        // Foreground Service라면 먼저 start 후 bind
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            context.startForegroundService(intent)
        }else{
            context.startService(intent)
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private val _voiceEvent = MutableSharedFlow<VoiceEvent>()
    val voiceEvent = _voiceEvent.asSharedFlow()
    private val voiceManager = VoiceAgentManager(
        onTaskTriggered = { taskDesc ->
            _voiceEvent.emit(VoiceEvent.TaskTriggered(taskDesc))
        },
        OnChatMessage = { response: String, isUser: Boolean->
            _voiceEvent.emit(VoiceEvent.ChatMessage(response, isUser))
        },
        OnStopStreaming = {
            _voiceEvent.emit(VoiceEvent.StopStreaming)
        }
    )

    @RequiresApi(Build.VERSION_CODES.S)
    fun startStreaming() = voiceManager.startStreaming()
    fun stopStreaming() = voiceManager.stopStreaming()
    fun sendVoiceText(text: String) = voiceManager.sendTextOnWebSocket(text)


    suspend fun startAgentIteration(resultCode: Int, data: Intent) : Result<AgentResponseDto>? {
        captureService?.initializeProjection(resultCode, data)

        var result: Result<AgentResponseDto>? = null
        var shouldContinue = true
        var iterationCount = 0
        while (shouldContinue) {
            kotlinx.coroutines.delay(1000) // 사이클 간 간격

            Log.d("startAgentIteration", "iteration starting log...($iterationCount)")
            iterationCount += 1
            // 1. 캡처
            var bitmap: Bitmap? = null
            var captureTry = 0
            while(captureTry < 5){
                bitmap = withTimeoutOrNull(5000) {
                    try {
                        captureService?.captureCurrentScreen()
                    }catch(e:Exception){
                        Log.e("startAgentIteration", "캡쳐 중 error 발생: ${e.message}")
                        null
                    }
                }
                if(bitmap == null){
                    Log.d("startAgentIteration", "bitmap is null")
                    captureTry++
                }else break
            }
            if(captureTry > 4) break

            // 2. 서버 전송
            result = uploadScreenCaptureAndUiInfo(bitmap!!)
            result.onSuccess { response ->
                Log.d("AgentIteration", "uploadScreenCapture() result is $response")
                // 3. 종료 조건 확인 (예: 서버가 종료 응답을 보냄)
                when(response.type){
                    "ACTION" -> {
                        // 4. 액션 실행 및 완료 대기
                        val success = processActionAndWait(response)
                        if (!success) {
                            Log.w("ChatRepository", "액션 실행 실패")
                            onFailInIteration("액션 실행 대기 중 타임아웃 발생")
                        }
                    }
                    "RESPONSE" -> {
                        shouldContinue = false
                    }
                    "ERROR" -> {
                        shouldContinue = false
                    }
                }
            }.onFailure {
                onFailInIteration(errorMsg = "스크린샷 업로드 통신 오류")
                shouldContinue = false
            }
        }
        Log.d("EndOfAgentIteration", "$result")
        return result
    }
    suspend fun processUserMessage(userInput: String): Result<AgentResponseDto> {
        return try {
            // 서버에 텍스트 전송 (Network)
            val response = NetworkClient.service.sendMessage(userInput, UserService.getUserId())
            if(response.isSuccessful){
                val body = response.body()
                if(body != null){
                    // Iteration 진입해야함
                    Log.i("ChatRepository", "Form 전송 및 응답 수신 성공: ${response.code()}")
                    Result.success(body)
                } else{
                    Result.failure(Exception("Empty Response Body"))
                }
            }else{
                Log.e("ChatRepository", "Form 전송 혹은 응답 수신 실패: ${response.code()}")
                Result.failure(Exception("Error Code: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Chat 오류: ${e.message}", e)
            Result.failure(e)
        }
    }

    // 스크린샷 이미지와 컨텍스트를 서버로 업로드 (Service에서 호출)
    suspend fun uploadScreenCaptureAndUiInfo(bitmap: Bitmap): Result<AgentResponseDto> {
        return try {
            // Bitmap을 MultipartBody.Part로 변환
            val filePart = TaskLoopUtils.bitmapToMultipartPart(bitmap = bitmap, userId = UserService.getUserId())

            // Activity 컨텍스트를 포함
            val uiXmlString = ActionController.getLatestUiHierarchy()
            val contextBody = uiXmlString.toRequestBody("text/plain".toMediaTypeOrNull())
            val sessionIdBody = UserService.getUserId().toRequestBody("text/plain".toMediaTypeOrNull())
            val scaleBody = DEFAULT_SCALE.toString().toRequestBody("text/plain".toMediaTypeOrNull())

            val agentResponse = NetworkClient.service.sendStepMultipart(filePart, contextBody,
                sessionIdBody, scaleBody)

            if(agentResponse.isSuccessful){
                val body = agentResponse.body()
                if(body != null){
                    Log.i("ChatRepository", "Image Form 전송 성공: agentResponse.body: ${body.message}")
                    Result.success(body)
                }else{
                    Result.failure(Exception("Empty Response"))
                }
            }else{
                Log.e("ChatRepository", "Image Form 응답 오류: ${agentResponse.code()}")
                Result.failure(Exception("Upload Failed: ${agentResponse.code()}"))
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "스크린샷 업로드 통신 오류: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Action 실행하고 종료까지 wait하는 함수
    suspend fun processActionAndWait(body: AgentResponseDto): Boolean {
        Log.d("ChatRepository", "Parsing action: ${body.action}, args: ${body.args}")
        val action = TaskLoopUtils.parseAction(body,
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
            captureService?.getScale() ?: DEFAULT_SCALE)

        val resultDeferred = CompletableDeferred<Boolean>()
        val job = CoroutineScope(Dispatchers.IO).launch {
            ActionController.actionResultFlow.collect { success ->
                resultDeferred.complete(success)
                this.cancel() // 한 번 받으면 종료
            }
        }

        ActionController.sendAction(action)

        return try {
            withTimeout(10000) {
                val isSuccess = resultDeferred.await()
                Log.i("ChatRepository", "액션 실행 결과 수신: $isSuccess")
                isSuccess
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "액션 실행 대기 중 타임아웃 발생: $e")
            false
        } finally {
            job.cancel()
        }
    }

    private suspend fun onFailInIteration(errorMsg: String = "", e: Exception? = null){
        processUserMessage("[system] iteration 중 실행 실패했습니다. Error: ${e?.message ?: errorMsg}")
    }
}