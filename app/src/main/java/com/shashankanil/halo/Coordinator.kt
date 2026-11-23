package com.shashankanil.halo

import android.os.Handler
import android.os.Looper
import com.shashankanil.halo.core.Effect
import com.shashankanil.halo.core.HaloState
import com.shashankanil.halo.core.Layer
import com.shashankanil.halo.core.Look
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TimerState(val startAt: Long, val durationMs: Long, val look: Look) {
    val endsAt get() = startAt + durationMs
}

/**
 * Collects every source that wants the ring (live light, alerts, calls, privacy, charging, timer,
 * automation) and turns them into an ordered layer list for the renderer. Main thread only.
 */
object Coordinator {
    private val main = Handler(Looper.getMainLooper())
    private var rev = 0L

    private val _ambientOn = MutableStateFlow(false)
    val ambientOn: StateFlow<Boolean> = _ambientOn
    private val _ambientUntil = MutableStateFlow(0L)
    val ambientUntil: StateFlow<Long> = _ambientUntil
    private val _timer = MutableStateFlow<TimerState?>(null)
    val timer: StateFlow<TimerState?> = _timer
    /** Layer currently on top, for the in-app preview. */
    private val _top = MutableStateFlow<Layer?>(null)
    val top: StateFlow<Layer?> = _top

    private var call: Layer? = null
    private var callKey: String? = null
    private var alert: Layer? = null
    private var automation: Layer? = null
    private var micActive = false
    private var cameraActive = false
    private var chargingLayer: Layer? = null
    private val reminders = LinkedHashMap<String, Look>()
    private var reminderCount = 0

    // --- live light --------------------------------------------------------------------------

    fun setAmbient(on: Boolean): Unit = onMain {
        val cfg = Settings.value
        _ambientOn.value = on
        _ambientUntil.value = if (on && cfg.protect && cfg.autoOffMinutes > 0) now() + cfg.autoOffMinutes * 60_000L else 0L
        main.removeCallbacks(ambientExpiry)
        if (_ambientUntil.value > 0) main.postAtTime(ambientExpiry, uptimeAt(_ambientUntil.value))
        recompute()
    }

    private val ambientExpiry = Runnable { setAmbient(false) }

    fun onConfigChanged(): Unit = onMain { recompute() }

    // --- alerts ------------------------------------------------------------------------------

    fun showAlert(id: String, look: Look, seconds: Int = Settings.value.alertSeconds): Unit = onMain {
        alert = Layer("alert:$id", look.restart(), now() + seconds * 1000L)
        recompute()
        main.postAtTime({ recompute() }, uptimeAt(alert!!.until + 50))
    }

    fun clearAlerts(): Unit = onMain {
        alert = null
        reminders.clear()
        main.removeCallbacks(reminderTick)
        recompute()
    }

    fun setCall(key: String, look: Look): Unit = onMain {
        if (callKey != key || call == null) call = Layer("call", look.restart(), now() + 90_000)
        callKey = key
        recompute()
    }

    fun clearCall(key: String): Unit = onMain {
        if (callKey == key) {
            call = null
            callKey = null
            recompute()
        }
    }

    fun addReminder(key: String, look: Look): Unit = onMain {
        if (Settings.value.reminderMinutes <= 0) return@onMain
        reminders[key] = look
        reminderCount = 0
        main.removeCallbacks(reminderTick)
        main.postDelayed(reminderTick, Settings.value.reminderMinutes * 60_000L)
    }

    fun removeReminder(key: String): Unit = onMain {
        reminders.remove(key)
        if (reminders.isEmpty()) main.removeCallbacks(reminderTick)
    }

    private val reminderTick: Runnable = object : Runnable {
        override fun run() {
            val minutes = Settings.value.reminderMinutes
            val look = reminders.values.lastOrNull() ?: return
            if (minutes <= 0 || reminderCount >= 30) return
            reminderCount++
            showAlert("reminder", look, 3)
            main.postDelayed(this, minutes * 60_000L)
        }
    }

    // --- status indicators -------------------------------------------------------------------

    fun setPrivacy(mic: Boolean, camera: Boolean): Unit = onMain {
        if (mic == micActive && camera == cameraActive) return@onMain
        micActive = mic
        cameraActive = camera
        recompute()
    }

    fun setCharging(layer: Layer?): Unit = onMain {
        chargingLayer = layer
        recompute()
        layer?.until?.takeIf { it > 0 }?.let { main.postAtTime({ recompute() }, uptimeAt(it + 50)) }
    }

    fun startTimer(minutes: Double, look: Look): Unit = onMain {
        val t = TimerState(now(), (minutes * 60_000).toLong(), look)
        _timer.value = t
        main.removeCallbacks(timerEnd)
        main.postAtTime(timerEnd, uptimeAt(t.endsAt + DONE_FLASH_MS))
        recompute()
    }

    fun cancelTimer(): Unit = onMain {
        _timer.value = null
        main.removeCallbacks(timerEnd)
        recompute()
    }

    private val timerEnd = Runnable { cancelTimer() }

    fun setAutomation(look: Look?, seconds: Int): Unit = onMain {
        automation = look?.let { Layer("automation", it.restart(), if (seconds > 0) now() + seconds * 1000L else 0L) }
        recompute()
    }

    // --- output ------------------------------------------------------------------------------

    fun recompute() {
        val cfg = Settings.value
        val now = now()
        val layers = buildList {
            call?.let { add(it) }
            alert?.let { add(it) }
            automation?.let { add(it) }
            if (cameraActive && cfg.camera.enabled) add(Layer("camera", cfg.camera.look))
            if (micActive && cfg.mic.enabled) add(Layer("mic", cfg.mic.look))
            _timer.value?.let { t ->
                add(Layer("timer", t.look.copy(effect = Effect.COUNTDOWN, startAt = t.startAt, durationMs = t.durationMs), t.endsAt + DONE_FLASH_MS))
            }
            chargingLayer?.let { add(it) }
            if (_ambientOn.value) add(Layer("live", cfg.live, _ambientUntil.value))
        }.filter { it.liveAt(now) }
        _top.value = layers.firstOrNull()
        val state = HaloState(rev = ++rev, enabled = true, priority = cfg.priority, protect = cfg.protect, layers = layers)
        Transport.push(state.toJson())
    }

    private fun Look.restart() = if (effect == Effect.MORSE) copy(startAt = now()) else this

    private fun now() = System.currentTimeMillis()

    private fun uptimeAt(wallMs: Long) = android.os.SystemClock.uptimeMillis() + (wallMs - now()).coerceAtLeast(0)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private const val DONE_FLASH_MS = 10_000L
}
