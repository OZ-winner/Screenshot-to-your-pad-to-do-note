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
    private var savedUrl: String? = null
    private var savedDeviceName: String = "小米平板"
    @Volatile private var generation = 0
    @Volatile private var reconnectScheduled = false
    @Volatile private var pendingOutbound: String? = null
    private lateinit var appContext: Context
    private val io = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun connectSaved(force: Boolean = false) {
        val saved = BridgePrefs(appContext).load() ?: return
        token = saved.token
        savedUrl = saved.url
        savedDeviceName = saved.deviceName
        if (!force && socket != null && _state.value == ConnectionState.Connected) {
            return
        }
        if (!force && _state.value == ConnectionState.Connecting) {
            return
        }
        connect(saved.url, null, saved.deviceName)
    }

    fun pair(url: String, code: String, deviceName: String) {
        token = null
        savedUrl = url
        savedDeviceName = deviceName
        connect(url, code, deviceName)
    }

    private fun connect(url: String, code: String?, deviceName: String) {
        generation += 1
        val activeGeneration = generation
        socket?.cancel()
        socket = null
        _state.value = ConnectionState.Connecting
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (activeGeneration != generation) return
                reconnectScheduled = false
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
                pendingOutbound?.let {
                    if (webSocket.send(it)) {
                        pendingOutbound = null
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (activeGeneration != generation) return
                handleMessage(url, deviceName, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeGeneration != generation) return
                socket = null
                _state.value = ConnectionState.Failed
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeGeneration != generation) return
                socket = null
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(url: String, deviceName: String, text: String) {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "paired" -> {
                val nextToken = json.getString("token")
                token = nextToken
                savedUrl = url
                savedDeviceName = deviceName
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
        sendOrQueue(message)
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
        sendOrQueue(message)
    }

    fun sendPing() {
        val message = JSONObject()
            .put("type", "ping")
            .put("token", token)
        sendOrQueue(message)
    }

    private fun sendOrQueue(message: JSONObject) {
        val text = message.toString()
        if (socket?.send(text) != true) {
            pendingOutbound = text
            connectSaved()
        }
    }

    private fun scheduleReconnect() {
        if (token == null || reconnectScheduled) return
        val url = savedUrl ?: BridgePrefs(appContext).load()?.also {
            savedUrl = it.url
            savedDeviceName = it.deviceName
        }?.url ?: return
        reconnectScheduled = true
        scheduler.schedule({
            reconnectScheduled = false
            if (_state.value == ConnectionState.Connected || _state.value == ConnectionState.Connecting) {
                return@schedule
            }
            connect(url, null, savedDeviceName)
        }, 1500, TimeUnit.MILLISECONDS)
    }

    fun close() {
        generation += 1
        socket?.close(1000, "reconnect")
        socket = null
        _state.value = ConnectionState.Disconnected
    }
}
