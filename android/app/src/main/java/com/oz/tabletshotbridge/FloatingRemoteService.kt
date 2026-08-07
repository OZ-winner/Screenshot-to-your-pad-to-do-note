package com.oz.tabletshotbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FloatingRemoteService : Service() {
    companion object {
        const val ACTION_START_SELECTION = "com.oz.tabletshotbridge.START_SELECTION"
        private const val NOTIFICATION_CHANNEL_ID = "floating_remote_connection"
        private const val NOTIFICATION_ID = 1001
        private const val FLOATING_BALL_SIZE = 108
        private const val FLOATING_BALL_EDGE_SLOP = 12
        private const val FLOATING_INITIAL_X = 60
        private const val FLOATING_INITIAL_Y = 160
    }

    private lateinit var windowManager: WindowManager
    private var panel: View? = null
    private var selectionOverlay: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var floatingState = FloatingState.ExpandedRight
    private var lastExpandDirection = ExpandDirection.Right
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screenshotNotice: View? = null
    private var connectionStatusView: TextView? = null
    private var connectionDotView: View? = null
    private var playPauseButton: ImageButton? = null
    private var connectionHealth = ConnectionHealth.Disconnected
    private var isPlaybackPaused = false

    override fun onCreate() {
        super.onCreate()
        startConnectionForegroundService()
        BridgeClient.init(applicationContext)
        BridgeClient.connectSaved()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceScope.launch {
            BridgeClient.captureFeedback.collect { feedback ->
                showCaptureFeedback(feedback)
            }
        }
        serviceScope.launch {
            BridgeClient.connectionHealth.collect { health ->
                connectionHealth = health
                updateConnectionIndicators()
            }
        }
        serviceScope.launch {
            BridgeClient.playPauseAcknowledged.collect {
                isPlaybackPaused = !isPlaybackPaused
                updatePlayPauseButton()
            }
        }
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
            showExpandedPanel()
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
        screenshotNotice?.let { windowManager.removeView(it) }
        screenshotNotice = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun showExpandedPanel(
        startX: Int = FLOATING_INITIAL_X,
        startY: Int = FLOATING_INITIAL_Y,
        expandDirection: ExpandDirection = ExpandDirection.Right,
        revealAfterPosition: Boolean = false,
    ) {
        val gripView = grip()
        lateinit var statusView: TextView
        lateinit var playbackButton: ImageButton
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8)
            background = roundedBackground()
            elevation = 12f
            val collapseIcon = if (expandDirection == ExpandDirection.Right) {
                R.drawable.ic_collapse
            } else {
                R.drawable.ic_collapse_right
            }
            val status = connectionStatus().also { statusView = it }
            val playback = iconButton(R.drawable.ic_pause, "暂停播放") {
                BridgeClient.command("play_pause")
            }.also { playbackButton = it }
            val buttons = listOf(
                iconButton(R.drawable.ic_screenshot_area, "选择截图区域") { showSelectionOverlay() },
                iconButton(R.drawable.ic_screenshot_fullscreen, "快速截全屏") {
                    BridgeClient.command("screenshot")
                },
                iconButton(R.drawable.ic_rewind, "后退 5 秒") { BridgeClient.command("seek_back_5") },
                playback,
                iconButton(R.drawable.ic_fast_forward, "快进 5 秒") { BridgeClient.command("seek_forward_5") },
                iconButton(collapseIcon, "收起为悬浮球") { collapseToBall() },
            )
            if (expandDirection == ExpandDirection.Right) {
                addView(gripView)
                addView(status)
                buttons.forEach { addView(it) }
            } else {
                // Keep rewind left of fast-forward in screen order on either side.
                addView(buttons.last())
                buttons.dropLast(1).forEach { addView(it) }
                addView(status)
                addView(gripView)
            }
        }
        val view = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            if (revealAfterPosition) {
                alpha = 0f
            }
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        attachDrag(gripView, view, layoutParams)
        removeFloatingControl()
        panel = view
        params = layoutParams
        connectionStatusView = statusView
        playPauseButton = playbackButton
        floatingState = if (expandDirection == ExpandDirection.Right) {
            FloatingState.ExpandedRight
        } else {
            FloatingState.ExpandedLeft
        }
        lastExpandDirection = expandDirection
        windowManager.addView(view, layoutParams)
        updateConnectionIndicators()
        updatePlayPauseButton()
        view.post {
            positionExpandedPanel(view, layoutParams, startX, startY, expandDirection)
            if (revealAfterPosition) {
                view.animate()
                    .alpha(1f)
                    .setDuration(90L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun showFloatingBall(startX: Int, startY: Int, animateIn: Boolean = false) {
        removeFloatingControl()
        val placement = floatingBallPlacement(startX, startY)
        val view = FrameLayout(this).apply {
            contentDescription = "展开遥控器"
            clipChildren = false
            clipToPadding = false
            addView(
                floatingBallView().apply {
                    translationX = placement.visualOffset
                },
                FrameLayout.LayoutParams(FLOATING_BALL_SIZE, FLOATING_BALL_SIZE),
            )
            setOnClickListener { expandFromBall() }
            if (animateIn) {
                alpha = 0f
                scaleX = 0.62f
                scaleY = 0.62f
            }
        }
        val layoutParams = WindowManager.LayoutParams(
            FLOATING_BALL_SIZE,
            FLOATING_BALL_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = placement.x
            y = placement.y
        }

        attachDrag(view, view, layoutParams, triggerClickOnTap = true, useFloatingBallDocking = true)
        windowManager.addView(view, layoutParams)
        panel = view
        params = layoutParams
        floatingState = FloatingState.Ball
        if (animateIn) {
            view.post {
                animateFloatingBallIn(view, 140L)
            }
        }
    }

    private fun floatingBallView(): FrameLayout {
        return FrameLayout(this).apply {
            addView(
                ImageButton(this@FloatingRemoteService).apply {
                    contentDescription = "展开遥控器"
                    setImageResource(R.drawable.ic_screenshot_area)
                    setColorFilter(0xffffffff.toInt())
                    background = roundedBackground(0xcc16a34a.toInt(), FLOATING_BALL_SIZE / 2f, oval = true)
                    elevation = 14f
                    setPadding(27)
                    isClickable = false
                    isFocusable = false
                },
                FrameLayout.LayoutParams(FLOATING_BALL_SIZE, FLOATING_BALL_SIZE),
            )
            addView(
                View(this@FloatingRemoteService).apply {
                    connectionDotView = this
                    elevation = 18f
                },
                FrameLayout.LayoutParams(22, 22, Gravity.TOP or Gravity.END).apply {
                    topMargin = 8
                    marginEnd = 8
                },
            )
            applyConnectionDot(connectionDotView)
        }
    }

    private fun animateFloatingBallIn(view: View, duration: Long) {
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
            .withEndAction {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            .start()
    }

    private fun collapseToBall() {
        if (floatingState == FloatingState.CollapsingToBall) {
            return
        }
        val currentView = panel ?: return
        val currentParams = params ?: return
        val root = currentView as? FrameLayout ?: return
        val content = root.getChildAt(0) as? LinearLayout ?: return
        val gripView = findGrip(content)
        val pivotInContentX = gripView.left + gripView.width / 2f
        val pivotInContentY = gripView.top + gripView.height / 2f
        val pivotX = content.left + pivotInContentX
        val pivotY = content.top + pivotInContentY
        val desiredBallX = currentParams.x + pivotX.toInt() - (FLOATING_BALL_SIZE / 2)
        val desiredBallY = currentParams.y + pivotY.toInt() - (FLOATING_BALL_SIZE / 2)
        val placement = floatingBallPlacement(desiredBallX, desiredBallY)
        val ballLeft = placement.x - currentParams.x
        val ballTop = placement.y - currentParams.y

        if (currentView.width <= 0 || currentView.height <= 0) {
            showFloatingBall(desiredBallX, desiredBallY, animateIn = true)
            return
        }

        floatingState = FloatingState.CollapsingToBall
        val ballView = floatingBallView().apply {
            alpha = 0f
            scaleX = 0.62f
            scaleY = 0.62f
            translationX = placement.visualOffset
        }
        root.addView(
            ballView,
            FrameLayout.LayoutParams(FLOATING_BALL_SIZE, FLOATING_BALL_SIZE).apply {
                leftMargin = ballLeft
                topMargin = ballTop
            },
        )

        content.pivotX = pivotInContentX
        content.pivotY = pivotInContentY
        content.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ballView.post {
            ballView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            animateFloatingBallIn(ballView, 180L)
        }

        content.animate()
            .alpha(0f)
            .scaleX(0.08f)
            .scaleY(0.08f)
            .setDuration(190L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .withEndAction {
                finishCollapsedBall(root, content, ballView, placement)
            }
            .start()
    }

    private fun finishCollapsedBall(
        root: FrameLayout,
        content: LinearLayout,
        ballView: View,
        placement: FloatingBallPlacement,
    ) {
        ballView.animate().cancel()
        ballView.alpha = 1f
        ballView.scaleX = 1f
        ballView.scaleY = 1f
        ballView.translationX = placement.visualOffset

        val (finalBall, finalParams) = addFinalFloatingBall(placement)
        panel = finalBall
        params = finalParams
        floatingState = FloatingState.Ball
        content.setLayerType(View.LAYER_TYPE_NONE, null)
        ballView.setLayerType(View.LAYER_TYPE_NONE, null)
        root.postOnAnimation {
            try {
                windowManager.removeView(root)
            } catch (_: IllegalArgumentException) {
                // The old expanded window can already be gone if the service is restarted.
            }
        }
    }

    private fun addFinalFloatingBall(placement: FloatingBallPlacement): Pair<View, WindowManager.LayoutParams> {
        val view = FrameLayout(this).apply {
            contentDescription = "展开遥控器"
            clipChildren = false
            clipToPadding = false
            addView(
                floatingBallView().apply {
                    translationX = placement.visualOffset
                },
                FrameLayout.LayoutParams(FLOATING_BALL_SIZE, FLOATING_BALL_SIZE),
            )
            setOnClickListener { expandFromBall() }
        }
        val layoutParams = WindowManager.LayoutParams(
            FLOATING_BALL_SIZE,
            FLOATING_BALL_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = placement.x
            y = placement.y
        }
        attachDrag(view, view, layoutParams, triggerClickOnTap = true, useFloatingBallDocking = true)
        windowManager.addView(view, layoutParams)
        return view to layoutParams
    }

    private fun expandFromBall() {
        if (floatingState != FloatingState.Ball) {
            return
        }
        val currentParams = params ?: return
        val startX = currentParams.x
        val startY = currentParams.y
        val direction = expandDirectionForBall(startX)
        lastExpandDirection = direction
        showExpandedPanel(startX, startY, direction, revealAfterPosition = true)
    }

    private fun removeFloatingControl() {
        panel?.let { windowManager.removeView(it) }
        panel = null
        params = null
        connectionStatusView = null
        connectionDotView = null
        playPauseButton = null
    }

    private fun connectionStatus(): TextView {
        return TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            minWidth = 82
            minHeight = 58
            setPadding(8, 0, 8, 0)
        }
    }

    private fun updateConnectionIndicators() {
        val (label, color) = when (connectionHealth) {
            ConnectionHealth.Connected -> "● 已连接" to 0xff4ade80.toInt()
            ConnectionHealth.Connecting -> "● 连接中" to 0xfffbbf24.toInt()
            ConnectionHealth.Disconnected -> "● 已断开" to 0xfff87171.toInt()
        }
        connectionStatusView?.apply {
            text = label
            setTextColor(color)
        }
        applyConnectionDot(connectionDotView, color)
    }

    private fun applyConnectionDot(dot: View?, color: Int = connectionColor()) {
        dot?.background = roundedBackground(color, 11f, oval = true).apply {
            setStroke(3, Color.WHITE)
        }
    }

    private fun connectionColor(): Int = when (connectionHealth) {
        ConnectionHealth.Connected -> 0xff22c55e.toInt()
        ConnectionHealth.Connecting -> 0xfff59e0b.toInt()
        ConnectionHealth.Disconnected -> 0xffef4444.toInt()
    }

    private fun updatePlayPauseButton() {
        playPauseButton?.apply {
            if (isPlaybackPaused) {
                setImageResource(R.drawable.ic_play)
                contentDescription = "继续播放"
            } else {
                setImageResource(R.drawable.ic_pause)
                contentDescription = "暂停播放"
            }
        }
    }

    private fun startConnectionForegroundService() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "悬浮遥控连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持截图直传与悬浮遥控连接"
                setShowBadge(false)
            },
        )
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screenshot_area)
            .setContentTitle("截图直传正在后台连接")
            .setContentText("悬浮遥控可随时使用")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun positionExpandedPanel(
        target: View,
        layoutParams: WindowManager.LayoutParams,
        anchorX: Int,
        anchorY: Int,
        expandDirection: ExpandDirection,
    ) {
        val bounds = screenBoundsFor(target)
        val content = (target as? FrameLayout)?.getChildAt(0) as? LinearLayout
        val gripView = content?.let { findGrip(it) }
        val gripCenterX = ((content?.left ?: 0) + (gripView?.left ?: 0)) +
            ((gripView?.width ?: FLOATING_BALL_SIZE) / 2)
        val gripCenterY = ((content?.top ?: 0) + (gripView?.top ?: 0)) +
            ((gripView?.height ?: FLOATING_BALL_SIZE) / 2)
        val anchorCenterX = anchorX + (FLOATING_BALL_SIZE / 2)
        val anchorCenterY = anchorY + (FLOATING_BALL_SIZE / 2)
        layoutParams.x = (anchorCenterX - gripCenterX).coerceIn(0, bounds.maxX)
        layoutParams.y = (anchorCenterY - gripCenterY).coerceIn(0, bounds.maxY)
        windowManager.updateViewLayout(target, layoutParams)
    }

    private fun expandDirectionForBall(ballX: Int): ExpandDirection {
        val centerLine = resources.displayMetrics.widthPixels / 2
        val ballRight = ballX + FLOATING_BALL_SIZE
        return when {
            ballRight < centerLine -> ExpandDirection.Right
            ballX > centerLine -> ExpandDirection.Left
            else -> lastExpandDirection
        }
    }

    private fun grip(): TextView {
        return TextView(this).apply {
            tag = "floating_grip"
            text = "≡"
            textSize = 20f
            setTextColor(0xffffffff.toInt())
            minWidth = 54
            minHeight = 58
            gravity = Gravity.CENTER
            setPadding(12)
        }
    }

    private fun findGrip(content: LinearLayout): View {
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child.tag == "floating_grip") {
                return child
            }
        }
        return content.getChildAt(0)
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
        var adjustmentStart = Quad(0, 0, 0, 0)
        var adjustmentDownX = 0f
        var adjustmentDownY = 0f
        val resizeHandleSize = 30

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

        val resizeHandles = mutableMapOf<SelectionAdjustMode, View>()
        SelectionAdjustMode.entries
            .filter { it != SelectionAdjustMode.Move }
            .forEach { mode ->
                val handle = View(this).apply {
                    background = roundedBackground(0xff22c55e.toInt(), resizeHandleSize / 2f, oval = true)
                    visibility = View.GONE
                    setOnTouchListener { _, event ->
                        if (!hasSelection) {
                            return@setOnTouchListener false
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                adjustmentStart = Quad(rectX, rectY, rectW, rectH)
                                adjustmentDownX = event.rawX
                                adjustmentDownY = event.rawY
                                true
                            }

                            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                                val next = adjustSelection(
                                    mode,
                                    adjustmentStart,
                                    event.rawX - adjustmentDownX,
                                    event.rawY - adjustmentDownY,
                                    root.width,
                                    root.height,
                                )
                                rectX = next.x
                                rectY = next.y
                                rectW = next.width
                                rectH = next.height
                                applyRect(rectView, rectLayout, rectX, rectY, rectW, rectH)
                                positionResizeHandles(resizeHandles, rectX, rectY, rectW, rectH, resizeHandleSize)
                                sendSelection("update", root, rectX, rectY, rectW, rectH)
                                true
                            }

                            else -> true
                        }
                    }
                }
                resizeHandles[mode] = handle
                root.addView(handle, FrameLayout.LayoutParams(resizeHandleSize, resizeHandleSize))
            }

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
                resizeHandles.values.forEach { it.visibility = View.GONE }
                actions.visibility = View.GONE
                sendSelection("update", root, 0, 0, 0, 0)
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

        rectView.setOnTouchListener { _, event ->
            if (!hasSelection) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    adjustmentStart = Quad(rectX, rectY, rectW, rectH)
                    adjustmentDownX = event.rawX
                    adjustmentDownY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val next = adjustSelection(
                        SelectionAdjustMode.Move,
                        adjustmentStart,
                        event.rawX - adjustmentDownX,
                        event.rawY - adjustmentDownY,
                        root.width,
                        root.height,
                    )
                    rectX = next.x
                    rectY = next.y
                    rectW = next.width
                    rectH = next.height
                    applyRect(rectView, rectLayout, rectX, rectY, rectW, rectH)
                    positionResizeHandles(resizeHandles, rectX, rectY, rectW, rectH, resizeHandleSize)
                    sendSelection("update", root, rectX, rectY, rectW, rectH)
                    true
                }

                else -> true
            }
        }

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
                    resizeHandles.values.forEach { it.visibility = View.GONE }
                    rectX = downX.toInt()
                    rectY = downY.toInt()
                    rectW = 1
                    rectH = 1
                    applyRect(rectView, rectLayout, rectX, rectY, rectW, rectH)
                    sendSelection("update", root, rectX, rectY, rectW, rectH)
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
                    resizeHandles.values.forEach { it.visibility = if (hasSelection) View.VISIBLE else View.GONE }
                    if (hasSelection) {
                        positionResizeHandles(resizeHandles, rectX, rectY, rectW, rectH, resizeHandleSize)
                    }
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

    private fun showCaptureFeedback(feedback: CaptureFeedback) {
        screenshotNotice?.let {
            try {
                windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
                // A previous short-lived notice can already be removed.
            }
        }

        val notice = TextView(this).apply {
            text = feedback.message
            textSize = 15f
            setTextColor(0xffffffff.toInt())
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            val color = when (feedback.phase) {
                CaptureFeedbackPhase.Processing -> 0xee111827.toInt()
                CaptureFeedbackPhase.Success -> 0xee15803d.toInt()
                CaptureFeedbackPhase.Failed -> 0xeeb42318.toInt()
            }
            background = roundedBackground(color, 12f)
            elevation = 18f
            alpha = 0f
        }
        val noticeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 72
        }
        windowManager.addView(notice, noticeParams)
        screenshotNotice = notice
        notice.animate().alpha(1f).setDuration(150L).start()
        if (feedback.phase != CaptureFeedbackPhase.Processing) {
            notice.postDelayed({
                notice.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction {
                        if (screenshotNotice === notice) {
                            try {
                                windowManager.removeView(notice)
                            } catch (_: IllegalArgumentException) {
                                // The service can be stopped while the notice is visible.
                            }
                            screenshotNotice = null
                        }
                    }
                    .start()
            }, 2_800L)
        }
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

    private fun positionResizeHandles(
        handles: Map<SelectionAdjustMode, View>,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        size: Int,
    ) {
        val halfSize = size / 2
        handles.forEach { (mode, handle) ->
            val left = when (mode) {
                SelectionAdjustMode.TopLeft, SelectionAdjustMode.BottomLeft -> x - halfSize
                SelectionAdjustMode.TopRight, SelectionAdjustMode.BottomRight -> x + width - halfSize
                SelectionAdjustMode.Move -> x
            }
            val top = when (mode) {
                SelectionAdjustMode.TopLeft, SelectionAdjustMode.TopRight -> y - halfSize
                SelectionAdjustMode.BottomLeft, SelectionAdjustMode.BottomRight -> y + height - halfSize
                SelectionAdjustMode.Move -> y
            }
            handle.layoutParams = (handle.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = left
                topMargin = top
            }
        }
    }

    private fun adjustSelection(
        mode: SelectionAdjustMode,
        start: Quad,
        dx: Float,
        dy: Float,
        rootWidth: Int,
        rootHeight: Int,
    ): Quad {
        val minSize = 16
        val maxWidth = rootWidth.coerceAtLeast(minSize)
        val maxHeight = rootHeight.coerceAtLeast(minSize)
        val right = start.x + start.width
        val bottom = start.y + start.height
        val deltaX = dx.toInt()
        val deltaY = dy.toInt()

        return when (mode) {
            SelectionAdjustMode.Move -> Quad(
                x = (start.x + deltaX).coerceIn(0, maxWidth - start.width),
                y = (start.y + deltaY).coerceIn(0, maxHeight - start.height),
                width = start.width,
                height = start.height,
            )
            SelectionAdjustMode.TopLeft -> {
                val left = (start.x + deltaX).coerceIn(0, right - minSize)
                val top = (start.y + deltaY).coerceIn(0, bottom - minSize)
                Quad(left, top, right - left, bottom - top)
            }
            SelectionAdjustMode.TopRight -> {
                val nextRight = (right + deltaX).coerceIn(start.x + minSize, maxWidth)
                val top = (start.y + deltaY).coerceIn(0, bottom - minSize)
                Quad(start.x, top, nextRight - start.x, bottom - top)
            }
            SelectionAdjustMode.BottomLeft -> {
                val left = (start.x + deltaX).coerceIn(0, right - minSize)
                val nextBottom = (bottom + deltaY).coerceIn(start.y + minSize, maxHeight)
                Quad(left, start.y, right - left, nextBottom - start.y)
            }
            SelectionAdjustMode.BottomRight -> {
                val nextRight = (right + deltaX).coerceIn(start.x + minSize, maxWidth)
                val nextBottom = (bottom + deltaY).coerceIn(start.y + minSize, maxHeight)
                Quad(start.x, start.y, nextRight - start.x, nextBottom - start.y)
            }
        }
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
        triggerClickOnTap: Boolean = false,
        useFloatingBallDocking: Boolean = false,
    ) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var hasDragged = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    downX = event.rawX
                    downY = event.rawY
                    hasDragged = false
                    if (useFloatingBallDocking) {
                        setFloatingBallVisualOffset(target, 0f)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!hasDragged && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        hasDragged = true
                    }
                    val bounds = screenBoundsFor(target)
                    layoutParams.x = (startX + dx.toInt()).coerceIn(0, bounds.maxX)
                    layoutParams.y = (startY + dy.toInt()).coerceIn(0, bounds.maxY)
                    if (useFloatingBallDocking) {
                        setFloatingBallVisualOffset(target, 0f)
                    }
                    windowManager.updateViewLayout(target, layoutParams)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (triggerClickOnTap && !hasDragged) {
                        target.performClick()
                    } else if (useFloatingBallDocking) {
                        settleFloatingBall(target, layoutParams)
                    } else {
                        keepInsideScreen(target, layoutParams)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (useFloatingBallDocking) {
                        settleFloatingBall(target, layoutParams)
                    } else {
                        keepInsideScreen(target, layoutParams)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun keepInsideScreen(target: View, layoutParams: WindowManager.LayoutParams) {
        val bounds = screenBoundsFor(target)
        layoutParams.x = layoutParams.x.coerceIn(0, bounds.maxX)
        layoutParams.y = layoutParams.y.coerceIn(0, bounds.maxY)
        windowManager.updateViewLayout(target, layoutParams)
    }

    private fun floatingBallPlacement(startX: Int, startY: Int): FloatingBallPlacement {
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val maxX = (displayWidth - FLOATING_BALL_SIZE).coerceAtLeast(0)
        val maxY = (displayHeight - FLOATING_BALL_SIZE).coerceAtLeast(0)
        val halfHiddenOffset = FLOATING_BALL_SIZE / 2
        val distanceToRight = displayWidth - (startX + FLOATING_BALL_SIZE)

        return when {
            startX <= FLOATING_BALL_EDGE_SLOP -> FloatingBallPlacement(
                x = 0,
                y = startY.coerceIn(0, maxY),
                visualOffset = -halfHiddenOffset.toFloat(),
            )
            distanceToRight <= FLOATING_BALL_EDGE_SLOP -> FloatingBallPlacement(
                x = maxX,
                y = startY.coerceIn(0, maxY),
                visualOffset = halfHiddenOffset.toFloat(),
            )
            else -> FloatingBallPlacement(
                x = startX.coerceIn(0, maxX),
                y = startY.coerceIn(0, maxY),
                visualOffset = 0f,
            )
        }
    }

    private fun settleFloatingBall(target: View, layoutParams: WindowManager.LayoutParams) {
        val placement = floatingBallPlacement(layoutParams.x, layoutParams.y)
        layoutParams.x = placement.x
        layoutParams.y = placement.y
        setFloatingBallVisualOffset(target, placement.visualOffset)
        windowManager.updateViewLayout(target, layoutParams)
    }

    private fun setFloatingBallVisualOffset(target: View, offset: Float) {
        (target as? FrameLayout)?.getChildAt(0)?.translationX = offset
    }

    private fun screenBoundsFor(target: View): ScreenBounds {
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val targetWidth = target.width.takeIf { it > 0 } ?: FLOATING_BALL_SIZE
        val targetHeight = target.height.takeIf { it > 0 } ?: FLOATING_BALL_SIZE
        return ScreenBounds(
            displayWidth = displayWidth,
            maxX = (displayWidth - targetWidth).coerceAtLeast(0),
            maxY = (displayHeight - targetHeight).coerceAtLeast(0),
        )
    }

    private fun roundedBackground(
        color: Int = 0xcc111827.toInt(),
        radius: Float = 18f,
        oval: Boolean = false,
    ) = GradientDrawable().apply {
        if (oval) {
            shape = GradientDrawable.OVAL
        }
        cornerRadius = radius
        setColor(color)
    }

    private fun selectionBorder() = GradientDrawable().apply {
        setColor(0x2216a34a)
        setStroke(5, 0xff22c55e.toInt())
    }

    private data class Quad(val x: Int, val y: Int, val width: Int, val height: Int)

    private data class ScreenBounds(val displayWidth: Int, val maxX: Int, val maxY: Int)

    private data class FloatingBallPlacement(val x: Int, val y: Int, val visualOffset: Float)

    private enum class ExpandDirection {
        Right,
        Left,
    }

    private enum class FloatingState {
        ExpandedRight,
        ExpandedLeft,
        CollapsingToBall,
        Ball,
    }

    private enum class SelectionAdjustMode {
        Move,
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
    }
}
