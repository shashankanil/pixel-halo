package com.shashankanil.halo.core

import org.json.JSONArray
import org.json.JSONObject

/** Every animation the renderer can draw on the ring. */
enum class Effect(val id: String, val label: String, val usesSecondColor: Boolean = false) {
    SOLID("solid", "Solid"),
    BREATHE("breathe", "Breathe"),
    PULSE("pulse", "Heartbeat"),
    STROBE("strobe", "Strobe"),
    RAINBOW("rainbow", "Rainbow"),
    CHASE("chase", "Comet"),
    SPIN("spin", "Spin", usesSecondColor = true),
    WAVE("wave", "Wave", usesSecondColor = true),
    TWINKLE("twinkle", "Twinkle"),
    CANDLE("candle", "Candle"),
    POLICE("police", "Police", usesSecondColor = true),
    FILL("fill", "Level", usesSecondColor = true),
    COUNTDOWN("countdown", "Countdown"),
    MORSE("morse", "Morse"),
    CUSTOM("custom", "Paint LEDs");

    companion object {
        fun of(id: String?): Effect = entries.firstOrNull { it.id == id } ?: SOLID
    }
}

/**
 * A complete description of what the ring shows. Pure data: the renderer turns it into colours with
 * [Effects.render], so the in-app preview and the hardware run the same maths.
 */
data class Look(
    val effect: Effect = Effect.SOLID,
    val color: Int = 0xFFFFFFFF.toInt(),
    val color2: Int = 0xFF000000.toInt(),
    /** 0..1, applied in software because HiLight has no hardware brightness control. */
    val brightness: Float = 1f,
    /** Animation speed multiplier, 0.25..4. */
    val speed: Float = 1f,
    /** Per-LED colours for [Effect.CUSTOM]. */
    val leds: List<Int> = emptyList(),
    /** 0..1 level for [Effect.FILL]. */
    val value: Float = 1f,
    /** Message for [Effect.MORSE]. */
    val text: String = "",
    /** Wall-clock reference (ms) for effects with a start, like [Effect.COUNTDOWN] and [Effect.MORSE]. */
    val startAt: Long = 0,
    /** Length of a [Effect.COUNTDOWN] in ms. */
    val durationMs: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("effect", effect.id)
        put("color", color)
        put("color2", color2)
        put("brightness", brightness.toDouble())
        put("speed", speed.toDouble())
        if (leds.isNotEmpty()) put("leds", JSONArray().apply { leds.forEach { put(it) } })
        if (effect == Effect.FILL) put("value", value.toDouble())
        if (text.isNotEmpty()) put("text", text)
        if (startAt != 0L) put("startAt", startAt)
        if (durationMs != 0L) put("durationMs", durationMs)
    }

    companion object {
        fun fromJson(o: JSONObject?): Look {
            if (o == null) return Look()
            val ledsJson = o.optJSONArray("leds")
            return Look(
                effect = Effect.of(o.optString("effect")),
                color = o.optInt("color", 0xFFFFFFFF.toInt()),
                color2 = o.optInt("color2", 0xFF000000.toInt()),
                brightness = o.optDouble("brightness", 1.0).toFloat().coerceIn(0f, 1f),
                speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.1f, 8f),
                leds = if (ledsJson == null) emptyList() else List(ledsJson.length()) { ledsJson.optInt(it) },
                value = o.optDouble("value", 1.0).toFloat().coerceIn(0f, 1f),
                text = o.optString("text", ""),
                startAt = o.optLong("startAt", 0),
                durationMs = o.optLong("durationMs", 0),
            )
        }
    }
}

/**
 * One candidate for the ring. The renderer shows the first layer in the list that has not expired.
 *
 * @param until wall-clock expiry in ms, or 0 for no expiry.
 */
data class Layer(val id: String, val look: Look, val until: Long = 0) {
    fun liveAt(now: Long) = until == 0L || until > now

    fun toJson(): JSONObject = JSONObject().put("id", id).put("look", look.toJson()).put("until", until)

    companion object {
        fun fromJson(o: JSONObject) = Layer(o.optString("id"), Look.fromJson(o.optJSONObject("look")), o.optLong("until"))
    }
}

/** The whole document the app sends to the renderer. */
data class HaloState(
    val rev: Long = 0,
    val enabled: Boolean = true,
    val priority: Int = 0,
    /** Ease brightness down during long, unbroken light to protect the LEDs. */
    val protect: Boolean = true,
    val layers: List<Layer> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("rev", rev)
        put("enabled", enabled)
        put("priority", priority)
        put("protect", protect)
        put("layers", JSONArray().apply { layers.forEach { put(it.toJson()) } })
    }.toString()

    companion object {
        fun fromJson(s: String): HaloState {
            val o = JSONObject(s)
            val arr = o.optJSONArray("layers") ?: JSONArray()
            return HaloState(
                rev = o.optLong("rev"),
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("priority", 0),
                protect = o.optBoolean("protect", true),
                layers = List(arr.length()) { Layer.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}
