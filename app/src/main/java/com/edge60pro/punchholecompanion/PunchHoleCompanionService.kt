package com.edge60pro.punchholecompanion

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.hypot

class PunchHoleCompanionService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: CompanionOverlayView
    private lateinit var punchHoleTouchView: View
    private lateinit var characterTouchView: View

    private lateinit var characterTouchParams: WindowManager.LayoutParams
    private lateinit var punchHoleTouchParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private var isScreenOn = true
    private var isCameraInUse = false

    private var baseCutoutX = 540
    private var baseCutoutY = 48

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    overlayView.forceSleepNow()
                    stopAppearanceTimer()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    restartAppearanceTimer()
                }
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isScreenOn || isCameraInUse) return
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    overlayView.triggerBatteryReaction(
                        isChargingSurge = true,
                        batteryPct = readCurrentBatteryPercentage()
                    )
                }
                Intent.ACTION_BATTERY_LOW -> {
                    overlayView.triggerBatteryReaction(
                        isChargingSurge = false,
                        batteryPct = readCurrentBatteryPercentage()
                    )
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (overlayView.state != CharacterState.HIDDEN) {
                        overlayView.triggerSingleTapTrick(
                            CompanionRepository.config.value.allowEdgeWalk
                        )
                    }
                }
            }
        }
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            isCameraInUse = true
            overlayView.retreatToPunchHole()
        }

        override fun onCameraAvailable(cameraId: String) {
            isCameraInUse = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CompanionRepository.init(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        detectCutoutCoordinates()
        setupOverlayWindows()
        observeSettingsAndCommands()

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraManager.registerAvailabilityCallback(cameraCallback, Handler(Looper.getMainLooper()))
    }

    private fun readCurrentBatteryPercentage(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
    }

    private fun detectCutoutCoordinates() {
        val metrics = windowManager.currentWindowMetrics
        val cutout = metrics.windowInsets.displayCutout
        if (cutout != null && cutout.boundingRects.isNotEmpty()) {
            val rect = cutout.boundingRects.first()
            baseCutoutX = rect.centerX()
            baseCutoutY = rect.centerY()
        } else {
            baseCutoutX = metrics.bounds.width() / 2
            baseCutoutY = (18 * resources.displayMetrics.density).toInt()
        }
    }

    private fun setupOverlayWindows() {
        val density = resources.displayMetrics.density

        overlayView = CompanionOverlayView(this) { charX, charY, isVisible ->
            updateCharacterHitboxPosition(charX, charY, isVisible)
        }

        val renderParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        windowManager.addView(overlayView, renderParams)

        val punchHitSize = (46 * density).toInt()
        punchHoleTouchView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (!isCameraInUse) {
                    overlayView.toggleCharacterVisibility()
                }
            }
        }
        punchHoleTouchParams = WindowManager.LayoutParams(
            punchHitSize,
            punchHitSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = baseCutoutX - punchHitSize / 2
            y = baseCutoutY - punchHitSize / 2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        windowManager.addView(punchHoleTouchView, punchHoleTouchParams)

        val charHitSize = (68 * density).toInt()
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var isDraggingCharacter = false
        var velocityTracker: VelocityTracker? = null

        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (overlayView.state == CharacterState.REACT_NOTIFICATION) {
                        runCatching { CompanionEventBus.lastNotificationIntent?.send() }
                        overlayView.retreatToPunchHole()
                    } else if (CompanionEventBus.isMusicCurrentlyPlaying &&
                        CompanionRepository.config.value.danceToMusic &&
                        overlayView.state != CharacterState.DANCE_MUSIC_GROOVE
                    ) {
                        overlayView.triggerMusicDance(CompanionEventBus.activeTrackBpm)
                    } else {
                        overlayView.triggerSingleTapTrick(
                            CompanionRepository.config.value.allowEdgeWalk
                        )
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    overlayView.retreatToPunchHole()
                    return true
                }
            }
        )

        characterTouchView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            isHapticFeedbackEnabled = true

            setOnTouchListener { view, event ->
                val rawEvent = MotionEvent.obtain(event)
                rawEvent.setLocation(event.rawX, event.rawY)

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        isDraggingCharacter = false
                        velocityTracker?.recycle()
                        velocityTracker = VelocityTracker.obtain()
                        velocityTracker?.addMovement(rawEvent)
                        gestureDetector.onTouchEvent(event)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        velocityTracker?.addMovement(rawEvent)
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY

                        if (!isDraggingCharacter && hypot(dx, dy) > touchSlop) {
                            isDraggingCharacter = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }

                        if (isDraggingCharacter) {
                            overlayView.onDragCharacter(event.rawX, event.rawY)
                        } else {
                            gestureDetector.onTouchEvent(event)
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        velocityTracker?.addMovement(rawEvent)
                        if (isDraggingCharacter) {
                            velocityTracker?.computeCurrentVelocity(1000)
                            val vx = velocityTracker?.xVelocity ?: 0f
                            val vy = velocityTracker?.yVelocity ?: 0f
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            overlayView.onReleaseCharacter(vx, vy)
                            isDraggingCharacter = false
                        } else {
                            gestureDetector.onTouchEvent(event)
                        }
                        velocityTracker?.recycle()
                        velocityTracker = null
                    }
                }
                rawEvent.recycle()
                true
            }
        }

        characterTouchParams = WindowManager.LayoutParams(
            charHitSize,
            charHitSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        windowManager.addView(characterTouchView, characterTouchParams)
    }

    private fun updateCharacterHitboxPosition(charX: Float, charY: Float, isVisible: Boolean) {
        if (!isVisible) {
            if (characterTouchView.visibility != View.GONE) {
                characterTouchView.visibility = View.GONE
            }
            return
        }
        if (characterTouchView.visibility != View.VISIBLE) {
            characterTouchView.visibility = View.VISIBLE
        }
        val half = characterTouchParams.width / 2
        val newX = (charX - half).toInt()
        val newY = (charY - half).toInt()

        if (abs(characterTouchParams.x - newX) >= 6 || abs(characterTouchParams.y - newY) >= 6) {
            characterTouchParams.x = newX
            characterTouchParams.y = newY
            runCatching { windowManager.updateViewLayout(characterTouchView, characterTouchParams) }
        }
    }

    private fun observeSettingsAndCommands() {
        serviceScope.launch {
            CompanionRepository.config.collectLatest { cfg ->
                val anchorX = baseCutoutX + cfg.xOffsetPx
                val anchorY = baseCutoutY + cfg.yOffsetPx
                overlayView.updateCutoutAnchor(anchorX.toFloat(), anchorY.toFloat())
                overlayView.applyConfig(cfg)

                punchHoleTouchParams.x = anchorX - punchHoleTouchParams.width / 2
                punchHoleTouchParams.y = anchorY - punchHoleTouchParams.height / 2
                runCatching { windowManager.updateViewLayout(punchHoleTouchView, punchHoleTouchParams) }

                restartAppearanceTimer()
            }
        }

        serviceScope.launch {
            CompanionRepository.manualCommand.collectLatest { cmd ->
                when (cmd) {
                    "TOGGLE" -> overlayView.toggleCharacterVisibility()
                    "TRICK" -> {
                        if (overlayView.state == CharacterState.HIDDEN) {
                            overlayView.spawnFromPunchHole()
                        } else {
                            overlayView.triggerSingleTapTrick(
                                CompanionRepository.config.value.allowEdgeWalk
                            )
                        }
                    }
                }
                CompanionRepository.manualCommand.value = null
            }
        }

        serviceScope.launch {
            CompanionEventBus.events.collect { event ->
                if (!isScreenOn || isCameraInUse) return@collect
                val cfg = CompanionRepository.config.value
                when (event) {
                    is CompanionTriggerEvent.NotificationAlert -> {
                        if (cfg.reactToNotifications) {
                            overlayView.triggerNotificationAlert(event.accentColor)
                        }
                    }
                    is CompanionTriggerEvent.MusicBeatStart -> {
                        if (cfg.danceToMusic) {
                            overlayView.triggerMusicDance(event.estimatedBpm)
                        }
                    }
                    is CompanionTriggerEvent.MusicStopped -> {
                        if (overlayView.state == CharacterState.DANCE_MUSIC_GROOVE) {
                            overlayView.retreatToPunchHole()
                        }
                    }
                }
            }
        }
    }

    private fun restartAppearanceTimer() {
        stopAppearanceTimer()
        val minutes = CompanionRepository.config.value.intervalMinutes
        if (minutes <= 0 || !isScreenOn) return

        timerJob = serviceScope.launch {
            while (isActive && isScreenOn) {
                delay(minutes * 60_000L)
                if (isScreenOn && !isCameraInUse && overlayView.state == CharacterState.HIDDEN) {
                    overlayView.spawnFromPunchHole()
                }
            }
        }
    }

    private fun stopAppearanceTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        stopAppearanceTimer()
        serviceScope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(powerReceiver) }
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        runCatching { cameraManager.unregisterAvailabilityCallback(cameraCallback) }
        runCatching { windowManager.removeView(overlayView) }
        runCatching { windowManager.removeView(punchHoleTouchView) }
        runCatching { windowManager.removeView(characterTouchView) }
    }
}
