package com.oz.tabletshotbridge

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding

class FloatingRemoteService : Service() {
    companion object {
        const val ACTION_START_SELECTION = "com.oz.tabletshotbridge.START_SELECTION"
    }

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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
        if (panel == null) {
            showPanel()
        }
        if (intent?.action == ACTION_START_SELECTION) {
            BridgeClient.connectSaved()
            panel?.postDelayed({ showSelectionOverlay() }, 120)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        panel?.let { windowManager.removeView(it) }
        selectionOverlay?.let { windowManager.removeView(it) }
        panel = null
        selectionOverlay = null
        super.onDestroy()
    }

    private fun showPanel() {
        val gripView = grip()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8)
            background = roundedBackground()
            addView(gripView)
            addView(iconButton(R.drawable.ic_screenshot_area, "截图") { showSelectionOverlay() })
            addView(iconButton(R.drawable.ic_rewind, "后退 5 秒") { BridgeClient.command("seek_back_5") })
            addView(iconButton(R.drawable.ic_pause, "暂停播放") { BridgeClient.command("play_pause") })
            addView(iconButton(R.drawable.ic_fast_forward, "快进 5 秒") { BridgeClient.command("seek_forward_5") })
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

        attachDrag(gripView, view, layoutParams)
        panel = view
        params = layoutParams
        windowManager.addView(view, layoutParams)
    }

    private fun grip(): TextView {
        return TextView(this).apply {
            text = "≡"
            textSize = 20f
            setTextColor(0xffffffff.toInt())
            minWidth = 54
            minHeight = 58
            gravity = Gravity.CENTER
            setPadding(12)
        }
    }

    private fun showSelectionOverlay() {
        if (selectionOverlay != null) {
            return
        }
        BridgeClient.connectSaved()

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x33000000)
        }
        var rectX = 0
        var rectY = 0
        var rectW = 0
        var rectH = 0
        var downX = 0f
        var downY = 0f
        var hasSelection = false

        val touchLayer = FrameLayout(this)
        root.addView(
            touchLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val rectView = FrameLayout(this).apply {
            background = selectionBorder()
            visibility = View.GONE
        }
        val rectLayout = FrameLayout.LayoutParams(1, 1)
        root.addView(rectView, rectLayout)

        lateinit var actions: LinearLayout
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(10)
            background = roundedBackground(0xdd111827.toInt(), 14f)
            visibility = View.GONE
            addView(actionButton(R.drawable.ic_close, "取消") {
                sendSelection("cancel", root, rectX, rectY, rectW, rectH)
                closeSelectionOverlay()
            })
            addView(actionButton(R.drawable.ic_redraw, "重新划区") {
                hasSelection = false
                rectView.visibility = View.GONE
                actions.visibility = View.GONE
                sendSelection("cancel", root, rectX, rectY, rectW, rectH)
            })
            addView(actionButton(R.drawable.ic_check, "保存") {
                if (hasSelection && rectW > 8 && rectH > 8) {
                    sendSelection("confirm", root, rectX, rectY, rectW, rectH)
                    closeSelectionOverlay()
                }
            })
        }
        val actionsLayout = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply {
            bottomMargin = 54
        }
        root.addView(actions, actionsLayout)

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
            sendSelection("begin", root, 0, 0, 0, 0)
        }

        touchLayer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    hasSelection = false
                    actions.visibility = View.GONE
                    rectView.visibility = View.VISIBLE
                    rectX = downX.toInt()
                    rectY = downY.toInt()
                    rectW = 1
                    rectH = 1
                    applyRect(rectView, rectLayout, rectX, rectY, rectW, rectH)
                    sendSelection("begin", root, rectX, rectY, rectW, rectH)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val next = rectFromDrag(root, downX, downY, event.rawX, event.rawY)
                    rectX = next.x
                    rectY = next.y
                    rectW = next.width
                    rectH = next.height
                    applyRect(rectView, rectLayout, rectX, rectY, rectW, rectH)
                    sendSelection("update", root, rectX, rectY, rectW, rectH)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val next = rectFromDrag(root, downX, downY, event.rawX, event.rawY)
                    rectX = next.x
                    rectY = next.y
                    rectW = next.width
                    rectH = next.height
                    hasSelection = rectW > 8 && rectH > 8
                    applyRect(rectView, rectLayout, rectX, rectY, rectW, rectH)
                    sendSelection("update", root, rectX, rectY, rectW, rectH)
                    actions.visibility = if (hasSelection) View.VISIBLE else View.GONE
                    true
                }

                else -> false
            }
        }
    }

    private fun closeSelectionOverlay() {
        selectionOverlay?.let { windowManager.removeView(it) }
        selectionOverlay = null
    }

    private fun applyRect(
        rectView: View,
        layoutParams: FrameLayout.LayoutParams,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        layoutParams.leftMargin = x
        layoutParams.topMargin = y
        layoutParams.width = width.coerceAtLeast(1)
        layoutParams.height = height.coerceAtLeast(1)
        rectView.layoutParams = layoutParams
    }

    private fun rectFromDrag(root: View, startX: Float, startY: Float, endX: Float, endY: Float): Quad {
        val rootWidth = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val left = kotlin.math.min(startX, endX).toInt().coerceIn(0, rootWidth - 1)
        val top = kotlin.math.min(startY, endY).toInt().coerceIn(0, rootHeight - 1)
        val right = kotlin.math.max(startX, endX).toInt().coerceIn(left + 1, rootWidth)
        val bottom = kotlin.math.max(startY, endY).toInt().coerceIn(top + 1, rootHeight)
        return Quad(left, top, right - left, bottom - top)
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

    private fun iconButton(iconRes: Int, description: String, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            contentDescription = description
            setImageResource(iconRes)
            setColorFilter(0xffffffff.toInt())
            background = roundedBackground(0x22ffffff, 10f)
            minimumWidth = 72
            minimumHeight = 58
            setPadding(16)
            setOnClickListener { onClick() }
        }
    }

    private fun actionButton(iconRes: Int, description: String, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            contentDescription = description
            setImageResource(iconRes)
            setColorFilter(0xffffffff.toInt())
            background = roundedBackground(0x3316a34a, 12f)
            layoutParams = LinearLayout.LayoutParams(72, 62).apply {
                marginStart = 6
                marginEnd = 6
            }
            setPadding(16)
            setOnClickListener { onClick() }
        }
    }

    private fun attachDrag(
        dragHandle: View,
        target: View,
        layoutParams: WindowManager.LayoutParams,
    ) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    downX = event.rawX
                    downY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val displayWidth = resources.displayMetrics.widthPixels
                    val displayHeight = resources.displayMetrics.heightPixels
                    val maxX = (displayWidth - target.width).coerceAtLeast(0)
                    val maxY = (displayHeight - target.height).coerceAtLeast(0)
                    layoutParams.x = (startX + (event.rawX - downX).toInt()).coerceIn(0, maxX)
                    layoutParams.y = (startY + (event.rawY - downY).toInt()).coerceIn(0, maxY)
                    windowManager.updateViewLayout(target, layoutParams)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> true
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
