package com.shashankanil.halo

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * Notification access does two jobs: it is how Halo sees calls and messages, and because Android
 * keeps a bound listener alive, it keeps the app process (and the battery, camera and microphone
 * monitors in [Monitors]) running in the background.
 */
class HaloListenerService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    /** Newest message stamp handled per notification key, so re-posts don't flash again. */
    private val seen = HashMap<String, Long>()

    override fun onListenerConnected() {
        connected = true
        Monitors.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val info = extract(sbn)
        val cfg = Settings.value

        if (info.isCall) {
            if (info.callType != 1 && info.callType != 0) {
                Coordinator.clearCall(sbn.key)
                return
            }
        } else {
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            val stamp = stampOf(sbn)
            val previous = seen[sbn.key]
            if (previous != null && stamp <= previous) return
            seen[sbn.key] = stamp
            if (seen.size > 500) seen.keys.take(250).forEach { seen.remove(it) }
        }

        val decision = NotificationMatch.decide(info, cfg) ?: return
        val ranking = Ranking()
        val ranked = currentRanking?.getRanking(sbn.key, ranking) == true
        // Silent notifications (low importance) never light the ring, except calls.
        if (!info.isCall && ranked && ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (cfg.respectDnd && ranked && !ranking.matchesInterruptionFilter() &&
            !(decision.favourite && cfg.favouritesBypassDnd)
        ) return
        if (NotificationMatch.inQuietHours(cfg, Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) &&
            !(decision.favourite && cfg.favouritesBypassDnd)
        ) return
        if (cfg.onlyScreenOff && getSystemService(PowerManager::class.java).isInteractive) return

        val fire = {
            if (info.isCall) Coordinator.setCall(sbn.key, decision.look)
            else {
                Coordinator.showAlert(sbn.key, decision.look)
                Coordinator.addReminder(sbn.key, decision.look)
            }
        }
        if (cfg.onlyFaceDown) FaceDown.check(this) { down -> if (down) fire() } else fire()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Coordinator.clearCall(sbn.key)
        Coordinator.removeReminder(sbn.key)
    }

    private fun stampOf(sbn: StatusBarNotification): Long {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        val last = style?.messages?.lastOrNull()?.timestamp
        return last ?: sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime
    }

    private fun extract(sbn: StatusBarNotification): NotifInfo {
        val n = sbn.notification
        val extras = n.extras
        val uris = mutableListOf<String>()
        val senders = mutableListOf<String>()
        @Suppress("DEPRECATION")
        extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)?.forEach { p ->
            p.uri?.let { uris += it }
            p.key?.let { uris += it }
        }
        val callPerson = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        callPerson?.uri?.let { uris += it }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        style?.messages?.takeLast(3)?.forEach { m ->
            m.person?.name?.toString()?.let { senders += it }
            m.person?.uri?.let { uris += it }
        }
        val isCall = n.category == Notification.CATEGORY_CALL
        val title = (callPerson?.name ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString() ?: ""
        return NotifInfo(
            pkg = sbn.packageName,
            key = sbn.key,
            title = title,
            senders = senders,
            personUris = uris,
            // Only the explicit flag says "group"; androidx fills conversationTitle for 1:1 chats too.
            isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            isCall = isCall,
            callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0),
        )
    }

    companion object {
        @Volatile var connected = false
            private set
    }
}

/** One accelerometer sample: is the phone lying screen-down (HiLight facing up)? */
object FaceDown {
    fun check(context: Context, result: (Boolean) -> Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return result(false)
        val main = Handler(Looper.getMainLooper())
        var done = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (done) return
                done = true
                sm.unregisterListener(this)
                result(e.values[2] < -7f)
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI, main)
        main.postDelayed({
            if (!done) {
                done = true
                sm.unregisterListener(listener)
                result(false)
            }
        }, 500)
    }
}
