package com.oz.tabletshotbridge

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding

class FloatingRemoteService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
    private var selectionOverlay: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        BridgeClient.init(applicationContext)
        BridgeClient.connectSaved()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        panel?.let { windowManager.removeView(it) }
        selectionOverlay?.let { windowManager.removeView(it) }
        panel = null
        selectionOverlay = null
        super.onDestroy()
    }

    private fun showPanel() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8)
            background = roundedBackground()
            addView(grip())
            addView(remoteButton("截") { BridgeClient.command("screenshot") })
            addView(remoteButton("框") { showSelectionOverlay() })
            addView(remoteButton("退") { BridgeClient.command("seek_back_5") })
            addView(remoteButton("停") { BridgeClient.command("play_pause") })
            addView(remoteButton("进") { BridgeClient.command("seek_forward_5") })
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 160
        }

        attachDrag(view, layoutParams)
        panel = view
        params = layoutParams
        windowManager.addView(view, layoutParams)
    }

    private fun grip(): TextView {
        return TextView(this).apply {
            text = "≡"
            textSize = 20f
            setTextColor(0xffffffff.toInt())
            setPadding(12)
        }
    }

    private fun showSelectionOverlay() {
        if (selectionOverlay != null) {
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x33000000)
        }
        val metrics = resources.displayMetrics
        var rectX = (metrics.widthPixels * 0.16f).toInt()
        var rectY = (metrics.heightPixels * 0.18f).toInt()
        var rectW = (metrics.widthPixels * 0.56f).toInt()
        var rectH = (metrics.heightPixels * 0.36f).toInt()

        val rectView = FrameLayout(this).apply {
            background = selectionBorder()
            addView(
                TextView(this@FloatingRemoteService).apply {
                    text = "拖动选区，右下角缩放"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setPadding(12)
                    background = roundedBackground(0xaa111827.toInt(), 8f)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START,
                ),
            )
            addView(
                TextView(this@FloatingRemoteService).apply {
                    text = "↘"
                    textSize = 24f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = roundedBackground(0xdd16a34a.toInt(), 12f)
                },
                FrameLayout.LayoutParams(60, 60, Gravity.BOTTOM or Gravity.END),
            )
        }
        val rectLayout = FrameLayout.LayoutParams(rectW, rectH).apply {
            leftMargin = rectX
            topMargin = rectY
        }
        root.addView(rectView, rectLayout)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(10)
            background = roundedBackground(0xdd111827.toInt(), 14f)
            addView(remoteButton("发送") {
                sendSelection("confirm", root, rectX, rectY, rectW, rectH)
                closeSelectionOverlay()
            })
            addView(remoteButton("取消") {
                sendSelection("cancel", root, rectX, rectY, rectW, rectH)
                closeSelectionOverlay()
            })
        }
        root.addView(
            actions,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = 54
            },
        )

        attachSelectionTouch(
            rectView = rectView,
            root = root,
            getRect = { Quad(rectX, rectY, rectW, rectH) },
            setRect = { next ->
                rectX = next.x
                rectY = next.y
                rectW = next.width
                rectH = next.height
                rectLayout.leftMargin = rectX
                rectLayout.topMargin = rectY
                rectLayout.width = rectW
                rectLayout.height = rectH
                rectView.layoutParams = rectLayout
            },
        )

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        selectionOverlay = root
        windowManager.addView(root, layoutParams)
        root.post {
            sendSelection("begin", root, rectX, rectY, rectW, rectH)
        }
    }

    private fun closeSelectionOverlay() {
        selectionOverlay?.let { windowManager.removeView(it) }
        selectionOverlay = null
    }

    private fun attachSelectionTouch(
        rectView: View,
        root: View,
        getRect: () -> Quad,
        setRect: (Quad) -> Unit,
    ) {
        var start = Quad(0, 0, 0, 0)
        var downX = 0f
        var downY = 0f
        var resizing = false

        rectView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    start = getRect()
                    downX = event.rawX
                    downY = event.rawY
                    resizing = event.x > start.width - 86 && event.y > start.height - 86
                    sendSelection("begin", root, start.x, start.y, start.width, start.height)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    val next = if (resizing) {
                        clampRect(
                            root,
                            start.x,
                            start.y,
                            start.width + dx,
                            start.height + dy,
                        )
                    } else {
                        clampRect(
                            root,
                            start.x + dx,
                            start.y + dy,
                            start.width,
                            start.height,
                        )
                    }
                    setRect(next)
                    sendSelection("update", root, next.x, next.y, next.width, next.height)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val next = getRect()
                    sendSelection("update", root, next.x, next.y, next.width, next.height)
                    true
                }

                else -> false
            }
        }
    }

    private fun clampRect(root: View, x: Int, y: Int, width: Int, height: Int): Quad {
        val rootWidth = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val nextWidth = width.coerceIn(140, rootWidth)
        val nextHeight = height.coerceIn(110, rootHeight)
        val nextX = x.coerceIn(0, (rootWidth - nextWidth).coerceAtLeast(0))
        val nextY = y.coerceIn(0, (rootHeight - nextHeight).coerceAtLeast(0))
        return Quad(nextX, nextY, nextWidth, nextHeight)
    }

    private fun sendSelection(phase: String, root: View, x: Int, y: Int, width: Int, height: Int) {
        val rootWidth = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        BridgeClient.remoteSelection(
            phase = phase,
            xRatio = x.toFloat() / rootWidth.toFloat(),
            yRatio = y.toFloat() / rootHeight.toFloat(),
            widthRatio = width.toFloat() / rootWidth.toFloat(),
            heightRatio = height.toFloat() / rootHeight.toFloat(),
        )
    }

    private fun remoteButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            minWidth = 72
            minHeight = 58
            setOnClickListener { onClick() }
        }
    }

    private fun attachDrag(view: LinearLayout, layoutParams: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    downX = event.rawX
                    downY = event.rawY
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startX + (event.rawX - downX).toInt()
                    layoutParams.y = startY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(view, layoutParams)
                    true
                }

                else -> false
            }
        }
    }

    private fun roundedBackground(
        color: Int = 0xcc111827.toInt(),
        radius: Float = 18f,
    ) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun selectionBorder() = GradientDrawable().apply {
        setColor(0x2216a34a)
        setStroke(5, 0xff22c55e.toInt())
    }

    private data class Quad(val x: Int, val y: Int, val width: Int, val height: Int)
}
