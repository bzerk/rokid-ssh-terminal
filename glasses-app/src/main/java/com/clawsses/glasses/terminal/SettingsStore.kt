package com.clawsses.glasses.terminal

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("rokid_terminal", Context.MODE_PRIVATE)

    fun loadConfig(): SshConfig {
        return SshConfig(
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, 22),
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            privateKey = prefs.getString(KEY_PRIVATE_KEY, "") ?: "",
            passphrase = prefs.getString(KEY_PASSPHRASE, "") ?: ""
        )
    }

    fun saveConfig(config: SshConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_PRIVATE_KEY, config.privateKey)
            .putString(KEY_PASSPHRASE, config.passphrase)
            .apply()
    }

    fun loadTrustedFingerprint(host: String, port: Int, username: String): String? =
        prefs.getString(fingerprintKey(host, port, username), null)

    fun saveTrustedFingerprint(host: String, port: Int, username: String, fingerprint: String) {
        prefs.edit().putString(fingerprintKey(host, port, username), fingerprint).apply()
    }

    fun clearTrustedFingerprint(host: String, port: Int, username: String) {
        prefs.edit().remove(fingerprintKey(host, port, username)).apply()
    }

    fun loadFontSize(): Float = prefs.getFloat(KEY_FONT_SIZE, 10f)

    fun saveFontSize(size: Float) {
        prefs.edit().putFloat(KEY_FONT_SIZE, size).apply()
    }

    private fun fingerprintKey(host: String, port: Int, username: String) =
        "fp:${host.trim()}:$port:${username.trim()}"

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_PRIVATE_KEY = "private_key"
        const val KEY_PASSPHRASE = "passphrase"
        const val KEY_FONT_SIZE = "font_size"
    }
}
