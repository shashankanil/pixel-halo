package com.shashankanil.halo.core

import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.os.Binder
import android.os.IBinder
import java.lang.reflect.Method

/**
 * Talks to the system `lights` service through the hidden ILightsManager binder interface.
 *
 * Every call needs android.permission.CONTROL_DEVICE_LIGHTS, which is signature|privileged. Normal
 * apps can never hold it, but the shell user (uid 2000, what `adb shell` runs as) already
 * does, so this class only works inside a process started through adb.
 *
 * HiLight shows up as eight lights of type LIGHT_TYPE_APPLICATION (10, new in Android 17), each
 * with RGB control and no hardware brightness control.
 */
class LightsBackend {
    private val token: IBinder = Binder()
    private var service: Any? = null
    private lateinit var mOpenSession: Method
    private lateinit var mCloseSession: Method
    private lateinit var mSetLightStates: Method

    var ids: IntArray = IntArray(0)
        private set
    var description: String = ""
        private set
    var sessionOpen = false
        private set
    var sessionPriority = Int.MIN_VALUE
        private set

    fun connect() {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "lights") as IBinder? ?: error("lights service missing")
        val iface = Class.forName("android.hardware.lights.ILightsManager")
        val svc = Class.forName("android.hardware.lights.ILightsManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        mOpenSession = iface.getMethod("openSession", IBinder::class.java, Int::class.javaPrimitiveType)
        mCloseSession = iface.getMethod("closeSession", IBinder::class.java)
        mSetLightStates = iface.getMethod(
            "setLightStates", IBinder::class.java, IntArray::class.java, Array<LightState>::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val all = iface.getMethod("getLights").invoke(svc) as List<Light>
        val ring = all.filter { it.type == LIGHT_TYPE_APPLICATION }.sortedBy { it.ordinal }
        ids = ring.map { it.id }.toIntArray()
        description = "${all.size} lights total; ring: " + ring.joinToString { l ->
            "id=${l.id} ord=${l.ordinal} rgb=${l.hasRgbControl()}"
        }
        service = svc
    }

    fun openSession(priority: Int) {
        val svc = service ?: return
        try {
            if (sessionOpen) closeSession()
            mOpenSession.invoke(svc, token, priority)
            sessionOpen = true
            sessionPriority = priority
        } catch (e: Exception) {
            HaloLog.w("openSession failed: ${e.rootMessage()}")
        }
    }

    fun closeSession() {
        val svc = service ?: return
        if (!sessionOpen) return
        try {
            mCloseSession.invoke(svc, token)
        } catch (e: Exception) {
            HaloLog.w("closeSession failed: ${e.rootMessage()}")
        }
        sessionOpen = false
    }

    /** Pushes one frame; [colors] is indexed by LED ordinal. */
    fun push(colors: IntArray): Boolean {
        val svc = service ?: return false
        if (!sessionOpen || ids.isEmpty()) return false
        val states = Array(ids.size) { i -> LightState.Builder().setColor(colors[i % colors.size]).build() }
        return try {
            mSetLightStates.invoke(svc, token, ids, states)
            true
        } catch (e: Exception) {
            HaloLog.w("setLightStates failed: ${e.rootMessage()}")
            closeSession()
            false
        }
    }

    companion object {
        /** Light.LIGHT_TYPE_APPLICATION, added in API 37. Literal so older stubs still compile. */
        const val LIGHT_TYPE_APPLICATION = 10
    }
}

internal fun Throwable.rootMessage(): String {
    var t: Throwable = this
    while (t.cause != null) t = t.cause!!
    return "${t.javaClass.simpleName}: ${t.message}"
}

/** Logging that works both in the app (logcat) and in the adb helper (stdout -> log file). */
object HaloLog {
    @Volatile var toStdout = false

    fun i(msg: String) = log("I", msg)
    fun w(msg: String) = log("W", msg)

    private fun log(level: String, msg: String) {
        if (toStdout) println("${System.currentTimeMillis()} $level $msg")
        else if (level == "W") android.util.Log.w("Halo", msg) else android.util.Log.i("Halo", msg)
    }
}
