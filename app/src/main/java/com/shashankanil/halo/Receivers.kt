package com.shashankanil.halo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.shashankanil.halo.adb.HelperLauncher
import com.shashankanil.halo.core.Effect

/**
 * Lets Tasker, Home Assistant, adb or any other app drive the ring once "Automation" is enabled:
 *
 *   adb shell am broadcast -n com.shashankanil.halo/.AutomationReceiver \
 *       -a com.shashankanil.halo.SET --es color "#ff00aa" --es effect breathe --ei seconds 20
 *   adb shell am broadcast -n com.shashankanil.halo/.AutomationReceiver -a com.shashankanil.halo.OFF
 *
 * Extras: color, color2 ("#rrggbb" or a colour name), effect (solid, breathe, pulse, strobe, rainbow,
 * chase, spin, wave, twinkle, candle, police, fill, morse), brightness (0-100), speed (float),
 * text (morse), value (0-100, for fill), seconds (0 = until auto-off), preset (saved preset name).
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cfg = Settings.value
        if (!cfg.automation) return
        when (intent.action) {
            ACTION_OFF -> Coordinator.setAutomation(null, 0)
            ACTION_SET -> {
                val base = intent.getStringExtra("preset")?.let { name ->
                    cfg.presets.firstOrNull { it.name.equals(name, ignoreCase = true) }?.look
                } ?: cfg.live
                val look = base.copy(
                    effect = intent.getStringExtra("effect")?.let { Effect.of(it) } ?: base.effect,
                    color = parse(intent.getStringExtra("color")) ?: base.color,
                    color2 = parse(intent.getStringExtra("color2")) ?: base.color2,
                    brightness = intent.extras?.get("brightness")?.toString()?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: base.brightness,
                    speed = intent.extras?.get("speed")?.toString()?.toFloatOrNull()?.coerceIn(0.1f, 8f) ?: base.speed,
                    text = intent.getStringExtra("text") ?: base.text,
                    value = intent.extras?.get("value")?.toString()?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: base.value,
                )
                val requested = intent.extras?.get("seconds")?.toString()?.toIntOrNull() ?: 10
                val cap = if (cfg.protect && cfg.autoOffMinutes > 0) cfg.autoOffMinutes * 60 else Int.MAX_VALUE
                val seconds = if (requested <= 0) (if (cap == Int.MAX_VALUE) 0 else cap) else minOf(requested, cap)
                Coordinator.setAutomation(look, seconds)
            }
        }
    }

    private fun parse(s: String?): Int? = s?.let { runCatching { Color.parseColor(it) or (0xFF shl 24) }.getOrNull() }

    companion object {
        const val ACTION_SET = "com.shashankanil.halo.SET"
        const val ACTION_OFF = "com.shashankanil.halo.OFF"
    }
}

/** Starting the app process is enough: [Monitors] then auto-starts the helper if it can. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Quick Settings tile: toggles the live light, or starts the helper when it isn't running. */
class HaloTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        if (!Transport.status.value.running) {
            HelperLauncher.start(this)
        } else {
            Coordinator.setAmbient(!Coordinator.ambientOn.value)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = Transport.status.value.running
        tile.state = if (running && Coordinator.ambientOn.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (running) Settings.value.live.effect.label else "Tap to start"
        tile.updateTile()
    }
}
