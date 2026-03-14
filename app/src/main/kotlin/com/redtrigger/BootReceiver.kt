package com.redtrigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val PREFS_NAME = "RedTriggerPrefs"
        private const val KEY_AUTO_ENABLE = "auto_enable_on_boot"
        
        fun isAutoEnableEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_AUTO_ENABLE, false) // Default to false for new users
        }

        fun setAutoEnable(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_AUTO_ENABLE, enabled).apply()
            DebugLog.log("Boot", "Auto-enable on boot set to: $enabled")
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        context?.let {
            val prefs = it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoEnable = prefs.getBoolean(KEY_AUTO_ENABLE, true)

            DebugLog.log("Boot", "BOOT_COMPLETED received, autoEnable=$autoEnable")

            if (autoEnable) {
                val success = TriggerManager.enableTriggers(it)
                DebugLog.log("Boot", "Triggers re-enabled: $success")
            } else {
                DebugLog.log("Boot", "Skipped: autoEnable=$autoEnable")
            }
        }
    }
}
