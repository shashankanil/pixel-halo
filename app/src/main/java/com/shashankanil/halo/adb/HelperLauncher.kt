package com.shashankanil.halo.adb

import android.content.Context
import com.shashankanil.halo.Coordinator
import com.shashankanil.halo.Settings
import com.shashankanil.halo.Transport
import com.shashankanil.halo.core.HelperMain
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

sealed interface LaunchState {
    data object Idle : LaunchState
    data class Working(val step: String) : LaunchState
    data object Started : LaunchState
    data class Failed(val reason: String) : LaunchState
}

/**
 * Starts the LED helper through the phone's own Wireless debugging: connect to the local adbd
 * with Halo's paired key, run one shell command that launches HelperMain from the installed APK
 * as the shell user, then disconnect. The helper keeps running until reboot.
 */
object HelperLauncher {
    private val worker = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> = _state

    const val LOG = "/data/local/tmp/pixel-halo.log"
    private const val SH_LOG = "/data/local/tmp/pixel-halo-sh.log"
    private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The exact shell command, also shown in the app for starting from a computer. */
    fun command(context: Context): String {
        val apk = context.applicationInfo.sourceDir
        // setsid: own session, so adbd tearing down this shell can't take the helper with it.
        return "CLASSPATH=$apk setsid nohup app_process /system/bin ${HelperMain::class.java.name} " +
            "--dir ${Transport.directory} > $LOG 2>&1 < /dev/null &"
    }

    /** Command for a computer: resolves the APK path on the phone, so it survives app updates. */
    fun computerCommand(context: Context): String =
        "adb shell 'CLASSPATH=\$(pm path ${context.packageName} | head -1 | cut -d: -f2) setsid nohup app_process " +
            "/system/bin ${HelperMain::class.java.name} > $LOG 2>&1 < /dev/null &'"

    fun start(context: Context) {
        val app = context.applicationContext
        if (_state.value is LaunchState.Working) return
        _state.value = LaunchState.Working("Looking for Wireless debugging…")
        worker.execute {
            _state.value = try {
                launch(app)
            } catch (e: AdbPairingRequiredException) {
                Settings.update { it.copy(paired = false) }
                LaunchState.Failed("Not paired yet. Tap Pair and enter the Wireless debugging code.")
            } catch (e: Throwable) {
                android.util.Log.w("Halo", "helper launch failed", e)
                LaunchState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun launch(app: Context): LaunchState {
        if (app.checkSelfPermission(LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return LaunchState.Failed("Allow \"Nearby devices / local network\" access in Halo › Setup first.")
        }
        val client = AdbClient.get(app)
        val port = AdbClient.discoverPort(app, AdbClient.CONNECT, 8000)
            ?: return LaunchState.Failed("Wireless debugging isn't on. Turn it on in Developer options (needs Wi-Fi).")
        _state.value = LaunchState.Working("Connecting to adbd on port $port…")
        if (client.isConnected) client.disconnect()
        if (!client.connect("127.0.0.1", port)) return LaunchState.Failed("adbd refused the connection. Try pairing again.")
        try {
            _state.value = LaunchState.Working("Starting LED helper…")
            val before = Transport.status.value.pid
            var cmd = command(app)
            // Switch Wireless debugging off a few seconds later, once this connection is gone.
            if (Settings.value.wifiAdbOffAfterStart) {
                cmd += " setsid sh -c 'sleep 4; settings put global adb_wifi_enabled 0' > /dev/null 2>&1 < /dev/null &"
            }
            // Keep the shell alive briefly: adbd kills the shell's process group when it exits, and
            // the helper must have moved into its own session (setsid) before that happens.
            cmd += " sleep 2" // command() ends with "&", which already separates commands
            runShell(client, "exec 2>$SH_LOG; $cmd")
            // Stay connected until the new helper reports in, so nothing is torn down mid-start.
            _state.value = LaunchState.Working("Waiting for the helper to report…")
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                val st = Transport.refreshStatus()
                if (st.running && st.pid != before) {
                    Coordinator.onConfigChanged() // push current state to the fresh helper
                    return LaunchState.Started
                }
                Thread.sleep(250)
            }
            return LaunchState.Failed("The helper didn't start. Check $LOG (adb shell cat $LOG).")
        } finally {
            runCatching { client.disconnect() }
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val client = AdbClient.get(app)
                val port = AdbClient.discoverPort(app, AdbClient.CONNECT, 5000) ?: return@runCatching
                if (client.isConnected) client.disconnect()
                if (client.connect("127.0.0.1", port)) {
                    val apk = app.applicationInfo.sourceDir
                    runShell(client, "CLASSPATH=$apk app_process /system/bin ${HelperMain::class.java.name} --stop")
                    client.disconnect()
                }
            }
            _state.value = LaunchState.Idle
        }
    }

    /**
     * Runs one shell command and collects its output until the shell exits or [timeoutMs] pass.
     * Reads happen on a helper thread because a libadb read can block past the shell's exit.
     */
    private fun runShell(client: AdbClient, cmd: String, timeoutMs: Long = 5000): String {
        val out = StringBuffer()
        val reader = Thread {
            try {
                client.openStream("shell:$cmd").use { stream ->
                    val input = stream.openInputStream()
                    val buf = ByteArray(512)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.append(String(buf, 0, n))
                    }
                }
            } catch (_: java.io.IOException) {
                // A command that backgrounds itself and exits at once can make adbd close the
                // stream before libadb sees it open ("Stream closed" / "actively rejected"), even
                // though the command ran. Callers confirm success another way.
            } catch (_: InterruptedException) {
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) reader.interrupt()
        return out.toString()
    }

    /** Pairs with the code shown in "Pair device with pairing code". */
    fun pair(context: Context, code: String, manualPort: Int? = null): Result<Unit> = runCatching {
        doPair(context, code, manualPort)
    }.onFailure { android.util.Log.w("Halo", "pairing failed", it) }

    private fun doPair(context: Context, code: String, manualPort: Int?) {
        val app = context.applicationContext
        val port = manualPort ?: AdbClient.discoverPort(app, AdbClient.PAIRING, 10_000)
            ?: error("Couldn't find the pairing dialog. Keep \"Pair device with pairing code\" open and try again.")
        check(AdbClient.get(app).pair("127.0.0.1", port, code.trim())) { "Pairing was rejected. Check the code." }
        Settings.update { it.copy(paired = true) }
    }
}
