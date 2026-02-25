package com.example.myllm.service

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.Nullable
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit


class VoiceAgentManager(
    private val serverUrl: String = "ws://10.0.2.2:8000/ws/voice/",
    private val onTaskTriggered: (String) -> Unit, // Task 실행 콜백
    private val onResponseReceived: (String) -> Unit // 일반 대화 응답 콜백
) {
    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 연결 유지를 위해 타임아웃 해제
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    var isStreaming = false

    // Gemini Live API 권장 오디오 설정
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    fun startStreaming() {
        val request = Request.Builder()
            .url("$serverUrl${UserService.getUserId()}")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                startAudioCapture()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try{
                    val json = Json.parseToJsonElement(text).jsonObject
                    Log.d("webSocket onMessage", json.toString())

                    // 서버에서 보낸 제어 신호 감지
                    if (json["type"]?.jsonPrimitive?.content == "CONTROL") {
                        if (json["action"]?.jsonPrimitive?.content == "START_TASK") {
                            val taskDesc = json["message"]?.jsonPrimitive?.content ?: "Task Start"
                            stopStreaming() // Task 수행 중 마이크 일시 중지
                            onTaskTriggered(taskDesc)
                        }
                    } else {
                        // 일반 응답 처리
                        onResponseReceived(json["message"]?.jsonPrimitive?.content ?: "")
                    }
                }catch (e: Exception){
                    Log.e("VoiceAgent", "Error parsing line: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("webSocket onFailure", "Connection Failed: ${t.message}")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startAudioCapture() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat, bufferSize
        )

        audioRecord?.startRecording()
        isStreaming = true

        Thread {
            val buffer = ByteArray(bufferSize)
            while (isStreaming) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // 원시 바이트 데이터를 서버로 전송
                    webSocket?.send(buffer.copyOfRange(0, read).toByteString())
                }
            }
        }.start()
    }

    fun stopStreaming() {
        isStreaming = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        webSocket?.close(1000, "User Stopped")
    }
}