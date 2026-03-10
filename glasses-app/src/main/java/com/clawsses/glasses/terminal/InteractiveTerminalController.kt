package com.clawsses.glasses.terminal

import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class InteractiveTerminalController(
    private val settingsStore: SettingsStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionLock = Mutex()
    private val writeLock = Mutex()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusText = MutableStateFlow("Idle.")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellOutput: OutputStream? = null
    private var readerJob: Job? = null

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        looper = Looper.getMainLooper(),
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color(0xFFB8FFB8),
        defaultBackground = Color.Black,
        onKeyboardInput = { data ->
            scope.launch {
                writeToShell(data)
            }
        },
        onResize = { dimensions ->
            scope.launch {
                resizeShell(dimensions)
            }
        }
    )

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            val config = settingsStore.loadConfig()
            if (config.host.isBlank() || config.username.isBlank()) {
                val error = "Host and username are required."
                _connectionState.value = ConnectionState.Error(error)
                _statusText.value = error
                return@withContext Result.failure(IllegalArgumentException(error))
            }

            disconnectLocked()
            _connectionState.value = ConnectionState.Connecting
            _statusText.value = "Connecting to ${config.username}@${config.host}..."

            val localJsch = JSch()
            if (config.privateKey.isNotBlank()) {
                localJsch.addIdentity(
                    "rokid-terminal-shell",
                    config.privateKey.toByteArray(StandardCharsets.UTF_8),
                    null,
                    config.passphrase.takeIf { it.isNotBlank() }?.toByteArray(StandardCharsets.UTF_8)
                )
            }

            try {
                val newSession = localJsch.getSession(config.username, config.host, config.port)
                if (config.password.isNotBlank()) {
                    newSession.setPassword(config.password)
                }
                newSession.setConfig("StrictHostKeyChecking", "no")
                newSession.timeout = 10_000
                newSession.connect(10_000)

                val fingerprint = newSession.hostKey?.getFingerPrint(localJsch)
                    ?: throw JSchException("No host key received.")
                val storedFingerprint = settingsStore.loadTrustedFingerprint(
                    config.host,
                    config.port,
                    config.username
                )

                val trustedOnFirstUse = when {
                    storedFingerprint == null -> {
                        settingsStore.saveTrustedFingerprint(
                            config.host,
                            config.port,
                            config.username,
                            fingerprint
                        )
                        true
                    }
                    storedFingerprint != fingerprint -> {
                        newSession.disconnect()
                        val error = "Host key changed. Saved=$storedFingerprint live=$fingerprint"
                        _connectionState.value = ConnectionState.Error(error)
                        _statusText.value = error
                        return@withContext Result.failure(IllegalStateException(error))
                    }
                    else -> false
                }

                val newShell = (newSession.openChannel("shell") as ChannelShell).apply {
                    setPtyType("xterm-256color", emulator.dimensions.columns, emulator.dimensions.rows, 0, 0)
                    setEnv("TERM", "xterm-256color")
                    inputStream = null
                    connect(10_000)
                }

                session = newSession
                shellChannel = newShell
                shellOutput = newShell.outputStream
                _connectionState.value = ConnectionState.Connected(
                    fingerprint = fingerprint,
                    trustedOnFirstUse = trustedOnFirstUse
                )
                _statusText.value = "Connected to ${config.username}@${config.host}"

                startReader(newShell.inputStream)
                sendInitialAttach(config)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Interactive SSH connect failed", e)
                disconnectLocked()
                val error = e.message ?: "Interactive shell connection failed"
                _connectionState.value = ConnectionState.Error(error)
                _statusText.value = error
                Result.failure(e)
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            disconnectLocked()
            _connectionState.value = ConnectionState.Disconnected
            _statusText.value = "Disconnected."
        }
    }

    fun close() {
        disconnectLocked()
        _connectionState.value = ConnectionState.Disconnected
        _statusText.value = "Disconnected."
        scope.cancel()
    }

    private suspend fun sendInitialAttach(config: SshConfig) {
        val targetSession = settingsStore.loadActiveSession()?.trim().orEmpty()
        val attachCommand = if (targetSession.isNotBlank()) {
            "tmux new-session -A -s ${shellQuote(targetSession)}"
        } else {
            config.autoAttachCommand.trim().ifBlank { "tmux attach || tmux new" }
        }
        writeToShell((attachCommand + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    private fun startReader(input: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch {
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        emulator.writeInput(buffer, 0, count)
                    } else {
                        delay(20)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shell reader stopped", e)
            } finally {
                _statusText.value = "Shell closed."
                if (_connectionState.value is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    private suspend fun writeToShell(data: ByteArray) {
        writeLock.withLock {
            val output = shellOutput ?: return
            output.write(data)
            output.flush()
        }
    }

    private suspend fun resizeShell(dimensions: TerminalDimensions) {
        sessionLock.withLock {
            try {
                shellChannel?.setPtySize(dimensions.columns, dimensions.rows, 0, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resize PTY", e)
            }
        }
    }

    private fun disconnectLocked() {
        readerJob?.cancel()
        readerJob = null
        try {
            shellChannel?.disconnect()
        } catch (_: Exception) {
        }
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        shellOutput = null
        shellChannel = null
        session = null
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private companion object {
        const val TAG = "InteractiveTerminal"
    }
}
