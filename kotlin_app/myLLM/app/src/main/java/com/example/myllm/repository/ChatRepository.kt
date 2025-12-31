package com.example.myllm.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.myllm.data.Action
import com.example.myllm.network.AgentResponseDto
import com.example.myllm.network.ApiService
import com.example.myllm.network.NetworkClient
import com.example.myllm.service.ActionController
import com.example.myllm.service.UserService
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class ChatRepository(
    private val apiService: ApiService = NetworkClient.service // 기존 싱글톤 사용
) {
    suspend fun processUserMessage(userInput: String): Result<AgentResponseDto> {
        return try {
            // 서버에 텍스트 전송 (Network)
            val response = NetworkClient.service.sendMessage(userInput, UserService.getUserId())
            if(response.isSuccessful){
                val body = response.body()
                if(body != null){
                    if(body.type == "ACTION"){
                        val action = parseAction(body)
                        ActionController.sendAction(action)
                    }
                    Log.i("ChatRepository", "Form 전송 성공: ${response.code()}")
                    Result.success(body)
                } else{
                    Result.failure(Exception("Empty Response Body"))
                }
            }else{
                Log.e("ChatRepository", "Form 전송 실패: ${response.code()}")
                Result.failure(Exception("Error Code: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Chat 오류: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseAction(dto: AgentResponseDto) : Action {
        Log.d("ChatRepository", "Parsing action: ${dto.action}, args: ${dto.args}")
        
        return when(dto.action) {
            // 클릭 액션
            "click_at" -> {
                val x = dto.args?.get("x")?.toFloatOrNull() ?: 0f
                val y = dto.args?.get("y")?.toFloatOrNull() ?: 0f
                Log.i("ChatRepository", "ClickAt: ($x, $y)")
                Action.ClickAt(x, y)
            }
            
            // 텍스트 입력 액션
            "type_text_at" -> {
                val x = dto.args?.get("x")?.toFloatOrNull() ?: 0f
                val y = dto.args?.get("y")?.toFloatOrNull() ?: 0f
                val text = dto.args?.get("text") ?: ""
                Log.i("ChatRepository", "ClickAndTypeText: ($x, $y) text='$text'")
                Action.ClickAndTypeText(x, y, text)
            }
            
            // 스크롤 액션
            "scroll_at" -> {
                val direction = dto.args?.get("direction") ?: "up"
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
                val appName = dto.args?.get("app_name") ?: dto.args?.get("name") ?: ""
                Log.i("ChatRepository", "PerformOpenApp: appName='$appName'")
                
                // 앱 이름을 패키지명으로 변환
                val packageName = convertAppNameToPackage(appName)
                Action.PerformOpenApp(packageName)
            }
            
            // 롱 클릭
            "long_press_at" -> {
                val x = dto.args?.get("x")?.toFloatOrNull() ?: 0f
                val y = dto.args?.get("y")?.toFloatOrNull() ?: 0f
                Log.i("ChatRepository", "PerformLongPress: ($x, $y)")
                Action.PerformLongPress(x, y)
            }
            
            // 텍스트 찾아 스크롤
            "scroll_to_text" -> {
                val text = dto.args?.get("text") ?: ""
                Log.i("ChatRepository", "PerformScrollToText: text='$text'")
                Action.PerformScrollToText(text)
            }
            
            // 스와이프
            "swipe" -> {
                val startX = dto.args?.get("start_x")?.toFloatOrNull() ?: 0f
                val startY = dto.args?.get("start_y")?.toFloatOrNull() ?: 0f
                val endX = dto.args?.get("end_x")?.toFloatOrNull() ?: 0f
                val endY = dto.args?.get("end_y")?.toFloatOrNull() ?: 0f
                
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


     // 스크린샷 이미지와 컨텍스트를 서버로 업로드 (Service에서 호출)
    suspend fun uploadScreenCapture(bitmap: Bitmap, activityContext: String): Result<AgentResponseDto> {
        return try {
            // Bitmap을 MultipartBody.Part로 변환
            val filePart = bitmapToMultipartPart(bitmap)

            // Activity 컨텍스트를 포함
            val contextstr = "<state><app name='${activityContext}'/></state>"
            val contextBody = contextstr.toRequestBody("text/plain".toMediaTypeOrNull())
            val sessionIdBody = UserService.getUserId().toRequestBody("text/plain".toMediaTypeOrNull())

            val agentResponse = NetworkClient.service.sendStepMultipart(filePart, contextBody,
                sessionIdBody)

            if(agentResponse.isSuccessful){
                val body = agentResponse.body()
                if(body != null){
                    if (body.type == "ACTION") {
                        ActionController.sendAction(parseAction(body))
                    }
                    Log.i("ChatRepository", "Image Form 전송 성공: ${body.message}")
                    Result.success(body)
                }else{
                    Result.failure(Exception("Empty Response"))
                }
            }else{
                Log.e("ChatViewModel", "Image Form 전송 실패: ${agentResponse.code()}")
                Result.failure(Exception("Upload Failed: ${agentResponse.code()}"))
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "스크린샷 업로드 통신 오류: ${e.message}", e)
            Result.failure(e)
        }
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