package com.clawsses.glasses

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.clawsses.glasses.terminal.SettingsStore
import com.clawsses.glasses.terminal.SshConfig
import com.clawsses.glasses.ui.theme.GlassesHudTheme

class ControlSurfaceActivity : ComponentActivity() {

    private val settingsStore: SettingsStore
        get() = GlassesApp.instance.settingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlassesHudTheme {
                ControlSurfaceScreen(
                    settingsStore = settingsStore,
                    openTerminal = {
                        startActivity(Intent(this, TerminalActivity::class.java))
                    },
                    openLocalShell = {
                        startActivity(
                            Intent(this, TerminalActivity::class.java)
                                .putExtra(TerminalActivity.EXTRA_LOCAL_MODE, true)
                        )
                    }
                )
            }
        }
    }
}

private enum class SurfaceAction(val label: String) {
    TERMINAL("SSH Terminal"),
    LOCAL_SHELL("Local Shell"),
    SETTINGS("Settings")
}

@Composable
private fun ControlSurfaceScreen(
    settingsStore: SettingsStore,
    openTerminal: () -> Unit,
    openLocalShell: () -> Unit
) {
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

    val actions = SurfaceAction.entries

    fun currentConfig() = SshConfig(
        host = host.trim(),
        port = port.toIntOrNull() ?: 22,
        username = username.trim(),
        password = password,
        privateKey = privateKey,
        passphrase = passphrase
    )

    fun activate(action: SurfaceAction) {
        when (action) {
            SurfaceAction.TERMINAL -> {
                settingsStore.saveConfig(currentConfig())
                openTerminal()
            }
            SurfaceAction.LOCAL_SHELL -> openLocalShell()
            SurfaceAction.SETTINGS -> {
                if (showSettings) settingsStore.saveConfig(currentConfig())
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
                    Key.DirectionRight -> { selectedIndex = (selectedIndex + 1).coerceAtMost(actions.lastIndex); true }
                    Key.DirectionLeft  -> { selectedIndex = (selectedIndex - 1).coerceAtLeast(0); true }
                    Key.Enter, Key.NumPadEnter -> { activate(actions[selectedIndex]); true }
                    Key.Escape -> {
                        if (showSettings) { settingsStore.saveConfig(currentConfig()); showSettings = false; true }
                        else false
                    }
                    else -> false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            StatusHeader(config = currentConfig())
            Spacer(modifier = Modifier.height(12.dp))
            ActionRow(
                actions = actions,
                selectedIndex = selectedIndex,
                onActivate = { activate(it) }
            )
            if (showSettings) {
                Spacer(modifier = Modifier.height(12.dp))
                SettingsPanel(
                    host = host, port = port, username = username,
                    password = password, privateKey = privateKey, passphrase = passphrase,
                    onHostChange = { host = it }, onPortChange = { port = it },
                    onUsernameChange = { username = it }, onPasswordChange = { password = it },
                    onPrivateKeyChange = { privateKey = it }, onPassphraseChange = { passphrase = it }
                )
            }
        }
    }
}

@Composable
private fun StatusHeader(config: SshConfig) {
    Surface(
        color = Color.Black,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().border(2.dp, Color(0xFF00FF00), RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("ROKID TERMINAL", color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (config.host.isBlank()) "No SSH host configured" else "${config.username}@${config.host}:${config.port}",
                color = Color(0xFFB8FFB8),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Text(
                "Arrows select  Enter activates  Esc closes settings",
                color = Color(0xFF88CC88), fontFamily = FontFamily.Monospace, fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ActionRow(
    actions: List<SurfaceAction>,
    selectedIndex: Int,
    onActivate: (SurfaceAction) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        actions.forEachIndexed { index, action ->
            val selected = selectedIndex == index
            Surface(
                color = Color.Black,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) Color(0xFF00FF00) else Color(0xFF226622),
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = action.label,
                        color = Color(0xFF00FF00),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    host: String, port: String, username: String, password: String,
    privateKey: String, passphrase: String,
    onHostChange: (String) -> Unit, onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit, onPasswordChange: (String) -> Unit,
    onPrivateKeyChange: (String) -> Unit, onPassphraseChange: (String) -> Unit
) {
    Surface(
        color = Color.Black,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().border(2.dp, Color(0xFF00FF00), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SETTINGS", color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            SettingsField(host, onHostChange, "Host")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsField(port, onPortChange, "Port", modifier = Modifier.weight(0.4f))
                SettingsField(username, onUsernameChange, "User", modifier = Modifier.weight(0.6f))
            }
            SettingsField(password, onPasswordChange, "Password")
            SettingsField(passphrase, onPassphraseChange, "Key Passphrase")
            SettingsField(privateKey, onPrivateKeyChange, "Private Key", singleLine = false)
            Text(
                "Select Settings again or press Esc to save and close.",
                color = Color(0xFF88CC88), fontFamily = FontFamily.Monospace, fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun SettingsField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
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
            color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace
        )
    )
}
