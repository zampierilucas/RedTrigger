package com.redtrigger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.redtrigger.ui.MainScreen
import com.redtrigger.ui.theme.RedTriggerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        DebugLog.log("Activity", "MainActivity created")
        
        val triggersEnabled = TriggerManager.isTriggersEnabled(this)
        val shizukuOk = try {
            rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        
        DebugLog.log("Activity", "Triggers currently enabled in system: $triggersEnabled")
        DebugLog.log("Activity", "Shizuku Ready: $shizukuOk, Watchdog: ${TriggerService.isRunning}")
        
        // Auto-start service if triggers are enabled and Shizuku is ready.
        // We don't check hasPermission here because the service itself will auto-grant it via Shizuku.
        if (shizukuOk && triggersEnabled && !TriggerService.isRunning) {
            DebugLog.log("Activity", "Prerequisites met, auto-starting service")
            startForegroundService(android.content.Intent(this, TriggerService::class.java))
        }
        
        setContent {
            RedTriggerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}
