package com.clawsses.glasses

import android.os.Bundle
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawsses.glasses.terminal.ConnectionState
import com.clawsses.glasses.terminal.InteractiveTerminalController
import com.clawsses.glasses.ui.theme.GlassesHudTheme
import org.connectbot.terminal.Terminal

class TerminalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlassesHudTheme {
                TerminalScreen(
                    controller = InteractiveTerminalController(GlassesApp.instance.settingsStore),
                    goBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun TerminalScreen(
    controller: InteractiveTerminalController,
    goBack: () -> Unit
) {
    val connectionState by controller.connectionState.collectAsState()
    val statusText by controller.statusText.collectAsState()
    val settingsStore = GlassesApp.instance.settingsStore
    var fontSize by rememberSaveable { mutableFloatStateOf(settingsStore.loadFontSize()) }

    LaunchedEffect(fontSize) {
        settingsStore.saveFontSize(fontSize)
    }

    LaunchedEffect(Unit) {
        controller.connect()
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.close()
        }
    }

    BackHandler(onBack = goBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(4.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Equals, Key.NumPadAdd -> { fontSize = (fontSize + 1f).coerceAtMost(24f); true }
                    Key.Minus, Key.NumPadSubtract -> { fontSize = (fontSize - 1f).coerceAtLeast(6f); true }
                    else -> false
                }
            }
    ) {
        Text(
            text = "font:${fontSize.toInt()}  $statusText",
            color = when (connectionState) {
                is ConnectionState.Error -> Color(0xFFFF6666)
                else -> Color(0xFF00FF00)
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            color = Color.Black,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            key(fontSize) {
                Terminal(
                    terminalEmulator = controller.emulator,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    typeface = Typeface.MONOSPACE,
                    initialFontSize = fontSize.sp,
                    minFontSize = 6.sp,
                    maxFontSize = 24.sp,
                    backgroundColor = Color.Black,
                    foregroundColor = Color.White,
                    keyboardEnabled = connectionState is ConnectionState.Connected,
                    showSoftKeyboard = false,
                    onTerminalTap = {},
                    forcedSize = null
                )
            }
        }
        if (connectionState is ConnectionState.Error) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = (connectionState as ConnectionState.Error).message,
                color = Color(0xFFFF8888),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
