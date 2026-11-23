package com.shashankanil.halo

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** What the helper last reported about itself. */
data class RendererStatus(
    val running: Boolean,
    val leds: Int = 0,
    val lights: String = "",
    val session: Boolean = false,
    val layer: String? = null,
    val uid: Int = -1,
    val pid: Int = -1,
    val version: Int = 0,
    val error: String? = null,
)

/**
 * The app and the helper run as different users, so they talk through two files in the app's
 * external files dir (readable by the shell user). The app owns both files: it writes state.json
 * atomically and the helper overwrites status.json in place.
 */
object Transport {
    private lateinit var dir: File
    private lateinit var stateFile: File
    private lateinit var statusFile: File
    private val io by lazy { Handler(HandlerThread("halo-io").apply { start() }.looper) }
    private val _status = MutableStateFlow(RendererStatus(running = false))
    val status: StateFlow<RendererStatus> = _status
    @Volatile private var pending: String? = null

    fun init(context: Context) {
        dir = context.getExternalFilesDir(null) ?: File(context.filesDir, "ext").apply { mkdirs() }
        stateFile = File(dir, "state.json")
        statusFile = File(dir, "status.json")
        if (!stateFile.exists()) stateFile.writeText("{}")
        if (!statusFile.exists()) statusFile.writeText("{}")
        // Readable for the shell user regardless of how the storage is mounted.
        dir.setReadable(true, false)
        stateFile.setReadable(true, false)
        statusFile.setWritable(true, false)
        io.post(::pollStatus)
    }

    val directory: String get() = dir.absolutePath

    /** Writes the state document. Coalesces rapid updates (slider drags) into one write. */
    fun push(json: String) {
        val first = pending == null
        pending = json
        if (first) io.post {
            val text = pending ?: return@post
            pending = null
            try {
                val tmp = File(dir, "state.json.tmp")
                tmp.writeText(text)
                tmp.setReadable(true, false)
                if (!tmp.renameTo(stateFile)) stateFile.writeText(text)
            } catch (e: Exception) {
                android.util.Log.w("Halo", "state write failed", e)
            }
        }
    }

    /** Reads status.json now (any thread) instead of waiting for the next poll. */
    fun refreshStatus(): RendererStatus = readStatus().also { _status.value = it }

    private fun pollStatus() {
        _status.value = readStatus()
        io.postDelayed(::pollStatus, 1000)
    }

    private fun readStatus(): RendererStatus = try {
        val o = JSONObject(statusFile.readText().ifBlank { "{}" })
        val hb = o.optLong("hb", 0)
        val alive = hb > 0 && System.currentTimeMillis() - hb < 4000
        RendererStatus(
            running = alive,
            leds = o.optInt("leds"),
            lights = o.optString("lights"),
            session = o.optBoolean("session"),
            layer = o.optString("layer").takeIf { it.isNotEmpty() && it != "null" },
            uid = o.optInt("uid", -1),
            pid = o.optInt("pid", -1),
            version = o.optInt("version"),
            error = o.optString("error").takeIf { it.isNotEmpty() },
        )
    } catch (_: Exception) {
        // A half-written file: keep the last value rather than flapping to "stopped".
        _status.value
    }
}
