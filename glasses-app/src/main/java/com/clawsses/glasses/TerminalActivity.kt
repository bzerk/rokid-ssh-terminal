package com.clawsses.glasses

import android.os.Bundle
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    LaunchedEffect(Unit) {
        controller.connect()
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.close()
        }
    }

    BackHandler(onBack = goBack)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = Color.Black,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, Color(0xFF00FF00))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "TERMINAL",
                        color = Color(0xFF00FF00),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp
                    )
                    Text(
                        text = statusText,
                        color = when (connectionState) {
                            is ConnectionState.Error -> Color(0xFFFF6666)
                            else -> Color(0xFF88CC88)
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 2
                    )
                    Text(
                        text = "Back leaves terminal  Keyboard sends raw input  tmux auto-attach on connect",
                        color = Color(0xFF66FFCC),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 2
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = Color.Black,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(2.dp, Color(0xFF00FF00))
            ) {
                Terminal(
                    terminalEmulator = controller.emulator,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    typeface = Typeface.MONOSPACE,
                    initialFontSize = 10.sp,
                    minFontSize = 8.sp,
                    maxFontSize = 18.sp,
                    backgroundColor = Color.Black,
                    foregroundColor = Color(0xFFB8FFB8),
                    keyboardEnabled = connectionState is ConnectionState.Connected,
                    showSoftKeyboard = false,
                    onTerminalTap = {},
                    forcedSize = null
                )
            }
            if (connectionState is ConnectionState.Error) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color.Black,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, Color(0xFFFF6666))
                ) {
                    Text(
                        text = (connectionState as ConnectionState.Error).message,
                        color = Color(0xFFFF8888),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, Color(0xFF226622))
                    .padding(8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Bluetooth keyboard recommended. Arrow keys go to the shell here, not surface navigation.",
                    color = Color(0xFF88CC88),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}
