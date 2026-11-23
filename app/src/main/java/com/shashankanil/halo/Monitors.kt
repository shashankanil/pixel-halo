package com.shashankanil.halo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import com.shashankanil.halo.adb.HelperLauncher
import com.shashankanil.halo.core.Effect
import com.shashankanil.halo.core.Layer
import com.shashankanil.halo.core.Look

/**
 * Background signals that light the ring without a notification: charging, low battery, camera or
 * microphone in use (the recording light), the screen turning on, and Wireless debugging being
 * switched on (to start the helper automatically). All public APIs, no extra permissions.
 */
object Monitors {
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private lateinit var app: Context

    private var plugged = false
    private var level = 0
    private var full = false
    private var lastLowWarning = 0L
    private val busyCameras = HashSet<String>()
    private var micBusy = false

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext

        app.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        app.registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_ON))

        // Recording light: another app opening a camera makes it "unavailable" to everyone else.
        app.getSystemService(CameraManager::class.java).registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(id: String) { busyCameras += id; privacyChanged() }
            override fun onCameraAvailable(id: String) { busyCameras -= id; privacyChanged() }
        }, main)
        val audio = app.getSystemService(AudioManager::class.java)
        audio.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                micBusy = configs.any { !it.isClientSilenced }
                privacyChanged()
            }
        }, main)
        micBusy = audio.activeRecordingConfigurations.any { !it.isClientSilenced }

        app.contentResolver.registerContentObserver(
            SystemSettings.Global.getUriFor(ADB_WIFI), false,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = maybeAutoStart()
            },
        )
        maybeAutoStart()
    }

    fun wirelessDebuggingOn(context: Context) =
        SystemSettings.Global.getInt(context.contentResolver, ADB_WIFI, 0) == 1

    private fun maybeAutoStart() {
        val cfg = Settings.value
        if (cfg.autoStart && cfg.paired && wirelessDebuggingOn(app) && !Transport.status.value.running) {
            // Give adbd a moment to publish its mDNS service after the toggle.
            main.postDelayed({ if (!Transport.status.value.running) HelperLauncher.start(app) }, 1500)
        }
    }

    /** Camera/mic must stay busy for a moment so face unlock and hotword blips don't flash. */
    private fun privacyChanged() {
        main.removeCallbacks(applyPrivacy)
        val turningOff = busyCameras.isEmpty() && !micBusy
        main.postDelayed(applyPrivacy, if (turningOff) 0 else 1200)
    }

    private val applyPrivacy = Runnable { Coordinator.setPrivacy(mic = micBusy, camera = busyCameras.isNotEmpty()) }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (Settings.value.clearOnScreenOn) Coordinator.clearAlerts()
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val newLevel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale
            val nowPlugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            val justPlugged = nowPlugged && !plugged
            val changed = nowPlugged != plugged || newLevel != level || (status == BatteryManager.BATTERY_STATUS_FULL) != full
            plugged = nowPlugged
            level = newLevel
            full = status == BatteryManager.BATTERY_STATUS_FULL
            if (changed) updateCharging(justPlugged)
            checkLowBattery()
        }
    }

    private var chargingUntil = 0L

    fun updateCharging(justPlugged: Boolean = false) {
        val cfg = Settings.value
        val now = System.currentTimeMillis()
        if (justPlugged) chargingUntil = now + cfg.chargingSeconds * 1000L
        val show = plugged && when (cfg.chargingMode) {
            ChargingMode.OFF -> false
            ChargingMode.ON_PLUG -> now < chargingUntil
            ChargingMode.WHILE_CHARGING -> true
        }
        if (!show) return Coordinator.setCharging(null)
        val color = when {
            full || level >= 95 -> 0xFF00E676.toInt()
            level >= 50 -> 0xFF76FF03.toInt()
            level >= 20 -> 0xFFFFC400.toInt()
            else -> 0xFFFF1744.toInt()
        }
        val look = if (full) Look(Effect.BREATHE, color = color)
        else Look(Effect.FILL, color = color, color2 = color, value = level / 100f)
        val until = if (cfg.chargingMode == ChargingMode.ON_PLUG) chargingUntil else 0L
        Coordinator.setCharging(Layer("charging", look, until))
    }

    private fun checkLowBattery() {
        val cfg = Settings.value
        val now = System.currentTimeMillis()
        if (cfg.lowBattery.enabled && !plugged && level in 1..cfg.lowBatteryLevel && now - lastLowWarning > 5 * 60_000) {
            lastLowWarning = now
            Coordinator.showAlert("low-battery", cfg.lowBattery.look, 4)
        }
    }

    private const val ADB_WIFI = "adb_wifi_enabled"
}
