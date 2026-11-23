package com.shashankanil.halo.core

import org.json.JSONObject

/**
 * The renderer. Runs in the privileged process, picks the top live layer from the latest
 * [HaloState] and draws it on the ring at up to ~30 fps.
 *
 * The light session is held only while there is something to show. While a session is open the
 * system's own HiLight effects (calls, Gemini) are suppressed, so as soon as nothing is live the
 * session is closed and the ring goes back to Android.
 */
class Engine(private val transport: String) {
    private val lights = LightsBackend()
    private val lock = Object()
    @Volatile private var state: HaloState? = null
    @Volatile private var running = false
    @Volatile private var error: String? = null
    @Volatile private var activeLayer: String? = null
    private var thread: Thread? = null
    private var litSince = 0L
    private var lastPushed: IntArray? = null
    private var lastPushAt = 0L

    val ledCount get() = lights.ids.size

    fun start() {
        try {
            lights.connect()
            HaloLog.i("connected: $ledCount HiLight LEDs (${lights.description})")
        } catch (e: Throwable) {
            error = e.rootMessage()
            HaloLog.w("lights connect failed: $error")
        }
        running = true
        thread = Thread(::loop, "halo-render").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread?.join(1000)
        lights.closeSession()
    }

    fun setState(json: String) {
        try {
            state = HaloState.fromJson(json)
        } catch (e: Exception) {
            HaloLog.w("bad state: ${e.rootMessage()}")
            return
        }
        synchronized(lock) { lock.notifyAll() }
    }

    fun status(): String = JSONObject().apply {
        put("version", VERSION)
        put("transport", transport)
        put("pid", android.os.Process.myPid())
        put("uid", android.os.Process.myUid())
        put("leds", ledCount)
        put("lights", lights.description)
        put("session", lights.sessionOpen)
        put("layer", activeLayer ?: JSONObject.NULL)
        put("rev", state?.rev ?: -1)
        put("hb", System.currentTimeMillis())
        error?.let { put("error", it) }
    }.toString()

    private fun loop() {
        while (running) {
            val wait = try {
                frame()
            } catch (t: Throwable) {
                HaloLog.w("frame failed: ${t.rootMessage()}")
                500L
            }
            synchronized(lock) { if (running && wait > 0) lock.wait(wait) }
        }
    }

    /** Draws one frame and returns how long to sleep before the next one. */
    private fun frame(): Long {
        val st = state
        val now = System.currentTimeMillis()
        val layer = if (st != null && st.enabled) st.layers.firstOrNull { it.liveAt(now) } else null
        if (layer == null || ledCount == 0) {
            goDark()
            // Still report what would be shown, so a device without HiLight is easy to diagnose.
            activeLayer = layer?.id
            // setState() wakes us early; otherwise re-check (and catch layer expiry) once a second.
            return 1000
        }
        activeLayer = layer.id
        var colors = Effects.render(layer.look, now, ledCount)
        if (Effects.isDark(colors)) {
            litSince = 0
        } else {
            if (litSince == 0L) litSince = now
            if (st!!.protect) colors = taper(colors, now - litSince)
        }
        if (!lights.sessionOpen || lights.sessionPriority != st!!.priority) lights.openSession(st!!.priority)
        val changed = lastPushed?.contentEquals(colors) != true
        // Re-send unchanged frames once a second so a dropped update cannot stick.
        if (changed || now - lastPushAt > 1000) {
            if (lights.push(colors)) {
                lastPushed = colors
                lastPushAt = now
            }
        }
        val animated = Effects.isAnimated(layer.look) || (st.protect && litSince != 0L)
        val untilExpiry = if (layer.until == 0L) Long.MAX_VALUE else layer.until - now
        return minOf(if (animated) FRAME_MS else 1000L, untilExpiry.coerceAtLeast(1))
    }

    private fun goDark() {
        activeLayer = null
        litSince = 0
        if (lights.sessionOpen) {
            lights.push(IntArray(ledCount) { Effects.BLACK })
            lights.closeSession()
        }
        lastPushed = null
    }

    /** After 20 s of unbroken light, ease down to 55 % over the next 10 s. */
    private fun taper(colors: IntArray, litMs: Long): IntArray {
        if (litMs <= TAPER_START_MS) return colors
        val k = 1.0 - 0.45 * ((litMs - TAPER_START_MS) / TAPER_RAMP_MS.toDouble()).coerceAtMost(1.0)
        return IntArray(colors.size) { Effects.scale(colors[it], k) }
    }

    companion object {
        const val VERSION = 1
        const val FRAME_MS = 33L
        const val TAPER_START_MS = 20_000L
        const val TAPER_RAMP_MS = 10_000L
    }
}
