package com.example.myllm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.myllm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.graphics.createBitmap
import com.example.myllm.network.AgentResponseDto
import com.example.myllm.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Response
import kotlin.coroutines.resume

// 이 서비스는 화면 캡처를 위해 Foreground Service로 실행되어야 한다.
class ScreenCaptureService : Service() {

    private val TAG = "CaptureService"
    private val NOTIFICATION_CHANNEL_ID = "ScreenCaptureChannel"
    private val NOTIFICATION_ID = 101

    // MediaProjection 관련 변수
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // 화면 크기 및 DPI 정보
    val scale = 0.5f
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensityDpi: Int = 0

    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any() // 스레드 안전을 위한 락

    /**
     * ===========================
     * serviceScope 관리 코드
     * ===========================
     */
    // Background에서 실행하기 위한 코루틴 스코프
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 루프 실행을 위한 Job 관리
    private var agentJob: Job? = null

    /**
     * Background에서 실행하기 위해 serviceScope에서 iteration을 실행하는 함수
     */
    fun startAgentLoop(repository: ChatRepository, resultCode: Int, data: Intent): Result<AgentResponseDto>? {
        // 이미 실행 중인 루프가 있다면 취소
        agentJob?.cancel()

        var response: Result<AgentResponseDto>? = null
        agentJob = serviceScope.launch {
            response = repository.startAgentIteration(resultCode, data)
        }
        Log.d("ScreenCaptureService-LoopEnd", "$response")
        return response
    }

    private val binder = LocalBinder()
    inner class LocalBinder() : Binder(){
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }
    val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // CLEAN UP RESOURCES HERE
            mediaProjection?.stop()
            mediaProjection = null
            super.onStop()
        }
    }

    // windowManager 초기화(화면 정보 초기화)
    override fun onCreate() {
        super.onCreate()

        // 화면 크기 정보 초기화
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        screenDensityDpi = resources.configuration.densityDpi
        // 최신 API 버전에 따라 다른 방식 사용
        // 최신 api(버전 30이상) => DisplayMetrics 사용
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let {
                screenWidth = it.width()
                screenHeight = it.height()
            }
            screenDensityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensityDpi = metrics.densityDpi
        }
    }

    // isCapturing 변수로 캡쳐 실행
    // false: 캡쳐, true면 캡쳐 중
    // MediaProjection 객체 생성해서 캡쳐
    override fun onBind(intent: Intent?): IBinder = binder

    fun initializeProjection(resultCode: Int, data: Intent){
        if (mediaProjection != null) return // 한 번만 생성
        
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // 안드로이드 14(API 34) 이상에서 registerCallback 등록 안하면 시스템에서 앱 강제 종료함
        // Android 14 서비스 타입을 명시하여 포그라운드 시작
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode,data)
        mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))

        val scaledWidth = (screenWidth * scale).toInt()
        val scaledHeight = (screenHeight * scale).toInt()
        val scaledDpi = (screenDensityDpi* scale).toInt()

        imageReader = ImageReader.newInstance(
            scaledWidth, scaledHeight,
            PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            scaledWidth, scaledHeight,
            scaledDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null // Handler
        )
        imageReader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bitmap = processImage(image)
                synchronized(bitmapLock) {
                    // 이전 비트맵 메모리 해제 (선택 사항)
                    // latestBitmap?.recycle()
                    latestBitmap = bitmap
                }
//                Log.d("ScreenCaptureService", "bitmap successfully captured: $image")
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "캡처 중 오류: ${e.message}")
            } finally {
                image.close()
            }
        }, Handler(Looper.getMainLooper()))

    }

    fun captureCurrentScreen(): Bitmap? {
        synchronized(bitmapLock) {
            return latestBitmap
        }
    }

    // 서비스 리소스 정리
    private fun cleanupResources() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
    }

    override fun onDestroy() {
        super.onDestroy()
        agentJob?.cancel()
        cleanupResources()
        Log.i(TAG, "ScreenCaptureService onDestroy")
    }

    private fun processImage(image: Image): Bitmap?{

        val scaledWidth = (screenWidth * scale).toInt()
        val scaledHeight = (screenHeight * scale).toInt()

        return try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * scaledWidth

            val bmp = createBitmap(scaledWidth + rowPadding / pixelStride, scaledHeight)
            bmp.copyPixelsFromBuffer(buffer)

            // 실제 이미지 영역만 잘라내기
            Bitmap.createBitmap(bmp, 0, 0, scaledWidth, scaledHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Image to Bitmap conversion failed: ${e.message}", e)
            null
        }
    }

    // Foreground Service를 위한 알림 생성
    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "화면 캡처 서비스",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Agent AI 서비스")
            .setContentText("백그라운드에서 화면 캡처 및 분석을 준비 중입니다.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 프로젝트에 맞는 아이콘 리소스로 변경 필요
            .build()
    }
}