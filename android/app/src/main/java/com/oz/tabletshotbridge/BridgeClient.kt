package com.oz.tabletshotbridge

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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

enum class CaptureFeedbackPhase {
    Processing,
    Success,
    Failed,
}

data class CaptureFeedback(
    val phase: CaptureFeedbackPhase,
    val message: String,
)

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
    @Volatile private var captureFeedbackGeneration = 0
    @Volatile private var captureInProgress = false
    private val screenshotFrameBinder = ScreenshotFrameBinder()
    private lateinit var appContext: Context
    private val io = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state
    private val _captureFeedback = MutableSharedFlow<CaptureFeedback>(extraBufferCapacity = 8)
    val captureFeedback: SharedFlow<CaptureFeedback> = _captureFeedback

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

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (activeGeneration != generation) return
                handleScreenshotBinary(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeGeneration != generation) return
                socket = null
                clearPendingScreenshotMetadata()
                _state.value = ConnectionState.Failed
                failActiveCapture("连接中断，截图失败")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeGeneration != generation) return
                socket = null
                clearPendingScreenshotMetadata()
                _state.value = ConnectionState.Disconnected
                failActiveCapture("连接已断开，截图失败")
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

            "screenshot_meta" -> handleScreenshotMetadata(json)

            "error" -> {
                _state.value = ConnectionState.Failed
                failActiveCapture(json.optString("message", "截图失败"))
            }
            "pong" -> _state.value = ConnectionState.Connected
            "status" -> {
                _state.value = when (json.optString("status")) {
                    "saving" -> ConnectionState.Saving
                    "saved" -> ConnectionState.Saved
                    "failed" -> ConnectionState.Failed
                    else -> ConnectionState.Connected
                }
            }
        }
    }

    private fun handleScreenshotMetadata(json: JSONObject) {
        val metadata = runCatching {
            PendingScreenshotMetadata(
                id = json.getString("id"),
                filename = json.getString("filename"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                mimeType = json.getString("mime_type"),
                byteLength = json.getLong("byte_length"),
                sha256 = json.getString("sha256").lowercase(),
            )
        }.getOrElse {
            failActiveCapture("截图元数据无效")
            _state.value = ConnectionState.Failed
            return
        }

        val validationError = validateScreenshotMetadata(metadata)
        if (validationError != null) {
            sendScreenshotResult(metadata.id, false, validationError)
            publishCaptureFeedback(CaptureFeedbackPhase.Failed, validationError)
            _state.value = ConnectionState.Failed
            return
        }

        val replaced = screenshotFrameBinder.expect(metadata)
        replaced?.let {
            sendScreenshotResult(it.id, false, "截图二进制数据缺失")
        }
        _state.value = ConnectionState.Saving
        publishCaptureFeedback(CaptureFeedbackPhase.Processing, "正在保存截图...")
        scheduler.schedule({
            screenshotFrameBinder.expire(metadata.id)?.let {
                completeScreenshot(it.id, false, "截图二进制数据接收超时")
            }
        }, 15, TimeUnit.SECONDS)
    }

    private fun handleScreenshotBinary(payload: ByteString) {
        val metadata = screenshotFrameBinder.take() ?: run {
            failActiveCapture("收到未匹配的截图数据")
            _state.value = ConnectionState.Failed
            return
        }
        val bytes = payload.toByteArray()
        val validationError = validateScreenshotPayload(metadata, bytes)
        if (validationError != null) {
            completeScreenshot(metadata.id, false, validationError)
            return
        }

        io.execute {
            val saved = runCatching {
                GallerySaver(appContext).saveImage(
                    filename = metadata.filename,
                    mimeType = metadata.mimeType,
                    bytes = bytes,
                )
            }.getOrDefault(false)
            completeScreenshot(
                id = metadata.id,
                success = saved,
                failureMessage = "平板保存截图失败",
            )
        }
    }

    private fun completeScreenshot(id: String, success: Boolean, failureMessage: String) {
        _state.value = if (success) ConnectionState.Saved else ConnectionState.Failed
        if (success) {
            publishCaptureFeedback(CaptureFeedbackPhase.Success, "截图已保存到平板")
            sendScreenshotResult(id, true, null)
        } else {
            publishCaptureFeedback(CaptureFeedbackPhase.Failed, failureMessage)
            sendScreenshotResult(id, false, failureMessage)
        }
    }

    private fun clearPendingScreenshotMetadata() {
        screenshotFrameBinder.clear()
    }

    fun command(command: String) {
        if (command == "screenshot") {
            publishCaptureFeedback(CaptureFeedbackPhase.Processing, "正在截图...", timeout = true)
        }
        val activeToken = token ?: run {
            failActiveCapture("电脑未连接，截图失败")
            return
        }
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
        if (phase == "confirm") {
            publishCaptureFeedback(CaptureFeedbackPhase.Processing, "正在保存截图...", timeout = true)
        }
        val activeToken = token ?: run {
            failActiveCapture("电脑未连接，截图失败")
            return
        }
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

    private fun sendScreenshotResult(id: String, success: Boolean, message: String?) {
        val activeToken = token ?: return
        val payload = JSONObject()
            .put("type", "screenshot_result")
            .put("token", activeToken)
            .put("id", id)
            .put("success", success)
            .put("message", message ?: JSONObject.NULL)
        sendOrQueue(payload)
    }

    private fun publishCaptureFeedback(
        phase: CaptureFeedbackPhase,
        message: String,
        timeout: Boolean = false,
    ) {
        val activeGeneration = synchronized(this) {
            captureFeedbackGeneration += 1
            captureInProgress = phase == CaptureFeedbackPhase.Processing
            captureFeedbackGeneration
        }
        _captureFeedback.tryEmit(CaptureFeedback(phase, message))

        if (timeout) {
            scheduler.schedule({
                val shouldFail = synchronized(this) {
                    if (captureFeedbackGeneration == activeGeneration && captureInProgress) {
                        captureFeedbackGeneration += 1
                        captureInProgress = false
                        true
                    } else {
                        false
                    }
                }
                if (shouldFail) {
                    _captureFeedback.tryEmit(
                        CaptureFeedback(CaptureFeedbackPhase.Failed, "截图超时，请重试"),
                    )
                    _state.value = ConnectionState.Failed
                }
            }, 15, TimeUnit.SECONDS)
        }
    }

    private fun failActiveCapture(message: String) {
        val shouldFail = synchronized(this) {
            if (captureInProgress) {
                captureFeedbackGeneration += 1
                captureInProgress = false
                true
            } else {
                false
            }
        }
        if (shouldFail) {
            _captureFeedback.tryEmit(CaptureFeedback(CaptureFeedbackPhase.Failed, message))
        }
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
        clearPendingScreenshotMetadata()
        socket?.close(1000, "reconnect")
        socket = null
        _state.value = ConnectionState.Disconnected
    }
}
