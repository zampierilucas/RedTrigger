package com.redtrigger

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/**
 * Shizuku UserService that reads /dev/input events with shell permissions.
 *
 * Runs in Shizuku's process (uid 2000/shell) which has the 'input' group
 * and can read /dev/input/eventX devices directly.
 *
 * Key injection via uinput binary creates a real virtual gamepad device
 * so injected events appear as standard L1/R1 gamepad buttons.
 */
class InputService : IInputService.Stub() {

    companion object {
        private const val TAG = "InputService"
        private const val UINPUT_BINARY_PATH = "/data/local/tmp/redtrigger_uinput"

        // Linux evdev keycodes for gamepad shoulder buttons
        private const val BTN_TL = 310
        private const val BTN_TR = 311
    }

    private val readerProcesses = mutableListOf<Process>()
    private val readerThreads = mutableListOf<Thread>()
    @Volatile private var reading = false
    @Volatile private var injectionEnabled = true
    private var activeCallback: ITriggerCallback? = null

    // uinput injector process
    private var uinputProcess: Process? = null
    private var uinputWriter: BufferedWriter? = null
    @Volatile private var uinputReady = false

    override fun detectDevices(): String {
        return try {
            val devices = mutableListOf<String>()
            val allDeviceNames = mutableListOf<String>()

            val proc = Runtime.getRuntime().exec(arrayOf("getevent", "-pl"))
            val output = proc.inputStream.bufferedReader().readText()
            proc.errorStream.bufferedReader().readText() // drain stderr
            proc.waitFor()

            debugMsg("Detect", "getevent -pl: ${output.length} chars")

            var currentDevice = ""

            for (line in output.lines()) {
                when {
                    line.startsWith("add device") || line.contains("/dev/input/event") -> {
                        currentDevice = Regex("/dev/input/event\\d+").find(line)?.value ?: ""
                    }
                    line.trimStart().startsWith("name:") -> {
                        val name = line.substringAfter("name:").trim().trim('"')
                        if (currentDevice.isNotEmpty()) {
                            allDeviceNames.add("$currentDevice=$name")
                            if (!name.contains("RedTrigger", ignoreCase = true) &&
                                !name.contains("Virtual", ignoreCase = true) &&
                                (name.contains("nubia_tgk_aw_sar", ignoreCase = true) ||
                                 name.contains("sar0", ignoreCase = true) ||
                                 name.contains("sar1", ignoreCase = true))) {
                                if (currentDevice !in devices) {
                                    devices.add(currentDevice)
                                    debugMsg("Detect", "SAR: $name -> $currentDevice")
                                }
                            }
                        }
                    }
                }
            }

            debugMsg("Detect", "All devices: ${allDeviceNames.joinToString(", ")}")

            if (devices.isEmpty()) {
                debugMsg("Detect", "WARNING: No SAR devices found")
            }

            devices.joinToString(",")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect devices", e)
            debugMsg("Detect", "ERROR: ${e.message}")
            ""
        }
    }

    /**
     * Extract and start the uinput injector binary.
     */
    private fun startUinputInjector() {
        try {
            // Kill stale uinput processes from previous runs
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c",
                    "pkill -f redtrigger_uinput 2>/dev/null; pkill -f uinput_injector 2>/dev/null; true")).waitFor()
                Thread.sleep(300) // give kernel time to release uinput fds and destroy devices
            } catch (_: Exception) {}

            extractUinputBinary()

            val proc = Runtime.getRuntime().exec(arrayOf(UINPUT_BINARY_PATH))
            uinputProcess = proc
            uinputWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))

            // Read stdout for READY signal
            thread(name = "uinput-reader", isDaemon = true) {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                        when {
                            line.startsWith("READY") -> {
                                uinputReady = true
                                debugMsg("UInput", "Virtual gamepad $line")
                            }
                            line.startsWith("OK") -> { /* injected ok */ }
                            else -> debugMsg("UInput", line)
                        }
                    }
                } catch (e: Exception) {
                    debugMsg("UInput", "Reader error: ${e.message}")
                }
                uinputReady = false
                debugMsg("UInput", "Process exited")
            }

            // Read stderr (native binary logging)
            thread(name = "uinput-stderr", isDaemon = true) {
                try {
                    BufferedReader(InputStreamReader(proc.errorStream)).forEachLine { line ->
                        debugMsg("UInput", line.removePrefix("LOG ").trim())
                    }
                } catch (_: Exception) {}
            }

            // Wait up to 3s for READY
            repeat(30) {
                if (uinputReady) return@repeat
                Thread.sleep(100)
            }

            debugMsg("UInput", if (uinputReady) "Injector ready" else "WARN: Not ready after 3s")
        } catch (e: Exception) {
            debugMsg("UInput", "ERROR: ${e.message}")
            uinputReady = false
        }
    }

    private fun extractUinputBinary() {
        Runtime.getRuntime().exec(arrayOf("rm", "-f", UINPUT_BINARY_PATH)).waitFor()

        val pmProc = Runtime.getRuntime().exec(arrayOf("pm", "path", "com.redtrigger"))
        val apkPath = pmProc.inputStream.bufferedReader().readText().trim().removePrefix("package:")
        pmProc.waitFor()
        debugMsg("UInput", "APK: $apkPath")

        val cmd = "unzip -o -j '$apkPath' 'assets/uinput_injector' -d /data/local/tmp/ && " +
            "mv /data/local/tmp/uinput_injector $UINPUT_BINARY_PATH && chmod 755 $UINPUT_BINARY_PATH"
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val err = proc.errorStream.bufferedReader().readText()
        proc.waitFor()

        if (proc.exitValue() != 0) {
            throw RuntimeException("Extract failed: $err")
        }
    }

    private fun stopUinputInjector() {
        // Close stdin first — the native binary cleans up UI_DEV_DESTROY on EOF
        try { uinputWriter?.close() } catch (_: Exception) {}
        uinputWriter = null

        // Give the binary time to handle EOF and call UI_DEV_DESTROY
        val proc = uinputProcess
        if (proc != null) {
            try {
                val exited = proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!exited) {
                    debugMsg("UInput", "Binary didn't exit after stdin close, force killing")
                    proc.destroyForcibly()
                    proc.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } catch (_: Exception) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
            }
        }
        uinputProcess = null
        uinputReady = false
    }

    override fun startReading(callback: ITriggerCallback?) {
        if (reading) return
        reading = true
        activeCallback = callback

        if (injectionEnabled) {
            startUinputInjector()
        }

        val detected = detectDevices()
        val devicePaths = detected.split(",").filter { it.isNotBlank() }

        if (devicePaths.isEmpty()) {
            debugMsg("Service", "No SAR devices found, reading all input devices")
            startReader(null, callback)
        } else {
            debugMsg("Service", "Starting: ${devicePaths.size} devices, inject=$injectionEnabled, uinput=$uinputReady")
            for (device in devicePaths) {
                startReader(device, callback)
            }
        }
    }

    /**
     * Start a getevent reader. If device is null, reads all devices.
     */
    private fun startReader(device: String?, callback: ITriggerCallback?) {
        val args = if (device != null) arrayOf("getevent", "-ql", device) else arrayOf("getevent", "-ql")
        val proc = Runtime.getRuntime().exec(args)
        readerProcesses.add(proc)

        val label = device ?: "all-devices"
        val t = thread(name = "InputService-$label") {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                    if (!reading) return@forEachLine
                    if (line.contains("EV_KEY")) {
                        parseGeteventLine(line, callback)
                    }
                }
            } catch (e: Exception) {
                if (reading) Log.e(TAG, "Error reading $label", e)
            }
        }
        readerThreads.add(t)
    }

    private fun parseGeteventLine(line: String, callback: ITriggerCallback?) {
        // Strip device path prefix (multi-device mode: "/dev/input/event4: EV_KEY ...")
        val trimmed = if (line.contains("/dev/input/")) {
            line.substringAfter(":").trim()
        } else {
            line.trim()
        }
        if (!trimmed.contains("EV_KEY")) return

        val isDown = trimmed.contains("DOWN") || trimmed.contains("value 1") || trimmed.endsWith(" 00000001")
        val isUp = trimmed.contains("UP") || trimmed.contains("value 0") || trimmed.endsWith(" 00000000")
        if (!isDown && !isUp) return

        // Try named key first
        var keyName = Regex("(KEY_\\w+)").find(trimmed)?.groupValues?.get(1) ?: ""

        // Fallback: hex scan code (0x41=65=KEY_F7, 0x42=66=KEY_F8)
        if (keyName.isEmpty()) {
            val hexMatch = Regex("EV_KEY\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]+)").find(trimmed)
            if (hexMatch != null) {
                keyName = when (hexMatch.groupValues[1].toIntOrNull(16)) {
                    65 -> "KEY_F7"
                    66 -> "KEY_F8"
                    else -> "KEY_0x${hexMatch.groupValues[1]}"
                }
            }
        }

        if (keyName.isEmpty()) keyName = "UNKNOWN"

        try { callback?.onRawKeyEvent(keyName, isDown) } catch (_: Exception) {}

        val trigger = when (keyName) {
            "KEY_F7" -> 0 // LEFT
            "KEY_F8" -> 1 // RIGHT
            else -> return
        }

        try { callback?.onTriggerEvent(trigger, isDown) } catch (_: Exception) {}

        if (injectionEnabled) {
            injectKey(trigger, isDown)
        }
    }

    private fun debugMsg(tag: String, msg: String) {
        Log.i(TAG, "[$tag] $msg")
        try { activeCallback?.onDebugMessage(tag, msg) } catch (_: Exception) {}
    }

    private fun injectKey(trigger: Int, isDown: Boolean) {
        val evdevCode = if (trigger == 0) BTN_TL else BTN_TR
        val value = if (isDown) 1 else 0
        val label = if (trigger == 0) "L1" else "R1"

        if (!uinputReady || uinputWriter == null) {
            debugMsg("Inject", "FAIL $label: uinput not ready")
            return
        }

        try {
            uinputWriter!!.write("$evdevCode $value\n")
            uinputWriter!!.flush()
            debugMsg("Inject", "uinput $label ${if (isDown) "DOWN" else "UP"} (evdev=$evdevCode)")
        } catch (e: Exception) {
            debugMsg("Inject", "uinput write failed: ${e.message}")
            uinputReady = false
        }
    }

    override fun setInjectionEnabled(enabled: Boolean) {
        injectionEnabled = enabled
        if (enabled && !uinputReady && reading) {
            startUinputInjector()
        } else if (!enabled) {
            stopUinputInjector()
        }
    }

    override fun stopReading() {
        reading = false
        stopUinputInjector()
        readerProcesses.forEach { try { it.destroy() } catch (_: Exception) {} }
        readerThreads.forEach { try { it.interrupt() } catch (_: Exception) {} }
        readerProcesses.clear()
        readerThreads.clear()
    }

    override fun grantPermission(permission: String) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("pm", "grant", "com.redtrigger", permission))
            val exitCode = proc.waitFor()
            debugMsg("Permission", "pm grant $permission exit=$exitCode")
        } catch (e: Exception) {
            debugMsg("Permission", "pm grant failed: ${e.message}")
        }
    }

    override fun destroy() {
        stopReading()
    }
}
