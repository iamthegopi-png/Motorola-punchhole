package com.edge60pro.punchholecompanion

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Automatically starts the companion service when the phone boots or the app updates.
 */
class CompanionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Settings.canDrawOverlays(context)) {
            val serviceIntent = Intent(context, PunchHoleCompanionService::class.java)
            runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
        }
    }
}

class PunchHoleCompanionService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private var overlayView: CompanionOverlayView? = null
    private var punchHoleTouchView: View? = null
    private var characterTouchView: View? = null

    private lateinit var characterTouchParams: WindowManager.LayoutParams
    private lateinit var punchHoleTouchParams: WindowManager.LayoutParams
    private lateinit var renderParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private var stealthResumeJob: Job? = null

    private var isScreenOn = true
    private var isCameraInUse = false
    private var isPausedForBanking = false
    private var areWindowsAttached = false
    private var lastSpawnElapsedMs = 0L

    private var baseCutoutX = 540
    private var baseCutoutY = 48

    companion object {
        const val ACTION_PAUSE_FOR_BANKING = "com.edge60pro.ACTION_PAUSE_BANKING"
        const val ACTION_RESUME_COMPANION = "com.edge60pro.ACTION_RESUME_COMPANION"
        const val ACTION_SUMMON_NOW = "com.edge60pro.ACTION_SUMMON_NOW"
        private const val CHANNEL_ID = "companion_stealth_channel"
        private const val NOTIF_ID = 1001
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    overlayView?.forceSleepNow()
                    stopAppearanceTimer()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    restartAppearanceTimer()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // Greet user when unlocking the phone if enough time has passed
                    isScreenOn = true
                    val cfg = CompanionRepository.config.value
                    val now = SystemClock.elapsedRealtime()
                    val cooldownMs = if (cfg.intervalMinutes > 0) cfg.intervalMinutes * 60_000L else 180_000L
                    if (!isCameraInUse && !isPausedForBanking &&
                        overlayView?.state == CharacterState.HIDDEN &&
                        (now - lastSpawnElapsedMs) >= cooldownMs
                    ) {
                        lastSpawnElapsedMs = now
                        overlayView?.spawnFromPunchHole("Welcome back! 👋")
                    }
                }
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isScreenOn || isCameraInUse || isPausedForBanking) return
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    overlayView?.triggerBatteryReaction(
                        isChargingSurge = true,
                        batteryPct = readCurrentBatteryPercentage()
                    )
                }
                Intent.ACTION_BATTERY_LOW -> {
                    overlayView?.triggerBatteryReaction(
                        isChargingSurge = false,
                        batteryPct = readCurrentBatteryPercentage()
                    )
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (overlayView?.state != CharacterState.HIDDEN) {
                        overlayView?.triggerSingleTapTrick(
                            CompanionRepository.config.value.allowEdgeWalk
                        )
                    }
                }
            }
        }
    }

    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            if (isPausedForBanking || !isScreenOn || isCameraInUse) return

            val isPlayingMusic = configs?.any { cfg ->
                cfg.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
            } == true

            if (isPlayingMusic && !CompanionEventBus.isMusicCurrentlyPlaying) {
                CompanionEventBus.isMusicCurrentlyPlaying = true
                val randomBpm = (112..138).random().toFloat()
                CompanionEventBus.activeTrackBpm = randomBpm
                if (CompanionRepository.config.value.danceToMusic) {
                    overlayView?.triggerMusicDance(randomBpm)
                }
            } else if (!isPlayingMusic && CompanionEventBus.isMusicCurrentlyPlaying) {
                CompanionEventBus.isMusicCurrentlyPlaying = false
                if (overlayView?.state == CharacterState.DANCE_MUSIC_GROOVE) {
                    overlayView?.retreatToPunchHole()
                }
            }
        }
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            isCameraInUse = true
            overlayView?.retreatToPunchHole()
        }

        override fun onCameraAvailable(cameraId: String) {
            isCameraInUse = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        CompanionRepository.init(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        startStealthForegroundNotification()
        detectCutoutCoordinates()
        setupOverlayWindows()
        observeSettingsAndCommands()

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
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

        audioManager.registerAudioPlaybackCallback(
            audioPlaybackCallback,
            Handler(Looper.getMainLooper())
        )

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraManager.registerAvailabilityCallback(cameraCallback, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_FOR_BANKING -> pauseOverlayForBanking(minutes = 5)
            ACTION_RESUME_COMPANION -> resumeOverlayFromBanking()
            ACTION_SUMMON_NOW -> {
                if (isPausedForBanking) resumeOverlayFromBanking()
                overlayView?.toggleCharacterVisibility()
            }
            else -> {
                if (!areWindowsAttached && !isPausedForBanking && Settings.canDrawOverlays(this)) {
                    attachWindowsToScreen()
                }
            }
        }
        return START_STICKY
    }

    /**
     * Prevents the service from dying when the user swipes the app away from Recent Apps.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, PunchHoleCompanionService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            101,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 900L,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun startStealthForegroundNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Punch Hole Companion Controls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps companion alive & provides 1-tap UPI Stealth pause"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val notif = buildControlNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildControlNotification(): Notification {
        val stealthIntent = Intent(this, PunchHoleCompanionService::class.java).apply {
            action = if (isPausedForBanking) ACTION_RESUME_COMPANION else ACTION_PAUSE_FOR_BANKING
        }
        val pendingStealth = PendingIntent.getService(
            this,
            0,
            stealthIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summonIntent = Intent(this, PunchHoleCompanionService::class.java).apply {
            action = ACTION_SUMMON_NOW
        }
        val pendingSummon = PendingIntent.getService(
            this,
            1,
            summonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPausedForBanking) {
            "Banking Stealth Active (Overlay Hidden)"
        } else {
            "Punch Hole Companion Active"
        }
        val subtitle = if (isPausedForBanking) {
            "Safe for UPI & Banking. Tap Resume when done."
        } else {
            "Tap punch hole anytime, or use quick buttons below."
        }
        val stealthLabel = if (isPausedForBanking) "Resume" else "Pause for UPI"

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Summon / Trick", pendingSummon).build())
            .addAction(Notification.Action.Builder(null, stealthLabel, pendingStealth).build())
            .build()
    }

    private fun pauseOverlayForBanking(minutes: Int) {
        isPausedForBanking = true
        stealthResumeJob?.cancel()
        overlayView?.forceSleepNow()
        detachWindowsFromScreen()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildControlNotification())

        stealthResumeJob = serviceScope.launch {
            delay(minutes * 60_000L)
            resumeOverlayFromBanking()
        }
    }

    private fun resumeOverlayFromBanking() {
        isPausedForBanking = false
        stealthResumeJob?.cancel()
        attachWindowsToScreen()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildControlNotification())
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

        renderParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

        val punchHitSize = (46 * density).toInt()
        punchHoleTouchView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (!isCameraInUse && !isPausedForBanking) {
                    lastSpawnElapsedMs = SystemClock.elapsedRealtime()
                    overlayView?.toggleCharacterVisibility()
                }
            }
        }
        punchHoleTouchParams = WindowManager.LayoutParams(
            punchHitSize,
            punchHitSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
                    val view = overlayView ?: return true
                    if (CompanionEventBus.isMusicCurrentlyPlaying &&
                        CompanionRepository.config.value.danceToMusic &&
                        view.state != CharacterState.DANCE_MUSIC_GROOVE
                    ) {
                        view.triggerMusicDance(CompanionEventBus.activeTrackBpm)
                    } else {
                        view.triggerSingleTapTrick(
                            CompanionRepository.config.value.allowEdgeWalk
                        )
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    overlayView?.retreatToPunchHole()
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
                            overlayView?.onDragCharacter(event.rawX, event.rawY)
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
                            overlayView?.onReleaseCharacter(vx, vy)
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

        attachWindowsToScreen()
    }

    private fun attachWindowsToScreen() {
        if (areWindowsAttached || !Settings.canDrawOverlays(this)) return
        runCatching {
            overlayView?.let { windowManager.addView(it, renderParams) }
            punchHoleTouchView?.let { windowManager.addView(it, punchHoleTouchParams) }
            characterTouchView?.let { windowManager.addView(it, characterTouchParams) }
            areWindowsAttached = true
        }
    }

    private fun detachWindowsFromScreen() {
        if (!areWindowsAttached) return
        runCatching { overlayView?.let { windowManager.removeView(it) } }
        runCatching { punchHoleTouchView?.let { windowManager.removeView(it) } }
        runCatching { characterTouchView?.let { windowManager.removeView(it) } }
        areWindowsAttached = false
    }

    private fun updateCharacterHitboxPosition(charX: Float, charY: Float, isVisible: Boolean) {
        val touchView = characterTouchView ?: return
        if (!areWindowsAttached) return

        if (!isVisible) {
            if (touchView.visibility != View.GONE) {
                touchView.visibility = View.GONE
            }
            return
        }
        if (touchView.visibility != View.VISIBLE) {
            touchView.visibility = View.VISIBLE
        }
        val half = characterTouchParams.width / 2
        val newX = (charX - half).toInt()
        val newY = (charY - half).toInt()

        if (abs(characterTouchParams.x - newX) >= 6 || abs(characterTouchParams.y - newY) >= 6) {
            characterTouchParams.x = newX
            characterTouchParams.y = newY
            runCatching { windowManager.updateViewLayout(touchView, characterTouchParams) }
        }
    }

    private fun observeSettingsAndCommands() {
        serviceScope.launch {
            CompanionRepository.config.collectLatest { cfg ->
                val anchorX = baseCutoutX + cfg.xOffsetPx
                val anchorY = baseCutoutY + cfg.yOffsetPx
                overlayView?.updateCutoutAnchor(anchorX.toFloat(), anchorY.toFloat())
                overlayView?.applyConfig(cfg)

                punchHoleTouchParams.x = anchorX - punchHoleTouchParams.width / 2
                punchHoleTouchParams.y = anchorY - punchHoleTouchParams.height / 2
                if (areWindowsAttached) {
                    punchHoleTouchView?.let {
                        runCatching { windowManager.updateViewLayout(it, punchHoleTouchParams) }
                    }
                }
                restartAppearanceTimer()
            }
        }

        serviceScope.launch {
            CompanionRepository.manualCommand.collectLatest { cmd ->
                when (cmd) {
                    "TOGGLE" -> {
                        if (isPausedForBanking) resumeOverlayFromBanking()
                        lastSpawnElapsedMs = SystemClock.elapsedRealtime()
                        overlayView?.toggleCharacterVisibility()
                    }
                    "TRICK" -> {
                        if (isPausedForBanking) resumeOverlayFromBanking()
                        val view = overlayView
                        if (view != null) {
                            if (view.state == CharacterState.HIDDEN) {
                                lastSpawnElapsedMs = SystemClock.elapsedRealtime()
                                view.spawnFromPunchHole()
                            } else {
                                view.triggerSingleTapTrick(
                                    CompanionRepository.config.value.allowEdgeWalk
                                )
                            }
                        }
                    }
                    "PAUSE_BANKING" -> pauseOverlayForBanking(minutes = 5)
                }
                CompanionRepository.manualCommand.value = null
            }
        }
    }

    private fun restartAppearanceTimer() {
        stopAppearanceTimer()
        val minutes = CompanionRepository.config.value.intervalMinutes
        if (minutes <= 0 || !isScreenOn) return

        val intervalMs = minutes * 60_000L
        timerJob = serviceScope.launch {
            while (isActive && isScreenOn) {
                delay(20_000L) // Lightweight check every 20s against monotonic hardware clock
                val now = SystemClock.elapsedRealtime()
                if (isScreenOn && !isCameraInUse && !isPausedForBanking &&
                    overlayView?.state == CharacterState.HIDDEN &&
                    (now - lastSpawnElapsedMs) >= intervalMs
                ) {
                    lastSpawnElapsedMs = now
                    overlayView?.spawnFromPunchHole()
                }
            }
        }
    }

    private fun stopAppearanceTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAppearanceTimer()
        stealthResumeJob?.cancel()
        serviceScope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback) }
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        runCatching { cameraManager.unregisterAvailabilityCallback(cameraCallback) }
        detachWindowsFromScreen()
    }
}
