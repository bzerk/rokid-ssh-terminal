package com.clawsses.glasses.terminal

data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = ""
)

sealed interface ConnectionState {
    object Disconnected : ConnectionState
    object Connecting : ConnectionState
    data class Connected(
        val fingerprint: String,
        val trustedOnFirstUse: Boolean
    ) : ConnectionState
    data class Error(val message: String) : ConnectionState
}
