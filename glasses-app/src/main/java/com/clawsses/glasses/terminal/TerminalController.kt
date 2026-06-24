package com.clawsses.glasses.terminal

import kotlinx.coroutines.flow.StateFlow
import org.connectbot.terminal.TerminalEmulator

interface TerminalController {
    val connectionState: StateFlow<ConnectionState>
    val statusText: StateFlow<String>
    val emulator: TerminalEmulator
    suspend fun connect(): Result<Unit>
    fun close()
}
