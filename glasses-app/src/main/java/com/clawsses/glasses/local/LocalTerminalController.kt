package com.clawsses.glasses.local

import android.content.Context
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.clawsses.glasses.terminal.AnsiColorRemapper
import com.clawsses.glasses.terminal.ConnectionState
import com.clawsses.glasses.terminal.TerminalController
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
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.FileInputStream
import java.io.FileOutputStream

class LocalTerminalController(private val context: Context) : TerminalController {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val colorRemapper = AnsiColorRemapper()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusText = MutableStateFlow("Idle.")
    override val statusText: StateFlow<String> = _statusText.asStateFlow()

    private var ptyFd: ParcelFileDescriptor? = null
    private var shellOut: FileOutputStream? = null
    private var readerJob: Job? = null

    override val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        looper = Looper.getMainLooper(),
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color.White,
        defaultBackground = Color.Black,
        onKeyboardInput = { data -> writeToShell(data) },
        onResize = { dims -> ptyFd?.let { PtyProcess.resize(it, dims.columns, dims.rows) } }
    )

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        val env = arrayOf(
            "TERM=xterm-256color",
            "HOME=${context.filesDir.absolutePath}",
            "SHELL=/system/bin/sh",
            "PATH=/system/bin:/system/xbin:/sbin",
            "USER=shell",
            "LOGNAME=shell"
        )
        val pfd = PtyProcess.create(emulator.dimensions.columns, emulator.dimensions.rows, env)
        if (pfd == null) {
            val error = "Failed to open local PTY"
            _connectionState.value = ConnectionState.Error(error)
            _statusText.value = error
            return@withContext Result.failure(RuntimeException(error))
        }
        ptyFd = pfd
        shellOut = FileOutputStream(pfd.fileDescriptor)
        _connectionState.value = ConnectionState.Connected(fingerprint = "", trustedOnFirstUse = false)
        _statusText.value = "Local shell"
        startReader(pfd)
        Result.success(Unit)
    }

    override fun close() {
        closePty()
        _connectionState.value = ConnectionState.Disconnected
        scope.cancel()
    }

    private fun closePty() {
        readerJob?.cancel()
        readerJob = null
        try { shellOut?.close() } catch (_: Exception) {}
        try { ptyFd?.close() } catch (_: Exception) {}
        shellOut = null
        ptyFd = null
    }

    private fun startReader(pfd: ParcelFileDescriptor) {
        readerJob?.cancel()
        colorRemapper.reset()
        readerJob = scope.launch {
            val input = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val remapped = colorRemapper.process(buffer, count)
                        emulator.writeInput(remapped, 0, remapped.size)
                    } else {
                        delay(20)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PTY reader stopped", e)
            } finally {
                _statusText.value = "Shell closed."
                if (_connectionState.value is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    private fun writeToShell(data: ByteArray) {
        try { shellOut?.write(data) } catch (e: Exception) { Log.w(TAG, "PTY write failed", e) }
    }

    private companion object {
        const val TAG = "LocalTerminal"
    }
}
