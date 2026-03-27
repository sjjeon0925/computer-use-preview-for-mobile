package com.example.myllm.repository

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.myllm.service.UserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

@Serializable
data class SendUserInputInWebsocket(
    val type: String,
    val data: String?
)
// MyRepository에서 sharedFlow로 데이터를 전달할때 사용하는 sealed class
// VoiceAgentManager -> ChatRepository -> ChatViewModel -> 각종 UI로 반영
sealed class VoiceEvent {
    data class TaskTriggered(val taskDesc: String): VoiceEvent()
    data class ChatMessage(val response: String, val isUser: Boolean): VoiceEvent()
    object StopStreaming: VoiceEvent()
    object AbortTask: VoiceEvent()
}

class VoiceAgentManager(
    private val serverUrl: String = "ws://10.0.2.2:8000/ws/voice/",
    private val onTaskTriggered: suspend (String) -> Unit,
    private val onChatMessage: suspend (String, Boolean) -> Unit,
    private val onStopStreaming: suspend () -> Unit,
    private val onAbortTask: suspend () -> Unit
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    var isStreaming = false
    private val webSocketScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 마이크 입력 설정 (Gemini Live API 권장)
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // STT 스크립트
    private var inputAudioTranscript = ""
    private var outputAudioTranscript = ""

    // ─── 오디오 출력: TURN_COMPLETE 시 한번에 재생 ────────────────────────
    private val outputSampleRate = 24000  // Gemini Live API 출력 기본값
    private val pendingAudioChunks = mutableListOf<ByteArray>()
    private var audioTrack: AudioTrack? = null

    /**
     * 누적된 PCM 청크들을 하나로 합쳐 AudioTrack으로 재생.
     * TURN_COMPLETE 수신 시 호출된다.
     */
    private fun playAccumulatedAudio() {
        val chunks: List<ByteArray> = synchronized(pendingAudioChunks) {
            if (pendingAudioChunks.isEmpty()) return
            pendingAudioChunks.toList().also { pendingAudioChunks.clear() }
        }

        val totalSize = chunks.sumOf { it.size }
        val pcm = ByteArray(totalSize)
        var pos = 0
        for (chunk in chunks) {
            chunk.copyInto(pcm, pos)
            pos += chunk.size
        }

        val minBuf = AudioTrack.getMinBufferSize(
            outputSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setSampleRate(outputSampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBuf, totalSize),
            AudioTrack.MODE_STATIC,  // 전체 데이터를 미리 로드 후 재생
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        if (audioTrack!!.state == AudioTrack.STATE_UNINITIALIZED) {
            Log.e("AudioPlayer", "AudioTrack 초기화 실패")
            return
        }

        audioTrack!!.write(pcm, 0, pcm.size)
        audioTrack!!.play()
        Log.d("AudioPlayer", "재생 시작: ${totalSize}bytes (${totalSize / 2 / outputSampleRate.toFloat()}초)")
    }

    /** INTERRUPTED 수신 시: 누적 청크 버리고 재생 중단 */
    private fun clearPendingAudio() {
        synchronized(pendingAudioChunks) { pendingAudioChunks.clear() }
        audioTrack?.pause()
        audioTrack?.flush()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun startStreaming() {
        pendingAudioChunks.clear()

        val request = Request.Builder()
            .url("$serverUrl${UserService.getUserId()}")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                startAudioCapture()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocketScope.launch {
                    parsingResponse(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                webSocketScope.launch {
                    onChatMessage("Connection Failed: ${t.message}", false)
                }
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
                    sendByteStringOnWebSocket(buffer.copyOfRange(0, read).toByteString())
                }
            }
        }.start()
    }

    private suspend fun parsingResponse(text: String) {
        try {
            val json = Json.parseToJsonElement(text).jsonObject

            when (json["type"]?.jsonPrimitive?.content) {
                "SETUP_COMPLETE" -> {
                    inputAudioTranscript = ""
                    outputAudioTranscript = ""
                }
                "CONTROL" -> {
                    when (json["action"]?.jsonPrimitive?.content) {
                        "START_TASK" -> {
                            val taskDesc = json["message"]?.jsonPrimitive?.content ?: "Task Start"
                            onTaskTriggered(taskDesc)
                        }
                        "ABORT_TASK" -> {
                            clearPendingAudio()
                            onAbortTask()
                        }
                    }
                }
                "INTERRUPTED" -> {
                    clearPendingAudio()
                    outputAudioTranscript = ""
                }
                "INPUT_TRANSCRIPTION" -> {
                    inputAudioTranscript += json["text"]?.jsonPrimitive?.content ?: ""
                    val finished = json["finished"]?.jsonPrimitive?.boolean ?: false
                    if (finished) {
                        onChatMessage(inputAudioTranscript, true)
                        inputAudioTranscript = ""
                    }
                }
                "OUTPUT_TRANSCRIPTION" -> {
                    outputAudioTranscript += json["text"]?.jsonPrimitive?.content ?: ""
                }
                "AUDIO" -> {
                    val audioData = json["data"]?.jsonPrimitive?.contentOrNull
                    if (audioData != null) {
                        accumulateAudio(audioData)  // 청크 누적
                    }
                }
                "TURN_COMPLETE" -> {
                    playAccumulatedAudio()
                    if (outputAudioTranscript.isNotEmpty()) {
                        onChatMessage(outputAudioTranscript, false)
                        outputAudioTranscript = ""
                    }
                }
                "RESPONSE" -> {
                    // TODO: 서버 텍스트 응답 처리
                }
                else -> {
                    Log.d("VoiceAgent", "Unknown type: ${json["type"]}")
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceAgent", "Error parsing: $text", e)
        }
    }

    /** base64 → PCM 디코딩 후 누적 리스트에 추가 */
    private fun accumulateAudio(base64Data: String) {
        val pcmBytes: ByteArray = try {
            Base64.decode(base64Data, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Base64 decode 실패: ${e.message}")
            return
        }
        synchronized(pendingAudioChunks) { pendingAudioChunks.add(pcmBytes) }
    }

    fun sendTextOnWebSocket(message: String){
        val json = SendUserInputInWebsocket(type="TEXT", data=message)
        val jsonString = Json.encodeToString(json)
        webSocket?.send(jsonString)
    }

    fun sendByteStringOnWebSocket(byteString: ByteString) {
        // PCM 바이트를 base64로 인코딩 후 Gemini Live API 포맷의 JSON 문자열로 전송
        // 서버는 이 JSON을 그대로 Gemini에 proxy함
        val b64 = Base64.encodeToString(byteString.toByteArray(), Base64.NO_WRAP)
        val json = SendUserInputInWebsocket(type="AUDIO", data=b64)
        val jsonString = Json.encodeToString(json)
        webSocket?.send(jsonString)
    }

    fun stopStreaming() {
        isStreaming = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        webSocket?.close(1000, "User Stopped")
        pendingAudioChunks.clear()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}