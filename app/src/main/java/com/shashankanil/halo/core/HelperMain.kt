package com.shashankanil.halo.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.RandomAccessFile

/**
 * Renderer started straight out of the installed APK, either by the app's built-in wireless ADB
 * client or by hand from a computer:
 *
 *   adb shell 'CLASSPATH=$(pm path com.shashankanil.halo | head -1 | cut -d: -f2) \
 *       nohup app_process /system/bin com.shashankanil.halo.core.HelperMain > /data/local/tmp/halo.log 2>&1 &'
 *
 * It inherits CONTROL_DEVICE_LIGHTS from the shell user. The app and the helper run as different
 * users, so they exchange two small files in the app's external files directory. The app creates
 * both files; the helper only reads state.json and overwrites status.json in place, which keeps
 * the files owned by (and readable for) the app.
 *
 * Only one helper runs at a time: a new one asks the old one to quit through an abstract socket.
 */
object HelperMain {
    private const val SOCKET = "pixel-halo-helper"
    private const val DEFAULT_DIR = "/sdcard/Android/data/com.shashankanil.halo/files"

    @JvmStatic
    fun main(args: Array<String>) {
        HaloLog.toStdout = true
        val dir = File(argValue(args, "--dir") ?: DEFAULT_DIR)
        val tag = argValue(args, "--tag") ?: "adb"
        if ("--stop" in args) {
            HaloLog.i(if (askOldHelperToQuit()) "asked running helper to stop" else "no helper running")
            return
        }

        val server = claimSocket() ?: run {
            HaloLog.w("could not take over from the running helper")
            System.exit(1)
            return
        }
        val quitFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread({
            while (!quitFlag.get()) {
                try {
                    server.accept().use { s -> if (s.inputStream.read() == 'q'.code) quitFlag.set(true) }
                } catch (_: Exception) {
                    quitFlag.set(true)
                }
            }
        }, "halo-socket").apply { isDaemon = true; start() }

        val engine = Engine(transport = tag)
        engine.start()
        Runtime.getRuntime().addShutdownHook(Thread { engine.stop() })
        HaloLog.i("helper up as uid ${android.os.Process.myUid()}, watching $dir")

        val stateFile = File(dir, "state.json")
        val statusFile = File(dir, "status.json")
        var lastStamp = ""
        var lastStatus = 0L
        while (!quitFlag.get()) {
            val stamp = "${stateFile.lastModified()}:${stateFile.length()}"
            if (stamp != lastStamp && stateFile.exists()) {
                try {
                    val text = stateFile.readText()
                    if (text.isNotBlank()) {
                        engine.setState(text)
                        lastStamp = stamp
                    }
                } catch (e: Exception) {
                    HaloLog.w("read state failed: ${e.rootMessage()}")
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastStatus >= 1000) {
                lastStatus = now
                writeInPlace(statusFile, engine.status())
            }
            Thread.sleep(50)
        }
        HaloLog.i("helper stopping")
        engine.stop()
        writeInPlace(statusFile, "{}")
        try { server.close() } catch (_: Exception) { }
        System.exit(0)
    }

    private fun argValue(args: Array<String>, name: String): String? {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    private fun claimSocket(): LocalServerSocket? {
        repeat(20) {
            try {
                return LocalServerSocket(SOCKET)
            } catch (_: Exception) {
                askOldHelperToQuit()
                Thread.sleep(150)
            }
        }
        return null
    }

    private fun askOldHelperToQuit(): Boolean = try {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress(SOCKET))
            s.outputStream.write('q'.code)
            s.outputStream.flush()
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Overwrites without recreating, so the file keeps its owner (the app). */
    private fun writeInPlace(f: File, text: String) {
        try {
            RandomAccessFile(f, "rw").use { raf ->
                val bytes = text.toByteArray()
                raf.setLength(0)
                raf.write(bytes)
            }
        } catch (e: Exception) {
            HaloLog.w("write status failed: ${e.rootMessage()}")
        }
    }
}
