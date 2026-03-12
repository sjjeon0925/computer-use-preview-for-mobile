package com.example.myllm.utils

import android.graphics.Bitmap
import android.util.Log
import com.example.myllm.data.Action
import com.example.myllm.network.AgentResponseDto
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import kotlin.math.abs

object TaskLoopUtils {
    fun scaleCoordinates(agentX: Float, agentY: Float, screenWidth: Int, screenHeight: Int, scale: Float): Pair<Float, Float> {
        // 비율 계산 및 변환 (Float 연산 후 Int로 반올림)
        val base = 1000f
        val realX = agentX / base * screenWidth
        val realY = agentY / base * screenHeight

        Log.d("ScreenCapture", "좌표 변환: ($agentX, $agentY) -> ($realX, $realY) [해상도: ${screenWidth}x${screenHeight}]")
        return Pair(realX, realY)
    }

    // Bitmap을 네트워크 전송용 Multipart로 변환
    fun bitmapToMultipartPart(
        bitmap: Bitmap,
        userId: String,
        partName: String = "screenshot", // 서버가 기대하는 파트 이름 (FastAPI 예시의 'screenshot')
        mediaType: String = "image/jpeg"
    ): MultipartBody.Part {
        val fileName = "${userId}_${System.currentTimeMillis()}.jpg"
        val byteArrayOutputStream = ByteArrayOutputStream()

        // JPEG 포맷으로 압축
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
        val imageBytes = byteArrayOutputStream.toByteArray()
        val requestBody = imageBytes.toRequestBody(mediaType.toMediaTypeOrNull(), 0, imageBytes.size)

        return MultipartBody.Part.createFormData(partName, fileName, requestBody)
    }

    fun parseAction(dto: AgentResponseDto, screenWidth:Int, screenHieght: Int, scale: Float) : Action {
        return when(dto.action) {
            // 클릭 액션
            "click_at" -> {
                val x = dto.args?.get("x")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val y = dto.args?.get("y")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val scaledCoord = scaleCoordinates(x, y, screenWidth, screenHieght, scale)
                Log.i("ChatRepository", "ClickAt: ($x, $y), scaled: ($scaledCoord)")
                Action.ClickAt(scaledCoord.first, scaledCoord.second)
            }

            // 텍스트 입력 액션
            "type_text_at" -> {
                val x = dto.args?.get("x")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val y = dto.args?.get("y")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val text = dto.args?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                val scaledCoord = scaleCoordinates(x, y, screenWidth, screenHieght, scale)
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
                val scaledCoord = scaleCoordinates(x, y, screenWidth, screenHieght, scale)
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
                val isHorizontal = abs(startY - endY) < 100
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
    fun convertAppNameToPackage(appName: String): String {
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

}