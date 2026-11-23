package com.shashankanil.halo

import android.app.Application

class HaloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Transport.init(this)
        Monitors.start(this)
        // Push the current (usually empty) state so a running helper matches the app.
        Coordinator.onConfigChanged()
    }
}
