package com.clawsses.glasses

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawsses.glasses.terminal.ConnectionState
import com.clawsses.glasses.terminal.SettingsStore
import com.clawsses.glasses.terminal.SshConfig
import com.clawsses.glasses.terminal.SshTerminalManager
import com.clawsses.glasses.terminal.TmuxSessionInfo
import com.clawsses.glasses.ui.theme.GlassesHudTheme
import kotlinx.coroutines.launch

class ControlSurfaceActivity : ComponentActivity() {

    private val manager: SshTerminalManager
        get() = GlassesApp.instance.sshTerminalManager

    private val settingsStore: SettingsStore
        get() = GlassesApp.instance.settingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlassesHudTheme {
                ControlSurfaceScreen(
                    manager = manager,
                    settingsStore = settingsStore,
                    openTerminal = {
                        startActivity(Intent(this, TerminalActivity::class.java))
                    }
                )
            }
        }
    }
}

private enum class SurfaceAction(val label: String) {
    CONNECT("Connect"),
    TERMINAL("Terminal"),
    REFRESH("Refresh"),
    CREATE("New tmux"),
    NEXT("Next Sess"),
    SETTINGS("Settings")
}

@Composable
private fun ControlSurfaceScreen(
    manager: SshTerminalManager,
    settingsStore: SettingsStore,
    openTerminal: () -> Unit
) {
    val connectionState by manager.connectionState.collectAsState()
    val sessions by manager.sessions.collectAsState()
    val activeSession by manager.activeSession.collectAsState()
    val snapshot by manager.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val savedConfig = remember { settingsStore.loadConfig() }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf(savedConfig.host) }
    var port by rememberSaveable { mutableStateOf(savedConfig.port.toString()) }
    var username by rememberSaveable { mutableStateOf(savedConfig.username) }
    var password by rememberSaveable { mutableStateOf(savedConfig.password) }
    var privateKey by rememberSaveable { mutableStateOf(savedConfig.privateKey) }
    var passphrase by rememberSaveable { mutableStateOf(savedConfig.passphrase) }
    var autoAttach by rememberSaveable { mutableStateOf(savedConfig.autoAttachCommand) }

    val actions = listOf(
        SurfaceAction.CONNECT,
        SurfaceAction.TERMINAL,
        SurfaceAction.REFRESH,
        SurfaceAction.CREATE,
        SurfaceAction.NEXT,
        SurfaceAction.SETTINGS
    )

    fun currentConfig(): SshConfig {
        return SshConfig(
            host = host.trim(),
            port = port.toIntOrNull() ?: 22,
            username = username.trim(),
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            autoAttachCommand = autoAttach.trim().ifBlank { "tmux attach || tmux new" }
        )
    }

    fun activate(action: SurfaceAction) {
        when (action) {
            SurfaceAction.CONNECT -> {
                settingsStore.saveConfig(currentConfig())
                scope.launch {
                    if (connectionState is ConnectionState.Connected) {
                        manager.disconnect()
                    } else {
                        manager.connect()
                    }
                }
            }
            SurfaceAction.TERMINAL -> {
                settingsStore.saveConfig(currentConfig())
                openTerminal()
            }
            SurfaceAction.REFRESH -> scope.launch {
                manager.refreshSessions()
                manager.refreshSnapshot()
            }
            SurfaceAction.CREATE -> scope.launch {
                manager.createSession()
            }
            SurfaceAction.NEXT -> scope.launch {
                manager.cycleSession(1)
            }
            SurfaceAction.SETTINGS -> {
                if (showSettings) {
                    settingsStore.saveConfig(currentConfig())
                }
                showSettings = !showSettings
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> {
                        selectedIndex = (selectedIndex + 1).coerceAtMost(actions.lastIndex)
                        true
                    }
                    Key.DirectionLeft -> {
                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        true
                    }
                    Key.DirectionDown -> {
                        selectedIndex = (selectedIndex + 2).coerceAtMost(actions.lastIndex)
                        true
                    }
                    Key.DirectionUp -> {
                        selectedIndex = (selectedIndex - 2).coerceAtLeast(0)
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        activate(actions[selectedIndex])
                        true
                    }
                    Key.Escape -> {
                        if (showSettings) {
                            settingsStore.saveConfig(currentConfig())
                            showSettings = false
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            StatusHeader(
                connectionState = connectionState,
                config = currentConfig(),
                activeSession = activeSession
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.weight(1.2f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActionGrid(
                        actions = actions,
                        selectedIndex = selectedIndex,
                        onActivate = { action -> activate(action) }
                    )
                    if (showSettings) {
                        SettingsPanel(
                            host = host,
                            port = port,
                            username = username,
                            password = password,
                            privateKey = privateKey,
                            passphrase = passphrase,
                            autoAttach = autoAttach,
                            onHostChange = { host = it },
                            onPortChange = { port = it },
                            onUsernameChange = { username = it },
                            onPasswordChange = { password = it },
                            onPrivateKeyChange = { privateKey = it },
                            onPassphraseChange = { passphrase = it },
                            onAutoAttachChange = { autoAttach = it }
                        )
                    } else {
                        SnapshotPanel(snapshot.content.ifBlank { "Connect and pick a tmux session." })
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                SessionRail(
                    modifier = Modifier.weight(0.8f),
                    sessions = sessions,
                    activeSession = activeSession,
                    onSelect = { session ->
                        scope.launch {
                            manager.selectSession(session.name)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusHeader(
    connectionState: ConnectionState,
    config: SshConfig,
    activeSession: String?
) {
    Surface(
        color = Color.Black,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().border(2.dp, Color(0xFF00FF00), RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "ROKID TERMINAL",
                color = Color(0xFF00FF00),
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when (connectionState) {
                    is ConnectionState.Connected -> "SSH connected"
                    is ConnectionState.Connecting -> "Connecting..."
                    is ConnectionState.Error -> "Error: ${connectionState.message}"
                    ConnectionState.Disconnected -> "Disconnected"
                },
                color = if (connectionState is ConnectionState.Error) Color(0xFFFF6666) else Color(0xFFB8FFB8),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Text(
                text = "Host: ${config.username}@${config.host.ifBlank { "unset" }}:${config.port}",
                color = Color(0xFF66FFCC),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                text = "tmux: ${activeSession ?: "none"}",
                color = Color(0xFF66FFCC),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                text = "Arrows move focus  Enter activates  Esc closes settings",
                color = Color(0xFF88CC88),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ActionGrid(
    actions: List<SurfaceAction>,
    selectedIndex: Int,
    onActivate: (SurfaceAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (rowStart in actions.indices step 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val rowItems = actions.subList(rowStart, minOf(rowStart + 2, actions.size))
                rowItems.forEachIndexed { offset, action ->
                    val index = rowStart + offset
                    val selected = selectedIndex == index
                    Surface(
                        color = Color.Black,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(94.dp)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) Color(0xFF00FF00) else Color(0xFF226622),
                                shape = RoundedCornerShape(12.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = action.label,
                                color = Color(0xFF00FF00),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRail(
    modifier: Modifier,
    sessions: List<TmuxSessionInfo>,
    activeSession: String?,
    onSelect: (TmuxSessionInfo) -> Unit
) {
    Surface(
        color = Color.Black,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.border(2.dp, Color(0xFF00FF00), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Text(
                text = "SESSIONS",
                color = Color(0xFF00FF00),
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sessions.isEmpty()) {
                    item {
                        Text(
                            text = "No tmux sessions yet.",
                            color = Color(0xFF88CC88),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    itemsIndexed(sessions) { _, session ->
                        val selected = session.name == activeSession
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(session) }
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) Color(0xFF00FF00) else Color(0xFF225522),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        ) {
                            Column {
                                Text(
                                    text = session.name,
                                    color = Color(0xFF00FF00),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${session.windows} windows  attached=${if (session.attached) "yes" else "no"}",
                                    color = Color(0xFF88CC88),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotPanel(content: String) {
    Surface(
        color = Color.Black,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .border(2.dp, Color(0xFF00FF00), RoundedCornerShape(12.dp))
    ) {
        Text(
            text = content,
            color = Color(0xFFB8FFB8),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun SettingsPanel(
    host: String,
    port: String,
    username: String,
    password: String,
    privateKey: String,
    passphrase: String,
    autoAttach: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPrivateKeyChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onAutoAttachChange: (String) -> Unit
) {
    Surface(
        color = Color.Black,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Color(0xFF00FF00), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "SETTINGS",
                color = Color(0xFF00FF00),
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp
            )
            SettingsField(value = host, onValueChange = onHostChange, label = "Host")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsField(
                    value = port,
                    onValueChange = onPortChange,
                    label = "Port",
                    modifier = Modifier.weight(0.4f)
                )
                SettingsField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = "User",
                    modifier = Modifier.weight(0.6f)
                )
            }
            SettingsField(value = password, onValueChange = onPasswordChange, label = "Password")
            SettingsField(value = passphrase, onValueChange = onPassphraseChange, label = "Key Passphrase")
            SettingsField(
                value = autoAttach,
                onValueChange = onAutoAttachChange,
                label = "Auto Attach"
            )
            SettingsField(
                value = privateKey,
                onValueChange = onPrivateKeyChange,
                label = "Private Key",
                singleLine = false
            )
            Text(
                text = "Select Settings again to save and close. Escape closes settings too.",
                color = Color(0xFF88CC88),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color(0xFF88CC88)) },
        minLines = if (singleLine) 1 else 4,
        maxLines = if (singleLine) 1 else 8,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF00FF00),
            unfocusedBorderColor = Color(0xFF226622),
            focusedTextColor = Color(0xFF00FF00),
            unfocusedTextColor = Color(0xFF00FF00),
            focusedContainerColor = Color.Black,
            unfocusedContainerColor = Color.Black,
            cursorColor = Color(0xFF00FF00)
        ),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = Color(0xFF00FF00),
            fontFamily = FontFamily.Monospace
        )
    )
}
