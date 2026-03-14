package com.redtrigger

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app debug log buffer. All components write here for UI display.
 */
object DebugLog {
    private const val MAX_ENTRIES = 200
    private val entries = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var version = 0L
        private set

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val isError = "ERROR" in message || "FAIL" in message || "Exception" in message
        val entry = "$timestamp [$tag] $message"
        synchronized(entries) {
            if (isError) {
                // Errors go to the front so they're visible when pasting
                entries.add(0, "⚠ $entry")
            } else {
                entries.add(entry)
            }
            if (entries.size > MAX_ENTRIES) {
                entries.removeAt(if (isError) entries.size - 1 else 0)
            }
            version++
        }
        if (isError) {
            Log.e("RedTrigger", "[$tag] $message")
        } else {
            Log.d("RedTrigger", "[$tag] $message")
        }
    }

    fun getEntries(): List<String> {
        synchronized(entries) {
            return entries.toList()
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            version++
        }
    }
}
