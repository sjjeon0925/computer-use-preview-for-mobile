package com.example.myllm

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.myllm.service.UserService
import com.example.myllm.ui.theme.MyLLMTheme
import com.example.myllm.view.navigation.AppNavigation
import android.provider.Settings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        UserService.initializeUser()

        setContent {
            MyLLMTheme {
                // 내비게이션 Composable만 호출
                AppNavigation()
            }
        }
    }
}