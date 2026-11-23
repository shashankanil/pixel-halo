package com.shashankanil.halo.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.shashankanil.halo.R
import com.shashankanil.halo.Settings
import java.util.concurrent.Executors

/**
 * Pairing has to happen while Settings shows the "Pair device with pairing code" dialog, and that
 * dialog closes if the user switches apps. So the code is typed into a notification reply
 * instead: the user pulls down the shade, enters the code, and this service pairs in place.
 */
class PairingService : Service() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        when (intent?.action) {
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
                startForeground(ID, notification("Pairing…", "Talking to Wireless debugging", askCode = false), TYPE)
                if (code.isNullOrBlank()) {
                    show("No code entered", "Tap Pair in Halo to try again.")
                    stopSelf()
                } else worker.execute {
                    val result = HelperLauncher.pair(this, code)
                    if (result.isSuccess) {
                        show("Paired", "Halo can now drive HiLight. Starting the LED helper…")
                        HelperLauncher.start(this)
                    } else {
                        show("Pairing failed", result.exceptionOrNull()?.message ?: "Unknown error")
                    }
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
            else -> startForeground(
                ID,
                notification(
                    "Enter Wireless debugging pairing code",
                    "In Settings, tap \"Pair device with pairing code\", then reply here with the 6-digit code.",
                    askCode = true,
                ),
                TYPE,
            )
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        show("Pairing timed out", "Tap Pair in Halo to try again.")
        stopSelf()
    }

    private fun show(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification(title, text, askCode = false))
    }

    private fun notification(title: String, text: String, askCode: Boolean): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_halo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
        if (askCode) {
            val reply = PendingIntent.getForegroundService(
                this, 1, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
            b.addAction(
                Notification.Action.Builder(null, "Enter code", reply).addRemoteInput(input).build(),
            )
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL = "pairing"
        private const val ID = 7
        private const val RESULT_ID = 8
        private const val KEY_CODE = "code"
        private const val ACTION_CODE = "com.shashankanil.halo.PAIR_CODE"
        private const val TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE

        fun begin(context: Context) {
            Settings.update { it.copy(paired = false) }
            context.startForegroundService(Intent(context, PairingService::class.java))
        }

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }
}
