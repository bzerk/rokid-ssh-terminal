package com.clawsses.glasses

import android.app.Application
import android.util.Log
import com.clawsses.glasses.terminal.SettingsStore
import com.clawsses.glasses.terminal.SshTerminalManager

class GlassesApp : Application() {

    companion object {
        const val TAG = "GlassesHUD"
        lateinit var instance: GlassesApp
            private set
    }

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var sshTerminalManager: SshTerminalManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsStore = SettingsStore(this)
        sshTerminalManager = SshTerminalManager(settingsStore)
        Log.d(TAG, "Rokid Terminal initialized")
    }
}
