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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class InteractiveTerminalController(
    private val settingsStore: SettingsStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionLock = Mutex()
    private val writeLock = Mutex()
    private var sgrStripBuffer = ByteArray(0)

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
        defaultForeground = Color.White,
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
                    setPtyType("xterm", emulator.dimensions.columns, emulator.dimensions.rows, 0, 0)
                    setEnv("TERM", "xterm")
                    setEnv("NO_COLOR", "1")
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
        // vt100 as default-terminal means new panes get TERM=vt100, which bash
        // treats as color-incapable and skips its colored PS1 automatically.
        val fullCommand = "tmux set-option -g default-terminal vt100 2>/dev/null; $attachCommand"
        writeToShell((fullCommand + "\n").toByteArray(StandardCharsets.UTF_8))
        // After tmux attaches, writes go to the active pane — push a plain PS1 there.
        delay(1200)
        writeToShell("unset PROMPT_COMMAND; export PS1='\\u@\\h:\\w\\$ '\n".toByteArray(StandardCharsets.UTF_8))
    }

    // Maps one SGR parameter list (e.g. "0;32" or "38;2;255;0;0") to a
    // translated sequence using 3 gray luminance levels so the monochrome
    // green display renders them as bright / medium / dim green.
    private fun remapSgr(params: String): ByteArray {
        if (params.isEmpty()) return "[0m".toByteArray()
        val codes = params.split(";").map { it.toIntOrNull() ?: 0 }
        val out = mutableListOf<String>()
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i]) {
                0 -> { out.add("0"); i++ }
                in FG_LEVEL -> { out.add(FG_LEVEL[c]!!); i++ }
                39 -> { out.add("39"); i++ }
                in 40..47, in 100..107 -> { out.add("49"); i++ }
                49 -> { out.add("49"); i++ }
                38 -> when {
                    i + 2 < codes.size && codes[i + 1] == 5 -> {
                        out.add(color256Level(codes[i + 2])); i += 3
                    }
                    i + 4 < codes.size && codes[i + 1] == 2 -> {
                        out.add(lumaLevel(rgbLuma(codes[i + 2], codes[i + 3], codes[i + 4]))); i += 5
                    }
                    else -> i++
                }
                48 -> when {
                    i + 2 < codes.size && codes[i + 1] == 5 -> { out.add("49"); i += 3 }
                    i + 4 < codes.size && codes[i + 1] == 2 -> { out.add("49"); i += 6 }
                    else -> i++
                }
                else -> i++  // drop bold, underline, blink, etc.
            }
        }
        if (out.isEmpty()) return ByteArray(0)
        return "[${out.joinToString(";")}m".toByteArray()
    }

    private fun remapColors(data: ByteArray, length: Int): ByteArray {
        val input = if (sgrStripBuffer.isNotEmpty()) sgrStripBuffer + data.copyOf(length)
                    else data.copyOf(length)
        sgrStripBuffer = ByteArray(0)
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i]
            if (b == 0x1B.toByte() && i + 1 < input.size && input[i + 1] == '['.code.toByte()) {
                var j = i + 2
                while (j < input.size && (input[j] < 0x40 || input[j] > 0x7E)) j++
                if (j >= input.size) { sgrStripBuffer = input.copyOfRange(i, input.size); break }
                if (input[j] == 'm'.code.toByte()) {
                    val mapped = remapSgr(String(input, i + 2, j - i - 2, Charsets.US_ASCII))
                    out.write(mapped)
                } else {
                    out.write(input, i, j - i + 1)
                }
                i = j + 1
            } else {
                out.write(b.toInt()); i++
            }
        }
        return out.toByteArray()
    }

    private fun startReader(input: InputStream) {
        readerJob?.cancel()
        sgrStripBuffer = ByteArray(0)
        readerJob = scope.launch {
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val remapped = remapColors(buffer, count)
                        emulator.writeInput(remapped, 0, remapped.size)
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

        // 3 gray luminance levels — on a green phosphor display these render
        // as bright / medium / dim green. White = max brightness.
        private const val L_BRIGHT = "38;2;255;255;255"  // white  → bright green
        private const val L_MED    = "38;2;160;160;160"  // gray   → medium green
        private const val L_DIM    = "38;2;70;70;70"     // dark   → dim green

        private fun lumaLevel(luma: Int) = when {
            luma >= 160 -> L_BRIGHT
            luma >= 70  -> L_MED
            else        -> L_DIM
        }

        private fun rgbLuma(r: Int, g: Int, b: Int) =
            (0.299 * r + 0.587 * g + 0.114 * b).toInt()

        private fun color256Level(n: Int): String = when {
            n in 0..7   -> FG_LEVEL[n + 30] ?: L_MED
            n in 8..15  -> FG_LEVEL[n - 8 + 90] ?: L_MED
            n in 16..231 -> {
                val idx = n - 16
                val r = if (idx / 36 == 0) 0 else 55 + (idx / 36) * 40
                val g = if ((idx / 6) % 6 == 0) 0 else 55 + ((idx / 6) % 6) * 40
                val b = if (idx % 6 == 0) 0 else 55 + (idx % 6) * 40
                lumaLevel(rgbLuma(r, g, b))
            }
            else -> lumaLevel(8 + (n - 232) * 10)  // 232–255 grayscale ramp
        }

        // ANSI 16 foreground color codes mapped to luminance levels
        private val FG_LEVEL = mapOf(
            30 to L_DIM,    // black      → dim (not invisible — would vanish on black bg)
            31 to L_DIM,    // dark red
            32 to L_MED,    // dark green
            33 to L_MED,    // dark yellow
            34 to L_DIM,    // dark blue
            35 to L_DIM,    // dark magenta
            36 to L_MED,    // dark cyan
            37 to L_MED,    // light gray
            90 to L_DIM,    // bright black / dark gray
            91 to L_MED,    // bright red
            92 to L_BRIGHT, // bright green
            93 to L_BRIGHT, // bright yellow
            94 to L_MED,    // bright blue
            95 to L_MED,    // bright magenta
            96 to L_BRIGHT, // bright cyan
            97 to L_BRIGHT  // bright white
        )
    }
}
