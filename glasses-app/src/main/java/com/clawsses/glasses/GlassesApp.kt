package com.clawsses.glasses

import android.app.Application
import android.util.Log
import com.clawsses.glasses.terminal.SettingsStore

class GlassesApp : Application() {

    companion object {
        const val TAG = "GlassesHUD"
        lateinit var instance: GlassesApp
            private set
    }

    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsStore = SettingsStore(this)
        Log.d(TAG, "Rokid Terminal initialized")
    }
}
