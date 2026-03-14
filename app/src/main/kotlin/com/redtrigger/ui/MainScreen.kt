package com.redtrigger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.redtrigger.BootReceiver
import com.redtrigger.DebugLog
import com.redtrigger.InputReader
import com.redtrigger.TriggerManager
import com.redtrigger.TriggerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val GITHUB_URL = "https://github.com/lzampier/RedTrigger"

/** Top-level nav: main screen vs debug log */
enum class Screen { Main, DebugLog, About }

@Composable
fun MainScreen() {
    var currentScreen by remember { mutableStateOf(Screen.Main) }

    when (currentScreen) {
        Screen.Main -> MainContent(
            onNavigate = { currentScreen = it }
        )
        Screen.DebugLog -> DebugLogScreen(
            onBack = { currentScreen = Screen.Main }
        )
        Screen.About -> AboutScreen(
            onBack = { currentScreen = Screen.Main }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(onNavigate: (Screen) -> Unit) {
    var triggersEnabled by remember { mutableStateOf(false) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuPermission by remember { mutableStateOf(false) }
    var keyMapperInstalled by remember { mutableStateOf(false) }
    var autoEnableOnBoot by remember { mutableStateOf(false) }
    var statusTick by remember { mutableStateOf(0L) }
    var menuExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    val refreshState: () -> Unit = {
        triggersEnabled = TriggerManager.isTriggersEnabled(context)
        keyMapperInstalled = TriggerManager.isKeyMapperInstalled(context)
        autoEnableOnBoot = BootReceiver.isAutoEnableEnabled(context)
        shizukuInstalled = isPackageInstalled(context, SHIZUKU_PACKAGE)
        shizukuRunning = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Exception) { false }
        shizukuPermission = if (shizukuRunning) {
            try {
                rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
        } else false
    }

    val shizukuOk = shizukuInstalled && shizukuRunning && shizukuPermission
    val prerequisitesMet = shizukuOk  // WRITE_SECURE_SETTINGS is auto-granted via Shizuku

    LaunchedEffect(shizukuOk, triggersEnabled) {
        // If the user grants Shizuku permission while triggers are already enabled in the system,
        // we should start the service to ensure the watchdog and reader are actually running.
        if (shizukuOk && triggersEnabled && !TriggerService.isRunning) {
            DebugLog.log("MainScreen", "Shizuku became ready while triggers were enabled. Starting service.")
            try {
                context.startForegroundService(Intent(context, TriggerService::class.java))
            } catch (e: Exception) {
                DebugLog.log("MainScreen", "Failed to start service: ${e.message}")
            }
        }
    }

    LaunchedEffect(Unit) { refreshState() }

    // Poll at 5s normally, 1s while prerequisites aren't met (user is actively fixing things)
    LaunchedEffect(Unit) {
        while (true) {
            val interval = if (shizukuInstalled && shizukuRunning && shizukuPermission) 5000L else 1000L
            delay(interval)
            statusTick++
            refreshState()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top bar with overflow menu ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, top = 16.dp)
            ) {
                // Title area
                Column(
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Text(
                        text = "🎮 RedTrigger",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val versionName = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                    } catch (_: Exception) { "?" }
                    Text(
                        text = "v$versionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                // Three-dot menu
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Debug Log") },
                            onClick = {
                                menuExpanded = false
                                onNavigate(Screen.DebugLog)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                menuExpanded = false
                                onNavigate(Screen.About)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // ── Prerequisites Card ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Prerequisites",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        // Shizuku (single row, smart fix)
                        PrerequisiteRow(
                            label = "Shizuku",
                            status = shizukuOk,
                            fixLabel = when {
                                !shizukuInstalled -> "Install"
                                !shizukuRunning -> "Open"
                                !shizukuPermission -> "Grant"
                                else -> ""
                            },
                            subtitle = when {
                                !shizukuInstalled -> "Not installed"
                                !shizukuRunning -> "Not running"
                                !shizukuPermission -> "Permission needed"
                                else -> null
                            },
                            onFix = {
                                when {
                                    !shizukuInstalled -> {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            data = Uri.parse("market://details?id=$SHIZUKU_PACKAGE")
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                                                data = Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
                                            }
                                            context.startActivity(webIntent)
                                        }
                                    }
                                    !shizukuRunning -> {
                                        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                                        if (intent != null) {
                                            context.startActivity(intent)
                                        } else {
                                            Toast.makeText(context, "Can't open Shizuku", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    !shizukuPermission -> {
                                        try {
                                            rikka.shizuku.Shizuku.requestPermission(0)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                // ── Main Toggle ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            !prerequisitesMet -> MaterialTheme.colorScheme.surfaceVariant
                            triggersEnabled -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Enable Triggers",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (prerequisitesMet)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )

                        Switch(
                            checked = triggersEnabled,
                            enabled = prerequisitesMet,
                            onCheckedChange = { enabled ->
                                val success = if (enabled) {
                                    TriggerManager.enableTriggers(context)
                                } else {
                                    TriggerManager.disableTriggers(context)
                                }
                                if (success) {
                                    triggersEnabled = enabled
                                    Toast.makeText(
                                        context,
                                        if (enabled) "Triggers enabled!" else "Triggers disabled",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(context, "Failed to toggle triggers", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                // ── Runtime Status (only when active) ──
                AnimatedVisibility(
                    visible = triggersEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Runtime",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            val watchdogRunning = remember(statusTick) { TriggerService.isRunning }
                            val readerState = remember(statusTick) { InputReader.state }

                            StatusRow("Watchdog Running", watchdogRunning)
                            StatusRowTriState(
                                label = "Reader Active",
                                state = readerState
                            )
                        }
                    }
                }

                // ── Settings ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        val prefs = context.getSharedPreferences("RedTriggerPrefs", Context.MODE_PRIVATE)
                        var injectEnabled by remember { mutableStateOf(prefs.getBoolean("capture_inject", true)) }
                        var showRemapInfo by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            InputReader.injectionEnabled = injectEnabled
                        }

                        // Remap to Gamepad toggle with info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Remap to Gamepad",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = { showRemapInfo = !showRemapInfo },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "Info",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Switch(
                                checked = injectEnabled,
                                onCheckedChange = { enabled ->
                                    injectEnabled = enabled
                                    prefs.edit().putBoolean("capture_inject", enabled).apply()
                                    InputReader.setInjectionEnabledLive(enabled)
                                    DebugLog.log("Config", "Key injection ${if (enabled) "ON" else "OFF"}")
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = showRemapInfo,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "How remapping works",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "ON: Triggers appear as standard L1/R1 gamepad buttons to all apps. " +
                                            "Games and emulators detect them automatically with no extra configuration.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "OFF: Triggers remain as raw F7/F8 keyboard keys. Most apps won't see them. " +
                                            "Apps like KeyMapper can still detect them in expert mode, but will require " +
                                            "Shizuku or root permissions on their end too.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Recommended: Keep ON unless you have a specific reason to use raw keys.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        ToggleRow("Auto-enable on boot", autoEnableOnBoot) { enabled ->
                            BootReceiver.setAutoEnable(context, enabled)
                            autoEnableOnBoot = enabled
                        }
                    }
                }

                // ── Tools ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Tools",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "KeyMapper lets you assign custom actions to the shoulder triggers " +
                                "(e.g. volume control, screenshots, app shortcuts). " +
                                "It works with RedTrigger both with remapping on (detects L1/R1 gamepad buttons) " +
                                "and off (detects raw F7/F8 keys in expert mode).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!keyMapperInstalled) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse("market://details?id=io.github.sds100.keymapper")
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install KeyMapper")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val intent = context.packageManager.getLaunchIntentForPackage("io.github.sds100.keymapper")
                                    if (intent != null) {
                                        context.startActivity(intent)
                                    } else {
                                        Toast.makeText(context, "KeyMapper not found", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open KeyMapper")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ── Debug Log Screen ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var logVersion by remember { mutableStateOf(0L) }
    val entries = remember(logVersion) { DebugLog.getEntries() }
    val listState = rememberLazyListState()

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val v = DebugLog.version
            if (v != logVersion) {
                logVersion = v
            }
        }
    }

    LaunchedEffect(logVersion) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // Top bar
            TopAppBar(
                title = { Text("Debug Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("RedTrigger Log", entries.joinToString("\n"))
                        )
                        Toast.makeText(context, "Log copied (${entries.size} entries)", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy")
                    }
                    TextButton(onClick = {
                        DebugLog.clear()
                        logVersion = DebugLog.version
                    }) {
                        Text("Clear")
                    }
                }
            )

            // Toggle & Diff button
            val canToggle = TriggerManager.hasWriteSecureSettings(context) || rikka.shizuku.Shizuku.pingBinder()
            var isDiffing by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()

            Button(
                onClick = {
                    if (isDiffing) return@Button
                    isDiffing = true
                    coroutineScope.launch {
                        val wasEnabled = TriggerManager.isTriggersEnabled(context)
                        val before = TriggerManager.dumpAllSettings(context)
                        if (wasEnabled) {
                            TriggerManager.disableTriggers(context)
                        } else {
                            TriggerManager.enableTriggers(context)
                        }
                        delay(2000)
                        val after = TriggerManager.dumpAllSettings(context)
                        val diff = TriggerManager.diffSettings(before, after)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Settings Diff", diff))
                        Toast.makeText(context, "Diff copied (${diff.count { it == '\n' }} lines)", Toast.LENGTH_LONG).show()
                        isDiffing = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                enabled = canToggle && !isDiffing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text(
                    if (isDiffing) "Diffing... (2s)" else "Toggle & Diff ALL Settings",
                    modifier = Modifier.padding(4.dp)
                )
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No log entries yet.\nEnable triggers to see activity.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        items(entries) { entry ->
                            val color = when {
                                "ERROR" in entry -> MaterialTheme.colorScheme.error
                                "[Watchdog]" in entry -> MaterialTheme.colorScheme.tertiary
                                "[Trigger]" in entry -> MaterialTheme.colorScheme.primary
                                "[Shizuku]" in entry -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                ),
                                color = color,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── About Screen ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    BackHandler(onBack = onBack)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "🎮",
                    style = MaterialTheme.typography.displayLarge
                )

                Text(
                    text = "RedTrigger",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "v$versionName",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "System-wide shoulder trigger enabler for Nubia/RedMagic devices. Maps capacitive triggers to virtual gamepad buttons via Shizuku + uinput.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(GITHUB_URL)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("View on GitHub")
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "Made by Lucas Zampieri",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ── Shared Composables ──

@Composable
fun PrerequisiteRow(
    label: String,
    status: Boolean,
    fixLabel: String,
    subtitle: String? = null,
    showFix: Boolean = !status,
    onFix: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (status) "✓" else "✗",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (status) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!status && subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showFix && fixLabel.isNotEmpty()) {
            OutlinedButton(
                onClick = onFix,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = fixLabel,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}

@Composable
fun StatusRow(label: String, status: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (status) "✓" else "✗",
            style = MaterialTheme.typography.bodyLarge,
            color = if (status) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun StatusRowTriState(label: String, state: InputReader.State) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        when (state) {
            InputReader.State.RUNNING -> Text(
                text = "✓",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            InputReader.State.STARTING -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.tertiary
            )
            InputReader.State.STOPPED -> Text(
                text = "✗",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }
}
