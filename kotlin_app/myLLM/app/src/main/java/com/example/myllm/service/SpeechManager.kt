package com.example.myllm.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.util.Locale
import java.util.concurrent.Executor

class MyRecognitionListener(
    private val onResultCallback: (String) -> Unit,
    private val onErrorCallback: (Int) -> Unit
    ): RecognitionListener{

    private var partialResultString: String = ""

    override fun onBeginningOfSpeech() {
        onStateChanged("speaking...")
    }

    override fun onEndOfSpeech() {
        onStateChanged("end speaking...")
    }

    override fun onError(error: Int) {
        onErrorCallback(error)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        onStateChanged("Listening...")
    }

    override fun onResults(results: Bundle?) {
        val resultmsg = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text: String? =
            try {
                resultmsg?.first()
            }catch (e: Exception){
                ""
            }

        if(text?.isNotEmpty() == true) {
            partialResultString = text
        }
        onResultCallback(partialResultString)
    }

    private fun onStateChanged(state: String){
        Log.d("MyRecognitionListener", state)
    }
    override fun onBufferReceived(buffer: ByteArray?) { }
    override fun onRmsChanged(rmsdB: Float) { }
    override fun onEvent(eventType: Int, params: Bundle?) { }
    override fun onPartialResults(partialResults: Bundle?) {
        val resultmsg = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text: String? =
            try {
                resultmsg?.first()
            }catch (e: Exception){
                ""
            }
        if(text?.isNotEmpty() == true) {
            partialResultString = text
            Log.d("MyRecognitionListener", "Partial Result: $partialResultString")
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
class SpeechManager(private val context: Context) {
    private val speechRecognizer: SpeechRecognizer? by lazy {
        try {
            if(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)){
                Toast.makeText(context, "음성 인식 불가능", Toast.LENGTH_SHORT)
            }
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            null // Preview 환경에서는 null 반환
        }
    }
    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    var isRecording = false
    var onMessageReady: ((String) -> Unit)? = null
    private var isContinuousMode = false
    private var resultText = ""
    private val recognitionListener = MyRecognitionListener(
        onResultCallback = { text ->
            resultText += "$text.\n"
            Log.d("onResultCallback", "resultText: $resultText")
            if (isContinuousMode) {
                startSTT(true)
            }else {
                onMessageReady?.invoke(resultText) // 에이전트로 명령 보내기
                Toast.makeText(context, resultText, Toast.LENGTH_SHORT)
            }
        },
        onErrorCallback = { error ->
            val message: String = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "오디오 에러"
                SpeechRecognizer.ERROR_CLIENT -> "클라이언트 에러"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "퍼미션 없음"
                SpeechRecognizer.ERROR_NETWORK -> "네트워크 에러"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트웍 타임아웃"
                SpeechRecognizer.ERROR_NO_MATCH -> "찾을 수 없음"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER 가 바쁨"
                SpeechRecognizer.ERROR_SERVER -> "서버 에러"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "시간초과"
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "사용 불가능한 언어"
                else -> "알 수 없는 오류: $error"
            }
            Log.e("onError", message)
        }
    )

    fun startSTT(continuous: Boolean = false, callingFromOut: Boolean = false) {
        // 나중에 언어팩 다운받는 로직 추가할 수 있을지도
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            speechRecognizer?.triggerModelDownload(intent)
//        }
        isRecording = true
        if(callingFromOut){
            isContinuousMode = continuous
            resultText = ""
        }
        speechRecognizer?.let {
            speechRecognizer?.setRecognitionListener(recognitionListener)
            speechRecognizer?.startListening(intent)
        }
    }
    /**
     * 녹음 정지
     */
    fun stopSTT() {
        if(isRecording) {
            isRecording = false
            speechRecognizer?.stopListening()
            onMessageReady?.invoke(resultText) // 에이전트로 명령 보내기
            resultText = ""
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
    }
}