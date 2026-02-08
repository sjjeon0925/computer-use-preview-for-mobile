package com.example.myllm.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import com.example.myllm.data.Action
import com.example.myllm.network.AgentResponseDto
import com.example.myllm.network.NetworkClient
import com.example.myllm.service.ActionController
import com.example.myllm.service.ScreenCaptureService
import com.example.myllm.service.UserService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class ChatRepository(private val context: Context) {
    private var captureService: ScreenCaptureService? = null
    fun getCaptureService(): ScreenCaptureService? {return captureService}
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
        bindCaptureService()
    }
    fun bindCaptureService() {
        val intent = Intent(context, ScreenCaptureService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

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
            result = uploadScreenCaptureAndWindowHierachy(bitmap!!)
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
    suspend fun uploadScreenCaptureAndWindowHierachy(bitmap: Bitmap): Result<AgentResponseDto> {
        return try {
            // Bitmap을 MultipartBody.Part로 변환
            val filePart = bitmapToMultipartPart(bitmap)

            // Activity 컨텍스트를 포함
            val uiXmlString = ActionController.getLatestUiHierarchy()
            val contextBody = uiXmlString.toRequestBody("text/plain".toMediaTypeOrNull())
            val sessionIdBody = UserService.getUserId().toRequestBody("text/plain".toMediaTypeOrNull())

            val agentResponse = NetworkClient.service.sendStepMultipart(filePart, contextBody,
                sessionIdBody)

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
        val action = parseAction(body)

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


    private fun parseAction(dto: AgentResponseDto) : Action {
        Log.d("ChatRepository", "Parsing action: ${dto.action}, args: ${dto.args}")
        
        return when(dto.action) {
            // 클릭 액션
            "click_at" -> {
                val x = dto.args?.get("x")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val y = dto.args?.get("y")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val scaledCoord = scaleCoordinates(x, y, context)
                Log.i("ChatRepository", "ClickAt: ($x, $y), scaled: ($scaledCoord)")
                Action.ClickAt(scaledCoord.first, scaledCoord.second)
            }
            
            // 텍스트 입력 액션
            "type_text_at" -> {
                val x = dto.args?.get("x")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val y = dto.args?.get("y")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val text = dto.args?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                val scaledCoord = scaleCoordinates(x, y, context)
                Log.i("ChatRepository", "ClickAndTypeText: ($x, $y), scaled: ($scaledCoord), text='$text'")
                Action.ClickAndTypeText(scaledCoord.first, scaledCoord.second, text)
            }
            
            // 스크롤 액션
            "scroll_at" -> {
                val direction = dto.args?.get("direction")?.jsonPrimitive?.contentOrNull ?: "up"
                val scrollUp = direction.lowercase() == "up"
                Log.i("ChatRepository", "PerformScroll: scrollUp=$scrollUp")
                Action.PerformScroll(scrollUp)
            }
            
            // 뒤로 가기
            "go_back" -> {
                Log.i("ChatRepository", "PerformGoBack")
                Action.PerformGoBack
            }
            
            // 홈으로 가기
            "go_home" -> {
                Log.i("ChatRepository", "PerformGoHome")
                Action.PerformGoHome
            }
            
            // 앱 실행 (중요: 서버는 app_name을 보냄)
            "open_app" -> {
                val appName = dto.args?.get("app_name")?.jsonPrimitive?.contentOrNull
                    ?: dto.args?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                Log.i("ChatRepository", "PerformOpenApp: appName='$appName'")
                
                // 앱 이름을 패키지명으로 변환
                val packageName = convertAppNameToPackage(appName)
                Action.PerformOpenApp(packageName)
            }
            
            // 롱 클릭
            "long_press_at" -> {
                val x = dto.args?.get("x")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val y = dto.args?.get("y")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val scaledCoord = scaleCoordinates(x, y, context)
                Log.i("ChatRepository", "PerformLongPress: ($x, $y), scaled: ($scaledCoord)")
                Action.PerformLongPress(scaledCoord.first, scaledCoord.second)
            }
            
            // 텍스트 찾아 스크롤
            "scroll_to_text" -> {
                val text = dto.args?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                Log.i("ChatRepository", "PerformScrollToText: text='$text'")
                Action.PerformScrollToText(text)
            }
            
            // 스와이프
            "swipe" -> {
                val startX = dto.args?.get("start_x")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val startY = dto.args?.get("start_y")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val endX = dto.args?.get("end_x")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val endY = dto.args?.get("end_y")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                
                // 수평 스와이프인지 판단
                val isHorizontal = kotlin.math.abs(startY - endY) < 100
                if (isHorizontal) {
                    val swipeRight = endX > startX
                    Log.i("ChatRepository", "PerformHorizontalSwipe: swipeRight=$swipeRight")
                    Action.PerformHorizontalSwipe(swipeRight)
                } else {
                    val scrollUp = endY < startY
                    Log.i("ChatRepository", "PerformScroll (from swipe): scrollUp=$scrollUp")
                    Action.PerformScroll(scrollUp)
                }
            }
            
            // 대기
            "wait_5_seconds" -> {
                Log.i("ChatRepository", "PerformWait: 5000ms")
                Action.PerformWait(5000L)
            }
            
            // 알 수 없는 액션
            else -> {
                Log.w("ChatRepository", "Unknown action: ${dto.action}, defaulting to no-op")
                Action.PerformWait(100L)
            }
        }
    }

    // =========================
    // myLLM의 AccessibilityService 내부 혹은 유틸리티 클래스
    // =========================

    /**
     * 앱 이름(한글/영문)을 Android 패키지명으로 변환
     */
    private fun convertAppNameToPackage(appName: String): String {
        return when(appName.lowercase().trim()) {
            "날씨", "weather" -> "com.google.android.googlequicksearchbox"
            "카메라", "camera" -> "com.android.camera2"
            "갤러리", "gallery", "사진" -> "com.google.android.apps.photos"
            "설정", "settings" -> "com.android.settings"
            "크롬", "chrome", "브라우저" -> "com.android.chrome"
            "유튜브", "youtube" -> "com.google.android.youtube"
            "지도", "maps", "map" -> "com.google.android.apps.maps"
            "카카오톡", "kakaotalk" -> "com.kakao.talk"
            "메시지", "message", "문자" -> "com.google.android.apps.messaging"
            "전화", "phone" -> "com.google.android.dialer"
            "시계", "clock" -> "com.google.android.deskclock"
            "계산기", "calculator" -> "com.google.android.calculator"
            else -> {
                if (appName.contains(".")) {
                    appName
                } else {
                    Log.w("ChatRepository", "Unknown app name: $appName, using default")
                    "com.android.settings"
                }
            }
        }
    }

    private fun scaleCoordinates(agentX: Float, agentY: Float, context: Context): Pair<Float, Float> {
        // 1. 기기의 실제 화면 해상도 가져오기
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 2. 비율 계산 및 변환 (Float 연산 후 Int로 반올림)
        val realX = (agentX / ScreenCaptureService.scale).toFloat()
        val realY = (agentY / ScreenCaptureService.scale).toFloat()

        Log.d("ChatRepository", "좌표 변환: ($agentX, $agentY) -> ($realX, $realY) [해상도: ${screenWidth}x${screenHeight}]")

        return Pair(realX, realY)
    }


    // Bitmap을 네트워크 전송용 Multipart로 변환
    private fun bitmapToMultipartPart(
        bitmap: Bitmap,
        partName: String = "screenshot", // 서버가 기대하는 파트 이름 (FastAPI 예시의 'screenshot')
        fileName: String = "${UserService.getUserId()}_${System.currentTimeMillis()}.jpg",
        mediaType: String = "image/jpeg"
    ): MultipartBody.Part {
        val byteArrayOutputStream = ByteArrayOutputStream()

        // JPEG 포맷으로 압축
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
        val imageBytes = byteArrayOutputStream.toByteArray()

        val requestBody = imageBytes.toRequestBody(mediaType.toMediaTypeOrNull(), 0, imageBytes.size)

        return MultipartBody.Part.createFormData(partName, fileName, requestBody)
    }

}