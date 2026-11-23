package com.shashankanil.halo.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.provider.ContactsContract
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shashankanil.halo.AppRule
import com.shashankanil.halo.FavContact
import com.shashankanil.halo.HaloListenerService
import com.shashankanil.halo.NotificationMatch
import com.shashankanil.halo.Settings
import com.shashankanil.halo.Trigger
import com.shashankanil.halo.core.Effect
import com.shashankanil.halo.core.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RULE_COLORS = listOf(0xFFFF4081, 0xFF00E5FF, 0xFF76FF03, 0xFFFFC400, 0xFF651FFF, 0xFFFF6D00, 0xFF1DE9B6).map { it.toInt() }

@Composable
fun NotificationAccessBanner() {
    val context = LocalContext.current
    if (HaloListenerService.connected) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Notification access is off", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Needed for calls, messages, and to keep Halo running in the background.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            TextButton(onClick = {
                val cn = ComponentName(context, HaloListenerService::class.java)
                context.startActivity(
                    Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(SystemSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString()),
                )
            }) { Text("Allow") }
        }
    }
}

@Composable
fun AlertsScreen() {
    val context = LocalContext.current
    val cfg by Settings.config.collectAsStateWithLifecycle()
    var editingContact by remember { mutableStateOf<FavContact?>(null) }
    var editingRule by remember { mutableStateOf<AppRule?>(null) }
    var pickingApp by remember { mutableStateOf(false) }
    var editingOther by remember { mutableStateOf<String?>(null) }

    NotificationAccessBanner()

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use
            val name = c.getString(0) ?: return@use
            val number = c.getString(1) ?: ""
            val key = c.getString(2) ?: ""
            Settings.update { s ->
                val existing = s.contacts.firstOrNull { (key.isNotEmpty() && it.lookupKey == key) || it.name == name }
                if (existing != null) {
                    s.copy(contacts = s.contacts.map {
                        if (it.id == existing.id && number.isNotEmpty() && it.numbers.none { n -> NotificationMatch.samePhone(n, number) })
                            it.copy(numbers = it.numbers + number) else it
                    })
                } else {
                    val color = RULE_COLORS[s.contacts.size % RULE_COLORS.size]
                    s.copy(contacts = s.contacts + FavContact(name = name, numbers = listOfNotNull(number.ifEmpty { null }), lookupKey = key,
                        look = Look(Effect.BREATHE, color = color, speed = 1.6f)))
                }
            }
        }
    }

    Section("Favourite contacts") {
        Text("Calls and messages from these people light the ring in their own colour. Works with Phone, Messages, WhatsApp, Telegram, Signal and most chat apps.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cfg.contacts.forEach { c ->
            ListItem(
                headlineContent = { Text(c.name) },
                supportingContent = {
                    Text(listOfNotNull("Calls".takeIf { c.calls }, "Messages".takeIf { c.messages }).joinToString(" · ").ifEmpty { "Off" } +
                        " · ${c.look.effect.label}")
                },
                leadingContent = { ColorDot(c.look.color, 36) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { editingContact = c },
            )
        }
        FilledTonalButton(onClick = {
            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }) { Icon(Icons.Filled.Add, null); Text(" Add contact") }
    }

    Section("Apps") {
        Text("Give any app its own colour and pattern for new notifications.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cfg.appRules.forEach { r ->
            ListItem(
                headlineContent = { Text(r.label) },
                supportingContent = { Text(if (r.enabled) r.look.effect.label else "Off") },
                leadingContent = { AppIcon(r.pkg, r.look.color) },
                trailingContent = {
                    Switch(r.enabled, { on -> Settings.update { s -> s.copy(appRules = s.appRules.map { if (it.pkg == r.pkg) it.copy(enabled = on) else it }) } })
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { editingRule = r },
            )
        }
        FilledTonalButton(onClick = { pickingApp = true }) { Icon(Icons.Filled.Add, null); Text(" Add app") }
    }

    Section("Everyone else") {
        TriggerRow("Other callers", "Calls from people who aren't favourites", cfg.otherCalls,
            onToggle = { on -> Settings.update { it.copy(otherCalls = it.otherCalls.copy(enabled = on)) } },
            onEdit = { editingOther = "calls" })
        TriggerRow("Other notifications", "Anything without its own rule", cfg.otherNotifications,
            onToggle = { on -> Settings.update { it.copy(otherNotifications = it.otherNotifications.copy(enabled = on)) } },
            onEdit = { editingOther = "notifications" })
    }

    Section("Behaviour") {
        SliderRow("Alert length", cfg.alertSeconds.toFloat(), 2f..60f, "${cfg.alertSeconds} s") { v ->
            Settings.update { it.copy(alertSeconds = v.toInt()) }
        }
        Text("Remind me while unread", style = MaterialTheme.typography.bodyMedium)
        val reminderOptions = listOf(0, 1, 2, 5, 10, 15)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            reminderOptions.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = cfg.reminderMinutes == m,
                    onClick = { Settings.update { it.copy(reminderMinutes = m) } },
                    shape = SegmentedButtonDefaults.itemShape(i, reminderOptions.size),
                ) { Text(if (m == 0) "Off" else "${m}m") }
            }
        }
        SwitchRow("Only when the screen is off", cfg.onlyScreenOff) { v -> Settings.update { it.copy(onlyScreenOff = v) } }
        SwitchRow("Only when face down", cfg.onlyFaceDown, "Like Google's HiLight: the ring faces up") { v -> Settings.update { it.copy(onlyFaceDown = v) } }
        SwitchRow("Stop when I turn the screen on", cfg.clearOnScreenOn) { v -> Settings.update { it.copy(clearOnScreenOn = v) } }
        SwitchRow("Respect Do Not Disturb", cfg.respectDnd) { v -> Settings.update { it.copy(respectDnd = v) } }
        SwitchRow("Favourites break through DND and quiet hours", cfg.favouritesBypassDnd) { v -> Settings.update { it.copy(favouritesBypassDnd = v) } }
        Text("Quiet hours", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HourPicker("From", cfg.quietStart, Modifier.weight(1f)) { h -> Settings.update { it.copy(quietStart = h) } }
            HourPicker("Until", cfg.quietEnd, Modifier.weight(1f)) { h -> Settings.update { it.copy(quietEnd = h) } }
        }
    }

    editingContact?.let { c ->
        var calls by remember(c.id) { mutableStateOf(c.calls) }
        var messages by remember(c.id) { mutableStateOf(c.messages) }
        LookDialog(
            title = c.name,
            initial = c.look,
            onDismiss = { editingContact = null },
            onSave = { look ->
                Settings.update { s -> s.copy(contacts = s.contacts.map { if (it.id == c.id) it.copy(look = look, calls = calls, messages = messages) else it }) }
                editingContact = null
            },
            extra = {
                SwitchRow("Calls", calls) { calls = it }
                SwitchRow("Messages", messages) { messages = it }
            },
            onDelete = {
                Settings.update { s -> s.copy(contacts = s.contacts.filterNot { it.id == c.id }) }
                editingContact = null
            },
        )
    }

    editingRule?.let { r ->
        LookDialog(
            title = r.label,
            initial = r.look,
            onDismiss = { editingRule = null },
            onSave = { look ->
                Settings.update { s -> s.copy(appRules = s.appRules.map { if (it.pkg == r.pkg) it.copy(look = look) else it }) }
                editingRule = null
            },
            onDelete = {
                Settings.update { s -> s.copy(appRules = s.appRules.filterNot { it.pkg == r.pkg }) }
                editingRule = null
            },
        )
    }

    editingOther?.let { which ->
        val t = if (which == "calls") cfg.otherCalls else cfg.otherNotifications
        LookDialog(
            title = if (which == "calls") "Other callers" else "Other notifications",
            initial = t.look,
            onDismiss = { editingOther = null },
            onSave = { look ->
                Settings.update {
                    if (which == "calls") it.copy(otherCalls = Trigger(true, look)) else it.copy(otherNotifications = Trigger(true, look))
                }
                editingOther = null
            },
        )
    }

    if (pickingApp) AppPickerDialog(
        exclude = cfg.appRules.map { it.pkg }.toSet(),
        onDismiss = { pickingApp = false },
        onPick = { pkg, label ->
            Settings.update { s ->
                s.copy(appRules = s.appRules + AppRule(pkg, label, Look(Effect.PULSE, color = RULE_COLORS[s.appRules.size % RULE_COLORS.size])))
            }
            pickingApp = false
        },
    )
}

@Composable
fun TriggerRow(title: String, subtitle: String, trigger: Trigger, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { ColorDot(if (trigger.look.effect == Effect.RAINBOW) 0xFFFF4081.toInt() else trigger.look.color, 32) },
        trailingContent = { Switch(trigger.enabled, onToggle) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onEdit),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HourPicker(label: String, hour: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(
            value = if (hour < 0) "Off" else "%02d:00".format(hour),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Off") }, onClick = { onPick(-1); open = false })
            (0..23).forEach { h -> DropdownMenuItem(text = { Text("%02d:00".format(h)) }, onClick = { onPick(h); open = false }) }
        }
    }
}

@Composable
private fun AppIcon(pkg: String, fallback: Int) {
    val context = LocalContext.current
    var icon by remember(pkg) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pkg) {
        icon = withContext(Dispatchers.IO) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(pkg)
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 96)
                d.draw(AndroidCanvas(bmp))
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    val i = icon
    if (i != null) Image(i, null, Modifier.size(36.dp)) else ColorDot(fallback, 36)
}

private data class AppEntry(val pkg: String, val label: String)

@Composable
private fun AppPickerDialog(exclude: Set<String>, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.pkg }
                .filter { it.pkg != context.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an app") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val list = apps
                if (list == null) Text("Loading…")
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list.filter { it.pkg !in exclude && (query.isBlank() || it.label.contains(query, true)) }, key = { it.pkg }) { a ->
                        ListItem(
                            headlineContent = { Text(a.label) },
                            supportingContent = { Text(a.pkg, style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { AppIcon(a.pkg, 0xFF555555.toInt()) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(a.pkg, a.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
