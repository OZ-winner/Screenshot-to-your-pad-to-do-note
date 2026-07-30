package com.oz.tabletshotbridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Saving,
    Saved,
    Failed,
}

object BridgeClient {
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null
    private var token: String? = null
    private lateinit var appContext: Context
    private val io = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun connectSaved() {
        val saved = BridgePrefs(appContext).load() ?: return
        token = saved.token
        connect(saved.url, null, saved.deviceName)
    }

    fun pair(url: String, code: String, deviceName: String) {
        token = null
        connect(url, code, deviceName)
    }

    private fun connect(url: String, code: String?, deviceName: String) {
        close()
        _state.value = ConnectionState.Connecting
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ConnectionState.Connected
                if (code != null) {
                    val message = JSONObject()
                        .put("type", "pair")
                        .put("code", code)
                        .put("device_name", deviceName)
                    webSocket.send(message.toString())
                } else {
                    val message = JSONObject()
                        .put("type", "ping")
                        .put("token", token)
                    webSocket.send(message.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(url, deviceName, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnectionState.Failed
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnectionState.Disconnected
            }
        })
    }

    private fun handleMessage(url: String, deviceName: String, text: String) {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "paired" -> {
                val nextToken = json.getString("token")
                token = nextToken
                BridgePrefs(appContext).save(url, nextToken, deviceName)
                _state.value = ConnectionState.Connected
            }

            "screenshot" -> {
                _state.value = ConnectionState.Saving
                val filename = json.getString("filename")
                val pngBase64 = json.getString("png_base64")
                val sha256 = json.getString("sha256")
                io.execute {
                    val ok = GallerySaver(appContext).savePng(
                        filename = filename,
                        pngBase64 = pngBase64,
                        expectedSha256 = sha256,
                    )
                    _state.value = if (ok) ConnectionState.Saved else ConnectionState.Failed
                }
            }

            "error" -> _state.value = ConnectionState.Failed
            "pong" -> _state.value = ConnectionState.Connected
            "status" -> _state.value = ConnectionState.Connected
        }
    }

    fun command(command: String) {
        val activeToken = token ?: return
        val message = JSONObject()
            .put("type", "command")
            .put("command", command)
            .put("token", activeToken)
        socket?.send(message.toString())
    }

    fun remoteSelection(
        phase: String,
        xRatio: Float,
        yRatio: Float,
        widthRatio: Float,
        heightRatio: Float,
    ) {
        val activeToken = token ?: return
        val message = JSONObject()
            .put("type", "remote_selection")
            .put("token", activeToken)
            .put("phase", phase)
            .put("x_ratio", xRatio.toDouble())
            .put("y_ratio", yRatio.toDouble())
            .put("width_ratio", widthRatio.toDouble())
            .put("height_ratio", heightRatio.toDouble())
        socket?.send(message.toString())
    }

    fun sendPing() {
        val message = JSONObject()
            .put("type", "ping")
            .put("token", token)
        socket?.send(message.toString())
    }

    fun close() {
        socket?.close(1000, "reconnect")
        socket = null
    }
}
