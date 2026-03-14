package com.clawsses.glasses.terminal

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SshTerminalManager(
    private val settingsStore: SettingsStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioMutex = Mutex()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _sessions = MutableStateFlow<List<TmuxSessionInfo>>(emptyList())
    val sessions: StateFlow<List<TmuxSessionInfo>> = _sessions.asStateFlow()

    private val _activeSession = MutableStateFlow<String?>(null)
    val activeSession: StateFlow<String?> = _activeSession.asStateFlow()

    private val _snapshot = MutableStateFlow(TerminalSnapshot())
    val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    private var jsch: JSch? = null
    private var session: Session? = null

    suspend fun connect(forceRetrust: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val config = settingsStore.loadConfig()
            if (config.host.isBlank() || config.username.isBlank()) {
                _connectionState.value = ConnectionState.Error("Host and username are required.")
                return@withContext Result.failure(IllegalArgumentException("Missing SSH settings"))
            }

            _connectionState.value = ConnectionState.Connecting
            disconnectLocked()

            val localJsch = JSch()
            if (config.privateKey.isNotBlank()) {
                localJsch.addIdentity(
                    "rokid-terminal",
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

                val hostKey = newSession.hostKey ?: throw JSchException("No host key received.")
                val fingerprint = hostKey.getFingerPrint(localJsch)
                val storedFingerprint = if (forceRetrust) null
                else settingsStore.loadTrustedFingerprint(config.host, config.port, config.username)

                when {
                    storedFingerprint == null -> {
                        settingsStore.saveTrustedFingerprint(
                            config.host,
                            config.port,
                            config.username,
                            fingerprint
                        )
                        _connectionState.value = ConnectionState.Connected(
                            fingerprint = fingerprint,
                            trustedOnFirstUse = true
                        )
                    }
                    storedFingerprint != fingerprint -> {
                        newSession.disconnect()
                        _connectionState.value = ConnectionState.Error(
                            "Host key changed. Saved=$storedFingerprint live=$fingerprint"
                        )
                        return@withContext Result.failure(IllegalStateException("Host key mismatch"))
                    }
                    else -> {
                        _connectionState.value = ConnectionState.Connected(
                            fingerprint = fingerprint,
                            trustedOnFirstUse = false
                        )
                    }
                }

                jsch = localJsch
                session = newSession
                _snapshot.value = TerminalSnapshot(
                    content = "",
                    statusLine = "Connected to ${config.username}@${config.host}"
                )
                refreshSessions()
                if (_activeSession.value == null) {
                    attachOrCreateDefaultSession()
                } else {
                    refreshSnapshot()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "SSH connect failed", e)
                disconnectLocked()
                _connectionState.value = ConnectionState.Error(e.message ?: "SSH connection failed")
                Result.failure(e)
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            disconnectLocked()
            _connectionState.value = ConnectionState.Disconnected
            _sessions.value = emptyList()
            _activeSession.value = null
            _snapshot.value = TerminalSnapshot(statusLine = "Disconnected.")
        }
    }

    suspend fun clearTrustedFingerprint() = withContext(Dispatchers.IO) {
        val config = settingsStore.loadConfig()
        settingsStore.clearTrustedFingerprint(config.host, config.port, config.username)
    }

    suspend fun refreshSessions(): Result<List<TmuxSessionInfo>> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!isSshConnected()) {
                _sessions.value = emptyList()
                return@withContext Result.success(emptyList())
            }
            val result = runExec(
                "tmux list-sessions -F '#{session_name}\\t#{session_windows}\\t#{session_attached}\\t#{session_activity}' 2>/dev/null || true"
            )
            val sessions = result.stdout.lines()
                .mapNotNull { parseSessionLine(it) }
                .sortedBy { it.name.lowercase(Locale.US) }
            _sessions.value = sessions
            if (_activeSession.value !in sessions.map { it.name }) {
                _activeSession.value = settingsStore.loadActiveSession()
                    ?.takeIf { saved -> sessions.any { it.name == saved } }
                    ?: sessions.firstOrNull()?.name
            }
            Result.success(sessions)
        }
    }

    suspend fun createSession(name: String? = null): Result<String> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!isSshConnected()) {
                return@withContext Result.failure(IllegalStateException("SSH session is not connected"))
            }
            val newName = name?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "rokid-${SimpleDateFormat("HHmmss", Locale.US).format(Date())}"
            runExec("tmux new-session -d -s ${shellQuote(newName)}")
            _activeSession.value = newName
            settingsStore.saveActiveSession(newName)
            refreshSessions()
            refreshSnapshot()
            Result.success(newName)
        }
    }

    suspend fun selectSession(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!isSshConnected()) {
                return@withContext Result.failure(IllegalStateException("SSH session is not connected"))
            }
            _activeSession.value = name
            settingsStore.saveActiveSession(name)
            refreshSnapshot()
            Result.success(Unit)
        }
    }

    suspend fun cycleSession(direction: Int): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!isSshConnected()) {
                return@withContext Result.failure(IllegalStateException("SSH session is not connected"))
            }
            val available = _sessions.value
            if (available.isEmpty()) return@withContext Result.failure(IllegalStateException("No tmux sessions"))
            val currentIndex = available.indexOfFirst { it.name == _activeSession.value }.coerceAtLeast(0)
            val nextIndex = (currentIndex + direction).mod(available.size)
            _activeSession.value = available[nextIndex].name
            settingsStore.saveActiveSession(available[nextIndex].name)
            refreshSnapshot()
            Result.success(Unit)
        }
    }

    suspend fun sendCommand(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!isSshConnected()) {
                return@withContext Result.failure(IllegalStateException("SSH session is not connected"))
            }
            val trimmed = command.trim()
            if (trimmed.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Command is empty"))
            }
            val target = _activeSession.value
                ?: return@withContext Result.failure(IllegalStateException("No active tmux session"))

            runExec(
                "tmux send-keys -t ${shellQuote(target)} -l -- ${shellQuote(trimmed)} ; " +
                    "tmux send-keys -t ${shellQuote(target)} Enter"
            )
            delay(180)
            _snapshot.value = _snapshot.value.copy(lastCommand = trimmed)
            refreshSnapshot()
            Result.success(Unit)
        }
    }

    suspend fun refreshSnapshot(lines: Int = 160): Result<String> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!isSshConnected()) {
                return@withContext Result.failure(IllegalStateException("SSH session is not connected"))
            }
            val target = _activeSession.value
                ?: return@withContext Result.failure(IllegalStateException("No active tmux session"))
            val result = runExec(
                "tmux capture-pane -p -t ${shellQuote(target)} -S -$lines"
            )
            val body = result.stdout.ifBlank { result.stderr.ifBlank { "(empty pane)" } }
            _snapshot.value = _snapshot.value.copy(
                content = body.trimEnd(),
                statusLine = "Session: $target"
            )
            Result.success(body)
        }
    }

    private suspend fun attachOrCreateDefaultSession() {
        val config = settingsStore.loadConfig()
        val autoAttach = config.autoAttachCommand.trim()
        if (autoAttach.isNotEmpty()) {
            runExec(autoAttach)
        }
        refreshSessions()
        if (_activeSession.value == null) {
            createSession()
        }
    }

    private fun disconnectLocked() {
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        session = null
        jsch = null
    }

    private fun parseSessionLine(line: String): TmuxSessionInfo? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.isEmpty()) return null
        return TmuxSessionInfo(
            name = parts.getOrElse(0) { return null },
            windows = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            attached = parts.getOrNull(2) == "1",
            activity = parts.getOrNull(3) ?: ""
        )
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun isSshConnected(): Boolean {
        return session?.isConnected == true
    }

    private suspend fun runExec(command: String): ExecResult = withContext(Dispatchers.IO) {
        val liveSession = session
        if (liveSession == null || !liveSession.isConnected) {
            throw IllegalStateException("SSH session is not connected")
        }

        val channel = (liveSession.openChannel("exec") as ChannelExec).apply {
            setCommand(command)
            setInputStream(null)
            setErrStream(null)
        }

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        channel.setOutputStream(stdout)
        channel.setErrStream(stderr)

        channel.connect(10_000)
        while (!channel.isClosed) {
            delay(40)
        }
        val exitStatus = channel.exitStatus
        channel.disconnect()

        ExecResult(
            stdout = stdout.toString(StandardCharsets.UTF_8.name()),
            stderr = stderr.toString(StandardCharsets.UTF_8.name()),
            exitStatus = exitStatus
        )
    }

    private data class ExecResult(
        val stdout: String,
        val stderr: String,
        val exitStatus: Int
    )

    private companion object {
        const val TAG = "SshTerminalManager"
    }
}
