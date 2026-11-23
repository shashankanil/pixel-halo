package com.shashankanil.halo.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shashankanil.halo.Coordinator
import com.shashankanil.halo.Preset
import com.shashankanil.halo.Settings
import com.shashankanil.halo.core.Effect
import com.shashankanil.halo.core.Look
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LightScreen() {
    val cfg by Settings.config.collectAsStateWithLifecycle()
    val on by Coordinator.ambientOn.collectAsStateWithLifecycle()
    val until by Coordinator.ambientUntil.collectAsStateWithLifecycle()
    val top by Coordinator.top.collectAsStateWithLifecycle()
    val timer by Coordinator.timer.collectAsStateWithLifecycle()
    val status by com.shashankanil.halo.Transport.status.collectAsStateWithLifecycle()
    var selected by remember { mutableIntStateOf(-1) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val shown = top?.takeIf { it.id != "live" }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        RingPreview(
            shown?.look ?: cfg.live,
            Modifier.fillMaxWidth(0.7f).padding(8.dp),
            selected = if (cfg.live.effect == Effect.CUSTOM) selected else -1,
            onLedTap = if (cfg.live.effect == Effect.CUSTOM && shown == null) { i -> selected = i } else null,
        )
        Text(
            when {
                shown != null -> "Showing: ${layerName(shown.id)}"
                on -> (if (status.running && status.leds > 0) "Live on HiLight" else "On (preview only: helper offline or no HiLight)") +
                    if (until > 0) " · off in ${formatLeft(until - now)}" else ""
                else -> "Preview: light is off"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Section("Light") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (on) "On" else "Off", style = MaterialTheme.typography.headlineSmall)
                Text("Your look on the ring until you switch it off" + if (cfg.protect && cfg.autoOffMinutes > 0) " or ${cfg.autoOffMinutes} min pass" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(on, { Coordinator.setAmbient(it) })
        }
    }

    Section("Presets") {
        var editing by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cfg.presets.forEach { p ->
                InputChip(
                    selected = p.look == cfg.live,
                    onClick = {
                        if (editing) Settings.update { c -> c.copy(presets = c.presets - p) }
                        else Settings.update { it.copy(live = p.look) }
                    },
                    label = { Text(p.name) },
                    leadingIcon = { ColorDot(if (p.look.effect == Effect.RAINBOW) 0xFFFF4081.toInt() else p.look.color, 16) },
                    trailingIcon = if (editing) ({ Icon(Icons.Filled.Close, "Delete") }) else null,
                )
            }
            AssistChip(onClick = { saving = true }, label = { Text("Save") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
            AssistChip(onClick = { editing = !editing }, label = { Text(if (editing) "Done" else "Edit") })
        }
        if (saving) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { saving = false },
                title = { Text("Save preset") },
                text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        Settings.update { it.copy(presets = it.presets + Preset(name.trim(), it.live)) }
                        saving = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
            )
        }
    }

    Section("Look") {
        LookEditor(cfg.live, { l -> Settings.update { it.copy(live = l) } }, paintSelected = selected)
    }

    Section("Timer") {
        val t = timer
        if (t != null) {
            val left = t.endsAt - now
            Text(if (left > 0) "Ring empties as time runs out: ${formatLeft(left)} left" else "Time's up!", style = MaterialTheme.typography.bodyLarge)
            FilledTonalButton(onClick = { Coordinator.cancelTimer() }) { Text("Cancel timer") }
        } else {
            Text("The ring drains like a clock and flashes when time is up.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.0, 3.0, 5.0, 10.0, 15.0, 25.0, 45.0).forEach { m ->
                    AssistChip(onClick = { Coordinator.startTimer(m, cfg.live.copy(effect = Effect.COUNTDOWN)) }, label = { Text("${m.toInt()} min") })
                }
            }
        }
    }

    Section("Quick actions") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Coordinator.showAlert("sos", Look(Effect.MORSE, color = 0xFFFFFFFF.toInt(), text = "SOS"), 60) }) { Text("SOS (60 s)") }
            FilledTonalButton(onClick = { Coordinator.showAlert("find", Look(Effect.STROBE, color = 0xFFFFFFFF.toInt()), 20) }) { Text("Find my phone") }
            FilledTonalButton(onClick = { Coordinator.showAlert("party", Look(Effect.RAINBOW, speed = 2f), 30) }) { Text("Party (30 s)") }
            FilledTonalButton(onClick = { Coordinator.clearAlerts() }) { Text("Stop alerts") }
        }
    }
}

fun layerName(id: String) = when {
    id == "call" -> "Incoming call"
    id.startsWith("alert:test") -> "Test"
    id.startsWith("alert:") -> "Notification / alert"
    id == "camera" -> "Recording light (camera)"
    id == "mic" -> "Recording light (microphone)"
    id == "timer" -> "Timer"
    id == "charging" -> "Charging"
    id == "automation" -> "Automation"
    else -> "Live light"
}

fun formatLeft(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "${s}s"
}
