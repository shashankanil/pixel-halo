package com.shashankanil.halo.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shashankanil.halo.ChargingMode
import com.shashankanil.halo.Coordinator
import com.shashankanil.halo.Monitors
import com.shashankanil.halo.Settings
import com.shashankanil.halo.Trigger
import com.shashankanil.halo.core.Effect
import com.shashankanil.halo.core.Look

@Composable
fun IndicatorsScreen() {
    val context = LocalContext.current
    val cfg by Settings.config.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }

    NotificationAccessBanner()

    Section("Recording light") {
        Text("Lights the ring while any app uses the camera or microphone, like a camcorder's tally light. Short blips (face unlock, hotword) are ignored.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TriggerRow("Camera in use", "Video, photos, video calls", cfg.camera,
            onToggle = { on -> Settings.update { it.copy(camera = it.camera.copy(enabled = on)) } }, onEdit = { editing = "camera" })
        TriggerRow("Microphone in use", "Voice notes, calls, recorders", cfg.mic,
            onToggle = { on -> Settings.update { it.copy(mic = it.mic.copy(enabled = on)) } }, onEdit = { editing = "mic" })
    }

    Section("Charging") {
        Text("Shows the battery level as a ring that fills up: red, amber, then green. Breathes green when full.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val modes = listOf(ChargingMode.OFF to "Off", ChargingMode.ON_PLUG to "On plug-in", ChargingMode.WHILE_CHARGING to "Always")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = cfg.chargingMode == mode,
                    onClick = { Settings.update { it.copy(chargingMode = mode) }; Monitors.updateCharging() },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(label) }
            }
        }
        if (cfg.chargingMode == ChargingMode.ON_PLUG) {
            SliderRow("Show for", cfg.chargingSeconds.toFloat(), 3f..60f, "${cfg.chargingSeconds} s") { v ->
                Settings.update { it.copy(chargingSeconds = v.toInt()) }
            }
        }
        FilledTonalButton(onClick = {
            Coordinator.showAlert("test", Look(Effect.FILL, color = 0xFF76FF03.toInt(), color2 = 0xFF76FF03.toInt(), value = 0.62f), 5)
        }) { Text("Preview") }
    }

    Section("Low battery") {
        TriggerRow("Low battery warning", "Pulses every 5 minutes below ${cfg.lowBatteryLevel}% until you plug in", cfg.lowBattery,
            onToggle = { on -> Settings.update { it.copy(lowBattery = it.lowBattery.copy(enabled = on)) } }, onEdit = { editing = "low" })
        SliderRow("Warn below", cfg.lowBatteryLevel.toFloat(), 5f..50f, "${cfg.lowBatteryLevel}%") { v ->
            Settings.update { it.copy(lowBatteryLevel = v.toInt()) }
        }
    }

    Section("Automation") {
        SwitchRow("Allow other apps to control the ring", cfg.automation,
            "Tasker, MacroDroid, Home Assistant, Termux or adb can set colours with a broadcast") { v -> Settings.update { it.copy(automation = v) } }
        if (cfg.automation) {
            val example = "am broadcast -n ${context.packageName}/.AutomationReceiver -a com.shashankanil.halo.SET " +
                "--es color \"#ff00aa\" --es effect breathe --ei seconds 30"
            SelectionContainer {
                Text(example, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text("Extras: color, color2, effect, brightness (0-100), speed, text, value (0-100), seconds, preset. Send …halo.OFF to clear.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Halo", example))
                }) { Text("Copy example") }
            }
        }
    }

    editing?.let { which ->
        val (title, trigger) = when (which) {
            "camera" -> "Camera in use" to cfg.camera
            "mic" -> "Microphone in use" to cfg.mic
            else -> "Low battery" to cfg.lowBattery
        }
        LookDialog(
            title = title,
            initial = trigger.look,
            onDismiss = { editing = null },
            onSave = { look ->
                Settings.update {
                    when (which) {
                        "camera" -> it.copy(camera = Trigger(true, look))
                        "mic" -> it.copy(mic = Trigger(true, look))
                        else -> it.copy(lowBattery = Trigger(true, look))
                    }
                }
                editing = null
            },
        )
    }
}
