package com.shashankanil.halo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shashankanil.halo.Coordinator
import com.shashankanil.halo.core.Effect
import com.shashankanil.halo.core.Effects
import com.shashankanil.halo.core.Look
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

const val LED_COUNT = 8

fun Int.asColor() = Color(this or (0xFF shl 24))

/**
 * Draws the HiLight ring running [look] with the same maths as the hardware. LED 0 is at the top
 * and the order runs clockwise. [selected] highlights an LED; [onLedTap] makes LEDs tappable.
 */
@Composable
fun RingPreview(look: Look, modifier: Modifier = Modifier, selected: Int = -1, onLedTap: ((Int) -> Unit)? = null) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameMillis { now = System.currentTimeMillis() }
    }
    val colors = Effects.render(look, now, LED_COUNT)
    val tap = if (onLedTap == null) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures { p ->
            val cx = size.width / 2f
            val cy = size.height / 2f
            val angle = atan2(p.x - cx, -(p.y - cy)) // 0 at top, clockwise
            val idx = (((angle / (2 * PI)) * LED_COUNT).roundToInt() + LED_COUNT) % LED_COUNT
            onLedTap(idx)
        }
    }
    Canvas(modifier.aspectRatio(1f).then(tap)) {
        val c = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * 0.33f
        val dot = size.minDimension * 0.07f
        // The flash window the LEDs sit behind.
        drawCircle(Color(0xFF1B1B20), radius = radius + dot * 2.2f, center = c)
        drawCircle(Color(0xFF2A2A31), radius = radius - dot * 1.6f, center = c)
        drawCircle(Color(0xFF3A3A44), radius = radius - dot * 1.6f, center = c, style = Stroke(2f))
        for (i in 0 until LED_COUNT) {
            val a = 2 * PI * i / LED_COUNT
            val p = Offset(c.x + (radius * sin(a)).toFloat(), c.y - (radius * cos(a)).toFloat())
            val col = colors[i].asColor()
            val lit = (colors[i] and 0xFFFFFF) != 0
            if (lit) drawCircle(
                Brush.radialGradient(listOf(col.copy(alpha = 0.55f), Color.Transparent), center = p, radius = dot * 3.2f),
                radius = dot * 3.2f, center = p,
            )
            drawCircle(if (lit) col else Color(0xFF34343C), radius = dot, center = p)
            if (i == selected) drawCircle(Color.White, radius = dot * 1.35f, center = p, style = Stroke(3f))
        }
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/** A colour dot that can sit at the start of a row. */
@Composable
fun ColorDot(color: Int, size: Int = 28) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color.asColor()).border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape))
}

/** Draggable bar with a gradient track; [value] is 0..1. */
@Composable
fun GradientBar(value: Float, brush: Brush, onChange: (Float) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    Box(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) {
                width = size.width
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> onChange((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            width = size.width.toInt()
            drawRoundRect(brush, topLeft = Offset(0f, size.height * 0.2f),
                size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.6f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.3f))
            val x = value.coerceIn(0f, 1f) * size.width
            drawCircle(Color.White, radius = size.height * 0.42f, center = Offset(x, size.height / 2))
            drawCircle(Color.Black.copy(alpha = 0.4f), radius = size.height * 0.42f, center = Offset(x, size.height / 2), style = Stroke(2f))
        }
    }
}

private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFFFF1744, 0xFFFF6D00, 0xFFFFC400, 0xFF76FF03, 0xFF00E676, 0xFF1DE9B6,
    0xFF00E5FF, 0xFF2979FF, 0xFF651FFF, 0xFFD500F9, 0xFFFF4081, 0xFFFF8F1F, 0xFFB0BEC5,
).map { it.toInt() }

/** Any colour: hue, saturation and brightness bars, swatches and a hex field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(color: Int, onChange: (Int) -> Unit) {
    val hsv = Effects.toHsv(color)
    var hue by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }
    // Keep local HSV in sync when the colour is changed from outside (swatch, preset, hex).
    LaunchedEffect(color) {
        val now = Effects.hsv(hue.toDouble(), sat.toDouble(), value.toDouble())
        if (now != (color or (0xFF shl 24))) {
            val h = Effects.toHsv(color)
            if (h[1] > 0.01f && h[2] > 0.01f) hue = h[0]
            sat = h[1]
            value = h[2]
        }
    }
    fun emit() = onChange(Effects.hsv(hue.toDouble(), sat.toDouble(), value.toDouble()))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SWATCHES.forEach { s ->
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(s.asColor())
                        .border(if (s == (color or (0xFF shl 24))) 3.dp else 1.dp, Color.White.copy(alpha = if (s == color) 1f else 0.25f), CircleShape)
                        .clickable { onChange(s) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Hue", style = MaterialTheme.typography.labelMedium)
        GradientBar(hue, Brush.horizontalGradient((0..6).map { Effects.hsv(it / 6.0, 1.0, 1.0).asColor() })) { hue = it; emit() }
        Text("Saturation", style = MaterialTheme.typography.labelMedium)
        GradientBar(sat, Brush.horizontalGradient(listOf(Effects.hsv(hue.toDouble(), 0.0, 1.0).asColor(), Effects.hsv(hue.toDouble(), 1.0, 1.0).asColor()))) { sat = it; emit() }
        Text("Colour brightness", style = MaterialTheme.typography.labelMedium)
        GradientBar(value, Brush.horizontalGradient(listOf(Color.Black, Effects.hsv(hue.toDouble(), sat.toDouble(), 1.0).asColor()))) { value = it; emit() }
        var hex by remember(color) { mutableStateOf("#%06X".format(color and 0xFFFFFF)) }
        OutlinedTextField(
            value = hex,
            onValueChange = { s ->
                hex = s
                val clean = s.removePrefix("#")
                if (clean.length == 6) clean.toLongOrNull(16)?.let { onChange((it or 0xFF000000).toInt()) }
            },
            label = { Text("Hex") },
            singleLine = true,
            modifier = Modifier.width(160.dp),
        )
    }
}

/** Full editor for a [Look]: effect, colours, brightness, speed and effect-specific options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookEditor(look: Look, onChange: (Look) -> Unit, allowCountdown: Boolean = false, paintSelected: Int = -1) {
    var editingSecond by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Effect", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Effect.entries.filter { allowCountdown || it != Effect.COUNTDOWN }.forEach { e ->
                FilterChip(selected = look.effect == e, onClick = {
                    onChange(
                        if (e == Effect.CUSTOM && look.leds.size != LED_COUNT) look.copy(effect = e, leds = List(LED_COUNT) { look.color })
                        else look.copy(effect = e),
                    )
                }, label = { Text(e.label) })
            }
        }
        if (look.effect != Effect.RAINBOW) {
            if (look.effect.usesSecondColor) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !editingSecond, onClick = { editingSecond = false }, label = { Text("Colour 1") }, leadingIcon = { ColorDot(look.color, 18) })
                    FilterChip(selected = editingSecond, onClick = { editingSecond = true }, label = { Text(if (look.effect == Effect.FILL) "Background" else "Colour 2") }, leadingIcon = { ColorDot(look.color2, 18) })
                }
            }
            val second = editingSecond && look.effect.usesSecondColor
            if (look.effect == Effect.CUSTOM) {
                Text(
                    if (paintSelected >= 0) "Painting LED ${paintSelected + 1}: pick a colour" else "Tap an LED on the ring, then pick its colour",
                    style = MaterialTheme.typography.bodySmall,
                )
                val target = if (paintSelected in look.leds.indices) look.leds[paintSelected] else look.color
                ColorPicker(target) { c ->
                    val leds = (if (look.leds.size == LED_COUNT) look.leds else List(LED_COUNT) { look.color }).toMutableList()
                    if (paintSelected in leds.indices) leds[paintSelected] = c else leds.indices.forEach { leds[it] = c }
                    onChange(look.copy(leds = leds, color = c))
                }
            } else {
                ColorPicker(if (second) look.color2 else look.color) { c ->
                    onChange(if (second) look.copy(color2 = c) else look.copy(color = c))
                }
            }
        }
        if (look.effect == Effect.MORSE) {
            OutlinedTextField(look.text, { onChange(look.copy(text = it)) }, label = { Text("Message (Morse)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (look.effect == Effect.FILL) {
            SliderRow("Level", look.value, 0f..1f, "${(look.value * 100).roundToInt()}%") { onChange(look.copy(value = it)) }
        }
        SliderRow("Brightness", look.brightness, 0.02f..1f, "${(look.brightness * 100).roundToInt()}%") { onChange(look.copy(brightness = it)) }
        if (Effects.isAnimated(look) && look.effect != Effect.COUNTDOWN) {
            SliderRow("Speed", look.speed, 0.25f..4f, "×${"%.2f".format(look.speed)}") { onChange(look.copy(speed = it)) }
        }
    }
}

/** Dialog to edit a rule's look, with a live preview and a button to test it on the hardware. */
@Composable
fun LookDialog(
    title: String,
    initial: Look,
    onDismiss: () -> Unit,
    onSave: (Look) -> Unit,
    extra: (@Composable () -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var look by remember { mutableStateOf(initial) }
    var selected by remember { mutableIntStateOf(-1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RingPreview(look, Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally), selected,
                    onLedTap = if (look.effect == Effect.CUSTOM) { i -> selected = i } else null)
                extra?.invoke()
                LookEditor(look, { look = it }, paintSelected = selected)
                Row {
                    TextButton(onClick = { Coordinator.showAlert("test", look, 5) }) { Text("Test on HiLight") }
                    Spacer(Modifier.weight(1f))
                    onDelete?.let { TextButton(onClick = it) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(look) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
