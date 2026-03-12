package com.example.myllm.view.screens

import android.Manifest
import android.R.attr.enabled
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.example.myllm.data.AppChatMessage
import com.example.myllm.ui.theme.MyLLMTheme
import com.example.myllm.viewmodel.ChatViewModel

// 채팅 화면 Composable
@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current

    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Companion.ChatViewModelFactory(
            context,
            isPreview
        )
    )

    val userInput = viewModel.userInput
    val messages = viewModel.messages
    val isLoading = viewModel.isLoading

    val mediaProjectionManager :MediaProjectionManager? = remember {
        if(isPreview) null
        else context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // 런처 정의: 시스템 팝업을 띄우고, 사용자가 승인했는지 거절했는지에 대한 결과(Intent data)를 받을 통로 정의.
    // rememberLauncherForActivityResult: 외부 액티비티로부터 결과를 받아오기 위한 Contract 객체
    val CapturePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d("ChatScreen", "MediaProjection 권한 승인됨. 서비스 시작 요청.")
            viewModel.onProjectionPermissionResult(resultCode = result.resultCode, data = result.data!!)
        } else {
            Log.e("ChatScreen", "MediaProjection 권한 거부됨.")
            // TODO: 권한 거부 시 로딩 상태 해제 등의 추가 처리 필요 (ViewModel에서 처리할 수도 있음)
        }
        viewModel.onCaptureRequestHandled()
    }

    // LaunchedEffect: Compose 화면이 나타날 때(또는 특정 변수가 변할 때) 실행되는 Coroutine 블록,
    // isCaptureRequested의 변화에 반응
    LaunchedEffect(viewModel.isCaptureRequested) {
        if (viewModel.isCaptureRequested) {
            Log.d("ChatScreen", "ViewModel isCaptureRequested 플래그 감지: 스크린샷 권한 요청 시작.")

            // MediaProjectionManager를 통해 권한 요청 Intent 생성
            val intent = mediaProjectionManager?.createScreenCaptureIntent()
            CapturePermissionLauncher.launch(intent)
        }
    }

    val speechPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 권한 허용 시 바로 STT 시작
            viewModel.startStreaming()
        } else {
            // 권한 거부 시 안내 (Toast 등)
            Toast.makeText(context, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("LLM 채팅") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로 가기")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Bottom
            ) {
                items(messages) { message ->
                    ChatBubble(message)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ){
                Button(
                    onClick = {
                        val permissionCheck = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (permissionCheck) {
                            // 이미 권한이 있으면 바로 시작
                            viewModel.startStreaming()
                        } else {
                            // 권한이 없으면 요청
                            speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                        enabled = !(viewModel.isRecording) && !isLoading, // 이미 스트리밍 중이면 비활성화
                        modifier = Modifier.weight(1f).padding(8.dp)
                ){
                    Text("녹음 시작")
                }
                Button(
                    onClick = {
                        viewModel.stopStreaming()
                    },
                    enabled = viewModel.isRecording, // 스트리밍 중일 때만 종료 가능
                    modifier = Modifier.weight(1f).padding(8.dp)
                ){
                    Text("녹음 종료")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = userInput,
                    onValueChange = { viewModel.updateUserInput(it) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    placeholder = { Text("메시지를 입력하세요") },
                    enabled = !isLoading
                )
                Button(
                    onClick = {
                        viewModel.processAndSendText(userInput)
                    },
                    enabled = userInput.isNotBlank() && !isLoading,
                    modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                    Text("전송")
                }
            }
        }
    }
}

// 말풍선 Composable
@Composable
fun ChatBubble(message: AppChatMessage) {
    val alignment = if (message.isUser) Arrangement.End else Arrangement.Start
    val bubbleColor =
        if (message.isUser)
            if(message.isSpeech) Color(0xFFFE7F5C) else Color(0xFF5DB0FE)
        else
            if(message.isSpeech) Color(0xffc3c3c3) else Color(0xFFE0E0E0)
    val textColor = if (message.isUser) Color.White else Color.Black

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = alignment
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = bubbleColor,
                    shape = MaterialTheme.shapes.medium
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = 16.sp,
                textAlign = if (message.isUser) TextAlign.End else TextAlign.Start
            )
        }
    }
}

// 프리뷰
@Preview(showBackground = true)
@Composable
@RequiresApi(Build.VERSION_CODES.S)
fun ChatScreenPreview() {
    MyLLMTheme {
        ChatScreen(navController = rememberNavController())
    }
}