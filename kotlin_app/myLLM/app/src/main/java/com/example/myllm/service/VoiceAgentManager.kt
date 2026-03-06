package com.example.myllm.service

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import android.util.Base64
import okio.ByteString
import java.util.concurrent.TimeUnit

class VoiceAgentManager(
    private val serverUrl: String = "ws://10.0.2.2:8000/ws/voice/",
    private val onTaskTriggered: (String) -> Unit,
    private val OnChatMessage: (String, Boolean) -> Unit,
    private val OnStopStreaming: () -> Unit
) {
    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    var isStreaming = false

    // 마이크 입력 설정 (Gemini Live API 권장)
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // STT 스크립트
    private var InputAudioTranscript = ""
    private var OutputAudioTranscript = ""

    // ─── 오디오 출력: TURN_COMPLETE 시 한번에 재생 ────────────────────────
    private val outputSampleRate = 24000  // Gemini Live API 출력 기본값
    private val pendingAudioChunks = mutableListOf<ByteArray>()
    private var audioTrack: AudioTrack? = null

    /**
     * 누적된 PCM 청크들을 하나로 합쳐 AudioTrack으로 재생.
     * TURN_COMPLETE 수신 시 호출된다.
     */
    private fun playAccumulatedAudio() {
        if (pendingAudioChunks.isEmpty()) return

        // 모든 청크를 하나의 ByteArray로 합침
        val totalSize = pendingAudioChunks.sumOf { it.size }
        val pcm = ByteArray(totalSize)
        var pos = 0
        for (chunk in pendingAudioChunks) {
            chunk.copyInto(pcm, pos)
            pos += chunk.size
        }
        pendingAudioChunks.clear()

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
        pendingAudioChunks.clear()
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
                parsingResponse(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                OnChatMessage("Connection Failed: ${t.message}", false)
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

    private fun parsingResponse(text: String) {
        try {
            val json = Json.parseToJsonElement(text).jsonObject

            when (json["type"]?.jsonPrimitive?.content) {
                "SETUP_COMPLETE" -> {
                    InputAudioTranscript = ""
                    OutputAudioTranscript = ""
                }
                "CONTROL" -> {
                    if (json["action"]?.jsonPrimitive?.content == "START_TASK") {
                        val taskDesc = json["message"]?.jsonPrimitive?.content ?: "Task Start"
                        OnStopStreaming()
                        onTaskTriggered(taskDesc)
                    }
                }
                "INTERRUPTED" -> {
                    clearPendingAudio()
                    OutputAudioTranscript = ""
                }
                "INPUT_TRANSCRIPTION" -> {
                    InputAudioTranscript += json["text"]?.jsonPrimitive?.content ?: ""
                    val finished = json["finished"]?.jsonPrimitive?.boolean ?: false
                    if (finished) {
                        OnChatMessage(InputAudioTranscript, true)
                        InputAudioTranscript = ""
                    }
                }
                "OUTPUT_TRANSCRIPTION" -> {
                    OutputAudioTranscript += json["text"]?.jsonPrimitive?.content ?: ""
                }
                "AUDIO" -> {
                    val audioData = json["data"]?.jsonPrimitive?.contentOrNull
                    if (audioData != null) {
                        accumulateAudio(audioData)  // 청크 누적
                    }
                }
                "TURN_COMPLETE" -> {
                    // 누적된 오디오 전체를 한번에 재생
                    playAccumulatedAudio()
                    if (OutputAudioTranscript.isNotEmpty()) {
                        OnChatMessage(OutputAudioTranscript, false)
                        OutputAudioTranscript = ""
                    }
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
        pendingAudioChunks.add(pcmBytes)
    }

    fun sendTextOnWebSocket(message: String){
        val jsonString = "{\"client_content\": {\"turns\": [{\"role\": \"user\",\"parts\": [{ \"text\": \"$message\" }]}],\"turn_complete\": \"true\" } }"
        webSocket?.send(jsonString)
    }

    fun sendByteStringOnWebSocket(byteString: ByteString) {
        // PCM 바이트를 base64로 인코딩 후 Gemini Live API 포맷의 JSON 문자열로 전송
        // 서버는 이 JSON을 그대로 Gemini에 proxy함
        val b64 = Base64.encodeToString(byteString.toByteArray(), Base64.NO_WRAP)
        val json = """{"realtime_input":{"media_chunks":[{"data":"$b64","mime_type":"audio/pcm"}]}}"""
        webSocket?.send(json)
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