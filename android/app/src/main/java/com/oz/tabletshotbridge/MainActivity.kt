package com.oz.tabletshotbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeClient.init(applicationContext)
        BridgeClient.connectSaved()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BridgeScreen(
                        hasOverlayPermission = Settings.canDrawOverlays(this),
                        requestOverlayPermission = { openOverlayPermission() },
                        startFloatingWindow = {
                            if (!Settings.canDrawOverlays(this)) {
                                openOverlayPermission()
                            } else {
                                BridgeClient.connectSaved()
                                ContextCompat.startForegroundService(
                                    this,
                                    Intent(this, FloatingRemoteService::class.java),
                                )
                            }
                        },
                        startSelectionScreenshot = {
                            if (!Settings.canDrawOverlays(this)) {
                                openOverlayPermission()
                            } else {
                                BridgeClient.connectSaved()
                                ContextCompat.startForegroundService(
                                    this,
                                    Intent(this, FloatingRemoteService::class.java)
                                        .setAction(FloatingRemoteService.ACTION_START_SELECTION),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private fun openOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }
}

@Composable
private fun BridgeScreen(
    hasOverlayPermission: Boolean,
    requestOverlayPermission: () -> Unit,
    startFloatingWindow: () -> Unit,
    startSelectionScreenshot: () -> Unit,
) {
    val state by BridgeClient.state.collectAsState()
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("小米平板") }
    var pastedPayload by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { payload ->
            pastedPayload = payload
            parsePairingPayload(payload)?.let {
                url = it.first
                code = it.second
            }
        }
    }

    LaunchedEffect(Unit) {
        BridgePrefs(context).load()?.let {
            url = it.url
            deviceName = it.deviceName
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("截图直传", style = MaterialTheme.typography.headlineMedium)
        Text("状态：${state.name}", style = MaterialTheme.typography.bodyLarge)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = pastedPayload,
                    onValueChange = {
                        pastedPayload = it
                        parsePairingPayload(it)?.let { payload ->
                            url = payload.first
                            code = payload.second
                        }
                    },
                    label = { Text("二维码内容或 ws 地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
                Button(
                    onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Text("扫码")
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("电脑 WebSocket 地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase() },
                        label = { Text("配对码") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("设备名") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Button(
                    onClick = { BridgeClient.pair(url.trim(), code.trim(), deviceName.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = url.startsWith("ws://") && code.isNotBlank(),
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Text("配对连接")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = requestOverlayPermission, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PictureInPicture, contentDescription = null)
                Text(if (hasOverlayPermission) "已授权" else "悬浮窗授权")
            }
            Button(
                onClick = startFloatingWindow,
                modifier = Modifier.weight(1f),
                enabled = hasOverlayPermission,
            ) {
                Icon(Icons.Default.PictureInPicture, contentDescription = null)
                Text("打开悬浮窗")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            RemoteButton("截图", modifier = Modifier.weight(1f), icon = { Icon(Icons.Default.CameraAlt, null) }) {
                startSelectionScreenshot()
            }
            RemoteButton("后退", modifier = Modifier.weight(1f), icon = { Icon(Icons.Default.Replay5, null) }) {
                BridgeClient.command("seek_back_5")
            }
            RemoteButton("暂停", modifier = Modifier.weight(1f), icon = { Icon(Icons.Default.Pause, null) }) {
                BridgeClient.command("play_pause")
            }
            RemoteButton("快进", modifier = Modifier.weight(1f), icon = { Icon(Icons.Default.FastForward, null) }) {
                BridgeClient.command("seek_forward_5")
            }
        }
    }
}

@Composable
private fun RemoteButton(
    label: String,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier.height(58.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Text(label)
        }
    }
}

private fun parsePairingPayload(input: String): Pair<String, String>? {
    val value = input.trim()
    if (value.startsWith("ws://")) return value to ""
    return try {
        val json = JSONObject(value)
        json.optString("url").takeIf { it.startsWith("ws://") }?.let { url ->
            url to json.optString("code")
        }
    } catch (_: Throwable) {
        null
    }
}
