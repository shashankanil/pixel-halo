package com.shashankanil.halo.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shashankanil.halo.Coordinator
import com.shashankanil.halo.HaloListenerService
import com.shashankanil.halo.Monitors
import com.shashankanil.halo.Settings
import com.shashankanil.halo.Transport
import com.shashankanil.halo.adb.HelperLauncher
import com.shashankanil.halo.adb.LaunchState
import com.shashankanil.halo.adb.PairingService
import com.shashankanil.halo.core.Effect
import com.shashankanil.halo.core.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val cfg by Settings.config.collectAsStateWithLifecycle()
    val status by Transport.status.collectAsStateWithLifecycle()
    val launch by HelperLauncher.state.collectAsStateWithLifecycle()
    var wifiAdb by remember { mutableStateOf(Monitors.wirelessDebuggingOn(context)) }
    var devOptions by remember { mutableStateOf(false) }
    var listener by remember { mutableStateOf(HaloListenerService.connected) }
    LaunchedEffect(Unit) {
        while (true) {
            wifiAdb = Monitors.wirelessDebuggingOn(context)
            devOptions = SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
            listener = HaloListenerService.connected
            delay(1000)
        }
    }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[LOCAL_NETWORK] != false) afterGrant?.invoke()
        afterGrant = null
    }
    /** Runs [action] once local-network (and notification) access is granted. */
    fun withPermissions(action: () -> Unit) {
        val missing = listOf(LOCAL_NETWORK, Manifest.permission.POST_NOTIFICATIONS)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { afterGrant = action; permissions.launch(missing.toTypedArray()) }
    }

    Section("LED helper") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(status.running && status.leds > 0, warn = status.running && status.leds == 0)
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        status.running && status.leds > 0 -> "Running: ${status.leds} HiLight LEDs connected"
                        status.running -> "Running, but no HiLight LEDs found"
                        else -> "Not running"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        status.running && status.leds == 0 -> "HiLight is only on the Pixel 11 Pro, Pro XL and Pro Fold. ${status.error ?: ""}"
                        status.running -> "Shell uid ${status.uid} · session ${if (status.session) "open" else "idle"}" + (status.layer?.let { " · ${layerName(it)}" } ?: "")
                        else -> "Starts through Wireless debugging and keeps running until the phone restarts."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (val l = launch) {
            is LaunchState.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(l.step, style = MaterialTheme.typography.bodySmall)
            }
            is LaunchState.Failed -> Text(l.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { withPermissions { HelperLauncher.start(context) } }, enabled = launch !is LaunchState.Working) {
                Text(if (status.running) "Restart helper" else "Start helper")
            }
            if (status.running) OutlinedButton(onClick = { HelperLauncher.stop(context) }) { Text("Stop") }
            if (status.running) FilledTonalButton(onClick = { Coordinator.showAlert("test", Look(Effect.RAINBOW, speed = 2f), 5) }) { Text("Test LEDs") }
        }
    }

    Section("1 · Wireless debugging") {
        StepRow(devOptions, "Developer options enabled", "Settings › About phone › tap Build number 7 times")
        StepRow(wifiAdb, "Wireless debugging is on", "Only needed for a few seconds while the helper starts. Needs Wi-Fi.")
        FilledTonalButton(onClick = { openDeveloperOptions(context) }) { Text("Open Developer options") }
    }

    Section("2 · Pair once") {
        StepRow(cfg.paired, if (cfg.paired) "Paired with Wireless debugging" else "Not paired yet",
            "Tap Pair, then in Wireless debugging choose \"Pair device with pairing code\" and type the code into Halo's notification.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { withPermissions { startPairing(context) } }) { Text(if (cfg.paired) "Pair again" else "Pair") }
        }
        ManualPairing()
    }

    Section("3 · After each restart") {
        SwitchRow("Start automatically", cfg.autoStart,
            "When you switch Wireless debugging on, Halo starts the helper by itself") { v -> Settings.update { it.copy(autoStart = v) } }
        SwitchRow("Turn Wireless debugging off after starting", cfg.wifiAdbOffAfterStart,
            "The helper keeps running. Keep USB debugging on: switching it off stops the helper.") { v -> Settings.update { it.copy(wifiAdbOffAfterStart = v) } }
        StepRow(listener, "Notification access", "Keeps Halo alive in the background for calls, messages and indicators")
        if (!listener) FilledTonalButton(onClick = {
            context.startActivity(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) { Text("Open notification access") }
    }

    Section("LED protection") {
        SwitchRow("Protect the LEDs", cfg.protect,
            "Dims long, unbroken light to 55% after 20 s and switches the live light off automatically. HiLight wasn't designed for hours of continuous light.") { v ->
            Settings.update { it.copy(protect = v) }
        }
        if (cfg.protect) {
            Text("Live light switches off after", style = MaterialTheme.typography.bodyMedium)
            val options = listOf(1, 5, 15, 30, 60)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = cfg.autoOffMinutes == m,
                        onClick = { Settings.update { it.copy(autoOffMinutes = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text("${m}m") }
                }
            }
        }
        SliderRow("Session priority", cfg.priority.toFloat(), 0f..100f, cfg.priority.toString(), steps = 9) { v ->
            Settings.update { it.copy(priority = v.toInt()) }
        }
        Text("While Halo shows something, Google's own HiLight effects (calls, Gemini) are paused. Halo hands the ring back as soon as it goes dark.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Section("From a computer (optional)") {
        Text("Same helper, started over USB instead of Wireless debugging:", style = MaterialTheme.typography.bodySmall)
        val cmd = HelperLauncher.computerCommand(context)
        SelectionContainer { Text(cmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Halo", cmd))
        }) { Text("Copy command") }
    }

    Section("Device") {
        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", style = MaterialTheme.typography.bodySmall)
        if (status.lights.isNotEmpty()) Text(status.lights, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ManualPairing() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide manual pairing" else "Pair manually (split screen)") }
    if (!open) return
    Text("Put Settings and Halo side by side, open \"Pair device with pairing code\", and copy the port (after the colon) and code here.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(code, { code = it.filter(Char::isDigit) }, label = { Text("Code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
    }
    Button(enabled = !busy && code.length >= 6, onClick = {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { HelperLauncher.pair(context, code, port.toIntOrNull()) }
            busy = false
            result = if (r.isSuccess) "Paired!" else r.exceptionOrNull()?.message
            if (r.isSuccess) HelperLauncher.start(context)
        }
    }) { Text(if (busy) "Pairing…" else "Pair") }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun StatusIcon(ok: Boolean, warn: Boolean = false) {
    Icon(
        if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
        null,
        tint = when {
            ok -> Color(0xFF00E676)
            warn -> Color(0xFFFFC400)
            else -> Color(0xFFFF5252)
        },
        modifier = Modifier.size(28.dp),
    )
    androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
}

@Composable
private fun StepRow(done: Boolean, title: String, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
            tint = if (done) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

private fun startPairing(context: android.content.Context) {
    PairingService.begin(context)
    openDeveloperOptions(context)
}

private fun openDeveloperOptions(context: android.content.Context) {
    context.startActivity(
        Intent(SystemSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            // Scrolls to and highlights "Wireless debugging" on Pixels.
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
