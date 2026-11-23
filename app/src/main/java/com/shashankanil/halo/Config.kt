package com.shashankanil.halo

import android.content.Context
import com.shashankanil.halo.core.Effect
import com.shashankanil.halo.core.Look
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Preset(val name: String, val look: Look)

/** A favourite contact with its own colour for calls and messages. */
data class FavContact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val numbers: List<String> = emptyList(),
    val lookupKey: String = "",
    val look: Look,
    val calls: Boolean = true,
    val messages: Boolean = true,
)

data class AppRule(val pkg: String, val label: String, val look: Look, val enabled: Boolean = true)

/** A trigger that can be switched on and has a look. */
data class Trigger(val enabled: Boolean, val look: Look) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("look", look.toJson())

    companion object {
        fun fromJson(o: JSONObject) = Trigger(o.optBoolean("enabled"), Look.fromJson(o.optJSONObject("look")))
    }
}

enum class ChargingMode { OFF, ON_PLUG, WHILE_CHARGING }

data class Config(
    val live: Look = Look(Effect.BREATHE, color = 0xFF7C4DFF.toInt()),
    val presets: List<Preset> = DEFAULT_PRESETS,
    val contacts: List<FavContact> = emptyList(),
    val appRules: List<AppRule> = emptyList(),
    val otherNotifications: Trigger = Trigger(false, Look(Effect.PULSE, color = 0xFFFFFFFF.toInt())),
    val otherCalls: Trigger = Trigger(false, Look(Effect.CHASE, color = 0xFF00E5FF.toInt(), speed = 1.5f)),
    val alertSeconds: Int = 8,
    val onlyScreenOff: Boolean = false,
    val onlyFaceDown: Boolean = false,
    val clearOnScreenOn: Boolean = true,
    val reminderMinutes: Int = 0,
    val respectDnd: Boolean = true,
    val favouritesBypassDnd: Boolean = true,
    /** Quiet hours as hour-of-day; -1 disables. */
    val quietStart: Int = -1,
    val quietEnd: Int = -1,
    val mic: Trigger = Trigger(false, Look(Effect.SOLID, color = 0xFFFF1744.toInt(), brightness = 0.6f)),
    val camera: Trigger = Trigger(false, Look(Effect.BREATHE, color = 0xFFFF1744.toInt())),
    val chargingMode: ChargingMode = ChargingMode.ON_PLUG,
    val chargingSeconds: Int = 8,
    val lowBattery: Trigger = Trigger(false, Look(Effect.PULSE, color = 0xFFFF3D00.toInt())),
    val lowBatteryLevel: Int = 15,
    val protect: Boolean = true,
    val autoOffMinutes: Int = 5,
    val priority: Int = 0,
    val automation: Boolean = false,
    val autoStart: Boolean = true,
    val wifiAdbOffAfterStart: Boolean = false,
    val paired: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("live", live.toJson())
        put("presets", JSONArray().apply { presets.forEach { put(JSONObject().put("name", it.name).put("look", it.look.toJson())) } })
        put("contacts", JSONArray().apply {
            contacts.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("numbers", JSONArray(it.numbers))
                    .put("lookupKey", it.lookupKey).put("look", it.look.toJson())
                    .put("calls", it.calls).put("messages", it.messages))
            }
        })
        put("appRules", JSONArray().apply {
            appRules.forEach {
                put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("look", it.look.toJson()).put("enabled", it.enabled))
            }
        })
        put("otherNotifications", otherNotifications.toJson())
        put("otherCalls", otherCalls.toJson())
        put("alertSeconds", alertSeconds)
        put("onlyScreenOff", onlyScreenOff)
        put("onlyFaceDown", onlyFaceDown)
        put("clearOnScreenOn", clearOnScreenOn)
        put("reminderMinutes", reminderMinutes)
        put("respectDnd", respectDnd)
        put("favouritesBypassDnd", favouritesBypassDnd)
        put("quietStart", quietStart)
        put("quietEnd", quietEnd)
        put("mic", mic.toJson())
        put("camera", camera.toJson())
        put("chargingMode", chargingMode.name)
        put("chargingSeconds", chargingSeconds)
        put("lowBattery", lowBattery.toJson())
        put("lowBatteryLevel", lowBatteryLevel)
        put("protect", protect)
        put("autoOffMinutes", autoOffMinutes)
        put("priority", priority)
        put("automation", automation)
        put("autoStart", autoStart)
        put("wifiAdbOffAfterStart", wifiAdbOffAfterStart)
        put("paired", paired)
    }

    companion object {
        val DEFAULT_PRESETS = listOf(
            Preset("Aurora", Look(Effect.WAVE, color = 0xFF00E676.toInt(), color2 = 0xFF2979FF.toInt())),
            Preset("Rainbow", Look(Effect.RAINBOW)),
            Preset("Candle", Look(Effect.CANDLE, color = 0xFFFF8F1F.toInt())),
            Preset("Comet", Look(Effect.CHASE, color = 0xFF18FFFF.toInt())),
            Preset("Party", Look(Effect.SPIN, color = 0xFFFF4081.toInt(), color2 = 0xFF651FFF.toInt(), speed = 2f)),
            Preset("SOS", Look(Effect.MORSE, color = 0xFFFFFFFF.toInt(), text = "SOS")),
        )

        fun fromJson(o: JSONObject): Config {
            val d = Config()
            fun trigger(key: String, def: Trigger) = o.optJSONObject(key)?.let { Trigger.fromJson(it) } ?: def
            return Config(
                live = o.optJSONObject("live")?.let { Look.fromJson(it) } ?: d.live,
                presets = o.optJSONArray("presets")?.let { a ->
                    List(a.length()) { a.getJSONObject(it).let { p -> Preset(p.optString("name"), Look.fromJson(p.optJSONObject("look"))) } }
                } ?: d.presets,
                contacts = o.optJSONArray("contacts")?.let { a ->
                    List(a.length()) {
                        val c = a.getJSONObject(it)
                        FavContact(
                            id = c.optString("id", UUID.randomUUID().toString()),
                            name = c.optString("name"),
                            numbers = c.optJSONArray("numbers")?.let { n -> List(n.length()) { i -> n.getString(i) } } ?: emptyList(),
                            lookupKey = c.optString("lookupKey"),
                            look = Look.fromJson(c.optJSONObject("look")),
                            calls = c.optBoolean("calls", true),
                            messages = c.optBoolean("messages", true),
                        )
                    }
                } ?: emptyList(),
                appRules = o.optJSONArray("appRules")?.let { a ->
                    List(a.length()) {
                        val r = a.getJSONObject(it)
                        AppRule(r.optString("pkg"), r.optString("label"), Look.fromJson(r.optJSONObject("look")), r.optBoolean("enabled", true))
                    }
                } ?: emptyList(),
                otherNotifications = trigger("otherNotifications", d.otherNotifications),
                otherCalls = trigger("otherCalls", d.otherCalls),
                alertSeconds = o.optInt("alertSeconds", d.alertSeconds),
                onlyScreenOff = o.optBoolean("onlyScreenOff", d.onlyScreenOff),
                onlyFaceDown = o.optBoolean("onlyFaceDown", d.onlyFaceDown),
                clearOnScreenOn = o.optBoolean("clearOnScreenOn", d.clearOnScreenOn),
                reminderMinutes = o.optInt("reminderMinutes", d.reminderMinutes),
                respectDnd = o.optBoolean("respectDnd", d.respectDnd),
                favouritesBypassDnd = o.optBoolean("favouritesBypassDnd", d.favouritesBypassDnd),
                quietStart = o.optInt("quietStart", d.quietStart),
                quietEnd = o.optInt("quietEnd", d.quietEnd),
                mic = trigger("mic", d.mic),
                camera = trigger("camera", d.camera),
                chargingMode = runCatching { ChargingMode.valueOf(o.optString("chargingMode")) }.getOrDefault(d.chargingMode),
                chargingSeconds = o.optInt("chargingSeconds", d.chargingSeconds),
                lowBattery = trigger("lowBattery", d.lowBattery),
                lowBatteryLevel = o.optInt("lowBatteryLevel", d.lowBatteryLevel),
                protect = o.optBoolean("protect", d.protect),
                autoOffMinutes = o.optInt("autoOffMinutes", d.autoOffMinutes),
                priority = o.optInt("priority", d.priority),
                automation = o.optBoolean("automation", d.automation),
                autoStart = o.optBoolean("autoStart", d.autoStart),
                wifiAdbOffAfterStart = o.optBoolean("wifiAdbOffAfterStart", d.wifiAdbOffAfterStart),
                paired = o.optBoolean("paired", d.paired),
            )
        }
    }
}

/** Persists [Config] as one JSON document and exposes it as a flow. */
object Settings {
    private const val PREFS = "halo"
    private const val KEY = "config"
    private lateinit var appContext: Context
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config
    val value get() = _config.value

    fun init(context: Context) {
        appContext = context.applicationContext
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _config.value = raw?.let { runCatching { Config.fromJson(JSONObject(it)) }.getOrNull() } ?: Config()
    }

    fun update(change: (Config) -> Config) {
        val next = change(_config.value)
        if (next == _config.value) return
        _config.value = next
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.toJson().toString()).apply()
        Coordinator.onConfigChanged()
    }
}
