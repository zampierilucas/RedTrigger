package com.redtrigger

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

object TriggerManager {
    private const val TAG = "TriggerManager"
    private const val NUBIA_GAME_SCENE = "nubia_game_scene"
    private const val NUBIA_GAME_MODE = "nubia_game_mode"
    private const val CC_GAME_MIS_OPERATE = "cc_game_mis_operate"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * Check if the app has WRITE_SECURE_SETTINGS permission
     */
    fun hasWriteSecureSettings(context: Context): Boolean {
        return try {
            context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WRITE_SECURE_SETTINGS permission", e)
            false
        }
    }

    /**
     * Check if triggers are currently enabled.
     */
    fun isTriggersEnabled(context: Context): Boolean {
        return try {
            val gameScene = Settings.Global.getInt(context.contentResolver, NUBIA_GAME_SCENE, 0)
            gameScene == 1
        } catch (e: Exception) {
            Log.e(TAG, "Error reading trigger settings", e)
            false
        }
    }

    /**
     * Enable shoulder triggers without activating full game mode.
     *
     * nubia_game_scene=1    → activates the SAR capacitive sensors
     * nubia_game_mode=1     → minimal game mode flag (just bit 0)
     * cc_game_mis_operate=0 → disables "swipe again" gesture blocking
     *
     * virtual_game_key is NOT set — it launches Game Space which hijacks
     * the home launcher and causes fullscreen/notification suppression.
     */
    fun enableTriggers(context: Context): Boolean {
        return try {
            // Start foreground service first — it connects Shizuku which auto-grants
            // WRITE_SECURE_SETTINGS, then applyTriggerSettings() is called once connected.
            context.startForegroundService(Intent(context, TriggerService::class.java))
            DebugLog.log("Enable", "Starting foreground service")

            // Try to apply settings now (works if permission already granted)
            applyTriggerSettings(context)

            Log.i(TAG, "Triggers enabled + service started")
            true
        } catch (e: Exception) {
            DebugLog.log("Enable", "Error: ${e.message}")
            Log.e(TAG, "Error enabling triggers", e)
            false
        }
    }

    /**
     * Apply trigger settings. Called from enableTriggers() and again from
     * InputReader after Shizuku grants the permission.
     */
    fun applyTriggerSettings(context: Context) {
        try {
            if (!hasWriteSecureSettings(context)) {
                DebugLog.log(
                    "Enable",
                    "No WRITE_SECURE_SETTINGS yet, will retry after Shizuku grant"
                )
                return
            }
            Settings.Global.putInt(context.contentResolver, NUBIA_GAME_SCENE, 1)
            DebugLog.log("Enable", "nubia_game_scene=1")
            Settings.Global.putInt(context.contentResolver, NUBIA_GAME_MODE, 1)
            DebugLog.log("Enable", "nubia_game_mode=1")
            Settings.Global.putInt(context.contentResolver, CC_GAME_MIS_OPERATE, 0)
            DebugLog.log("Enable", "cc_game_mis_operate=0")
        } catch (e: Exception) {
            DebugLog.log("Enable", "Apply error: ${e.message}")
            Log.e(TAG, "Error applying trigger settings", e)
        }
    }

    /**
     * Disable triggers by resetting all values to off state.
     */
    fun disableTriggers(context: Context): Boolean {
        return try {
            // Stop the observer service first
            context.stopService(Intent(context, TriggerService::class.java))
            DebugLog.log("Disable", "Service stopped")
            Settings.Global.putInt(context.contentResolver, NUBIA_GAME_SCENE, 0)
            Settings.Global.putInt(context.contentResolver, NUBIA_GAME_MODE, 0)
            DebugLog.log("Disable", "Settings reset to 0")
            Log.i(TAG, "Triggers disabled + service stopped")
            true
        } catch (e: Exception) {
            DebugLog.log("Disable", "Error: ${e.message}")
            Log.e(TAG, "Error disabling triggers", e)
            false
        }
    }

    /**
     * Dump ALL settings (Global, Secure, System) without any filter.
     * Returns a map of "namespace:name" -> value for easy diffing.
     */
    fun dumpAllSettings(context: Context): Map<String, String> {
        val settings = mutableMapOf<String, String>()
        try {
            listOf(
                "global" to Settings.Global.CONTENT_URI,
                "secure" to Settings.Secure.CONTENT_URI,
                "system" to Settings.System.CONTENT_URI
            ).forEach { (namespace, uri) ->
                context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)
                    ?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex("name")
                        val valIdx = cursor.getColumnIndex("value")
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIdx) ?: continue
                            val value = cursor.getString(valIdx) ?: "null"
                            settings["$namespace:$name"] = value
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dumping all settings", e)
        }

        return settings
    }

    /**
     * Compute diff between two settings snapshots.
     * Returns a human-readable string showing added, removed, and changed settings.
     */
    fun diffSettings(before: Map<String, String>, after: Map<String, String>): String {
        val sb = StringBuilder()
        val allKeys = (before.keys + after.keys).sorted()
        var changes = 0

        sb.appendLine("=== Settings Diff (before → after toggle) ===")
        sb.appendLine()

        for (key in allKeys) {
            val bVal = before[key]
            val aVal = after[key]

            when {
                bVal == null && aVal != null -> {
                    sb.appendLine("+ $key = $aVal")
                    changes++
                }

                bVal != null && aVal == null -> {
                    sb.appendLine("- $key = $bVal")
                    changes++
                }

                bVal != aVal -> {
                    sb.appendLine("~ $key: $bVal → $aVal")
                    changes++
                }
            }
        }

        if (changes == 0) {
            sb.appendLine("(no changes)")
        } else {
            sb.appendLine()
            sb.appendLine("Total: $changes changed settings")
        }

        return sb.toString()
    }

    /**
     * Check if KeyMapper is installed
     */
    fun isKeyMapperInstalled(context: Context): Boolean {
        val keyMapperPackages = listOf(
            "io.github.sds100.keymapper",
            "io.github.sds100.keymapper.debug"
        )

        return keyMapperPackages.any { packageName ->
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }


    fun arePrerequisitesMet(context: Context): Boolean {
        return isShizukuInstalled(context) && isShizukuRunning() && isShizukuPermission()
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun isShizukuPermission(): Boolean {
        return if (isShizukuRunning()) {
            try {
                rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        } else false
    }
}
