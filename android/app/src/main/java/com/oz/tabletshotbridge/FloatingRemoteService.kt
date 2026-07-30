package com.oz.tabletshotbridge

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding

class FloatingRemoteService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
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
        panel = null
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

    private fun roundedBackground() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 18f
        setColor(0xcc111827.toInt())
    }
}
