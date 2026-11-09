package com.edge60pro.punchholecompanion

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Choreographer
import android.view.View
import androidx.annotation.RawRes
import kotlin.math.*
import kotlin.random.Random

enum class CharacterState {
    HIDDEN,
    DESCENDING,
    IDLE_HANGING,
    DRAGGING_SLINGSHOT,
    SLINGSHOT_LAUNCH,
    TRICK_BACKFLIP,
    TRICK_BUNGEE_JUMP,
    TRICK_SWING_WAVE,
    TRICK_TARZAN_SWING,
    TRICK_UPSIDE_DOWN,
    TRICK_SMOKE_TELEPORT,
    EDGE_WALKING,
    REACT_CHARGING_SURGE,
    REACT_LOW_BATTERY,
    REACT_NOTIFICATION,
    DANCE_MUSIC_GROOVE,
    CLIMBING_UP
}

class CompanionOverlayView(
    context: Context,
    private val onCharacterBoundsMoved: (Float, Float, Boolean) -> Unit
) : View(context), Choreographer.FrameCallback, SensorEventListener {

    private val density = resources.displayMetrics.density
    private val rope = VerletRopeEngine(18)

    // Hardware Tilt Sensor (Active ONLY while character is visible = 0% idle battery drain)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var isSensorRegistered = false
    private var phoneTiltX = 0f

    var state: CharacterState = CharacterState.HIDDEN
        private set

    private var lastFrameNanos = 0L
    private var isLoopRunning = false
    private var stateTimer = 0f
    private var idleDurationTarget = 16f

    private var charX = 540f
    private var charY = 45f
    private var charRotationDeg = 0f
    private var smoothRotationDeg = 0f
    private var scaleX = 1f
    private var scaleY = 1f
    private var portalScale = 0f

    // Secondary Follow-Through & Overlapping Action Channels
    private var followThroughX = 0f
    private var followThroughY = 0f
    private var lookOffsetX = 0f
    private var targetLookOffsetX = 0f
    private var lookChangeTimer = 0f

    private var blinkTimer = 0f
    private var isBlinking = false
    private var edgeProgress = 0f
    private var edgeDirection = 1f

    private var ropeThicknessPx = 4.0f * density
    private var currentSkin: CharacterSkin = CharacterSkin.MOCHI_CAT
    private var autoSwapEnabled: Boolean = true
    private var swapIndex: Int = 0
    private var currentStretchRatio: Float = 1.0f

    private var lottieRig: LottieCharacterRig? = null
    private var currentBatteryPct = 100
    private var activeBpm = 116f

    // Cartoon Speech Bubble State
    private var speechText: String = ""
    private var speechTimer: Float = 0f
    private var speechDuration: Float = 3.2f

    // Pre-allocated Studio Particle Pool (18 Particles, Zero GC allocation)
    private val maxParticles = 18
    private val partActive = BooleanArray(maxParticles)
    private val partX = FloatArray(maxParticles)
    private val partY = FloatArray(maxParticles)
    private val partVx = FloatArray(maxParticles)
    private val partVy = FloatArray(maxParticles)
    private val partSize = FloatArray(maxParticles)
    private val partLife = FloatArray(maxParticles)
    private val partMaxLife = FloatArray(maxParticles)
    private val partColor = IntArray(maxParticles)
    private val partType = IntArray(maxParticles) // 0 = Star, 1 = Heart, 2 = Smoke Puff

    // Pre-allocated Paths & RectF
    private val ropePath = Path()
    private val scarfOrTailPath = Path()
    private val earPath = Path()
    private val starPath = Path()
    private val lightningPath = Path()
    private val bodyRect = RectF()
    private val bubbleRect = RectF()

    // Studio Braided Rope Paints
    private val ropeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(75, 0, 0, 0)
    }

    private val ropeOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#181A24")
    }

    private val ropePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#F4A261")
    }

    private val ropeBraidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 7f * density), 0f)
        color = Color.parseColor("#E76F51")
    }

    // Studio Cel-Shaded Cartoon Character Paints
    private val cartoonOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#14161F")
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val chinShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(38, 0, 0, 0)
    }

    private val rimLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(95, 255, 255, 255)
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(135, 255, 105, 135)
    }

    private val tailStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFDF9")
    }

    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#181A24")
    }

    private val cutoutRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.parseColor("#00E5FF")
    }

    private val tensionIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
        color = Color.argb(150, 255, 255, 255)
    }

    private val electricArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E676")
    }

    private val lowBatteryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.parseColor("#FF1744")
    }

    private val batteryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#00E676")
    }

    private val headphonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E5FF")
    }

    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        typeface = Typeface.DEFAULT_BOLD
        color = Color.parseColor("#B388FF")
    }

    fun attachLottieAsset(@RawRes resId: Int) {
        lottieRig = LottieCharacterRig(context, resId)
    }

    fun updateCutoutAnchor(ax: Float, ay: Float) {
        rope.anchorX = ax
        rope.anchorY = ay
        if (state == CharacterState.HIDDEN) {
            rope.resetToAnchor(ax, ay)
        }
    }

    fun applyConfig(config: CompanionConfig) {
        rope.totalLengthPx = config.ropeLengthDp * density
        ropeThicknessPx = config.ropeThicknessDp * density
        autoSwapEnabled = config.autoSwapCharacters
        currentSkin = config.skin
        swapIndex = CharacterSkin.values().indexOf(currentSkin).coerceAtLeast(0)
        syncSkinPaints(currentSkin)
    }

    private fun syncSkinPaints(skin: CharacterSkin) {
        ropePaint.strokeWidth = ropeThicknessPx
        ropeOutlinePaint.strokeWidth = ropeThicknessPx + 2.8f * density
        ropeShadowPaint.strokeWidth = ropeThicknessPx + 4.0f * density
        ropeBraidPaint.strokeWidth = (ropeThicknessPx * 0.55f).coerceAtLeast(1.5f * density)

        ropePaint.color = skin.ropeColor
        ropeBraidPaint.color = skin.ropeBraidColor
        bodyPaint.color = skin.bodyColor
        secondaryPaint.color = skin.secondaryColor
        accentPaint.color = skin.accentColor
        eyePaint.color = skin.eyeColor
        tailStrokePaint.color = skin.bodyColor
    }

    private fun advanceCharacterIfAutoSwap() {
        if (autoSwapEnabled) {
            val allSkins = CharacterSkin.values()
            swapIndex = (swapIndex + 1) % allSkins.size
            currentSkin = allSkins[swapIndex]
        } else {
            currentSkin = CompanionRepository.config.value.skin
        }
        syncSkinPaints(currentSkin)
    }

    private fun showSpeechBubble(customMessage: String? = null) {
        speechText = customMessage ?: when (currentSkin) {
            CharacterSkin.MOCHI_CAT -> listOf("Meow~ ✨", "Purrfect! 🐾", "Cozy day 💕", "Nyaa~ 🧶").random()
            CharacterSkin.SHADOW_NINJA -> listOf("Nin-Nin! 🥷", "Silent breeze 🍃", "Shadow Art ✨", "At your service!").random()
            CharacterSkin.ASTRO_BOT -> listOf("Zero-G! 🚀", "Hello Friend ✨", "Beep-Boop! 🤖", "All systems calm").random()
            CharacterSkin.KITSUNE_SPIRIT -> listOf("Kon-Kon! 🦊", "Fox Blessing ✨", "Lucky chime 🔔", "Peaceful vibes").random()
        }
        speechDuration = 3.2f
        speechTimer = speechDuration
    }

    private fun emitParticleBurst(
        originX: Float,
        originY: Float,
        count: Int,
        type: Int,
        color: Int
    ) {
        var spawned = 0
        for (i in 0 until maxParticles) {
            if (!partActive[i]) {
                partActive[i] = true
                partX[i] = originX + (Random.nextFloat() - 0.5f) * 20f * density
                partY[i] = originY + (Random.nextFloat() - 0.5f) * 20f * density
                val angle = Random.nextFloat() * PI.toFloat() * 2f
                // Dreamy, slow-floating studio particle speeds
                val speed = (18f + Random.nextFloat() * 48f) * density
                partVx[i] = cos(angle) * speed
                partVy[i] = sin(angle) * speed - 22f * density
                partSize[i] = (if (type == 2) 12f else 6.5f) * density * (0.75f + Random.nextFloat() * 0.5f)
                partMaxLife[i] = 1.15f + Random.nextFloat() * 0.65f
                partLife[i] = partMaxLife[i]
                partColor[i] = color
                partType[i] = type
                spawned++
                if (spawned >= count) break
            }
        }
    }

    fun toggleCharacterVisibility() {
        if (state == CharacterState.HIDDEN) {
            spawnFromPunchHole()
        } else if (state != CharacterState.CLIMBING_UP) {
            retreatToPunchHole()
        }
    }

    fun spawnFromPunchHole(greetingText: String? = null) {
        if (state != CharacterState.HIDDEN) return
        advanceCharacterIfAutoSwap()
        rope.resetToAnchor(rope.anchorX, rope.anchorY)
        rope.deployedRatio = 0.02f
        state = CharacterState.DESCENDING
        stateTimer = 0f
        idleDurationTarget = Random.nextFloat() * 8f + 16f // Calm 16-24s hangout
        rope.applyTipImpulse((Random.nextFloat() - 0.5f) * 18f * density, 10f * density)
        emitParticleBurst(rope.anchorX, rope.anchorY + 12f * density, 6, 0, Color.parseColor("#00F5D4"))
        showSpeechBubble(greetingText)
        startAnimationLoop()
    }

    fun retreatToPunchHole() {
        if (state == CharacterState.HIDDEN || state == CharacterState.CLIMBING_UP) return
        rope.isTipPinned = false
        state = CharacterState.CLIMBING_UP
        stateTimer = 0f
        rope.applyTipImpulse((Random.nextFloat() - 0.5f) * 10f * density, -6f * density)
        startAnimationLoop()
    }

    fun triggerSingleTapTrick(allowEdgeWalk: Boolean) {
        if (state == CharacterState.HIDDEN || state == CharacterState.CLIMBING_UP) return
        stateTimer = 0f

        val nextTrick = if (state == CharacterState.EDGE_WALKING || state == CharacterState.TRICK_UPSIDE_DOWN) {
            CharacterState.TRICK_BACKFLIP
        } else {
            val pool = mutableListOf(
                CharacterState.TRICK_BACKFLIP,
                CharacterState.TRICK_BUNGEE_JUMP,
                CharacterState.TRICK_SWING_WAVE,
                CharacterState.TRICK_TARZAN_SWING,
                CharacterState.TRICK_UPSIDE_DOWN,
                CharacterState.TRICK_SMOKE_TELEPORT
            )
            if (allowEdgeWalk) pool.add(CharacterState.EDGE_WALKING)
            pool.random()
        }

        showSpeechBubble()
        emitParticleBurst(charX, charY, 5, 0, currentSkin.accentColor)

        when (nextTrick) {
            CharacterState.TRICK_BACKFLIP -> {
                state = CharacterState.TRICK_BACKFLIP
            }
            CharacterState.TRICK_BUNGEE_JUMP -> {
                state = CharacterState.TRICK_BUNGEE_JUMP
            }
            CharacterState.TRICK_SWING_WAVE -> {
                state = CharacterState.TRICK_SWING_WAVE
                rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 42f * density, 0f)
            }
            CharacterState.TRICK_TARZAN_SWING -> {
                state = CharacterState.TRICK_TARZAN_SWING
                val dir = if (Random.nextBoolean()) 1 else -1
                rope.applyTipImpulse(dir * 75f * density, -15f * density)
                showSpeechBubble("Wheee~ 🌟")
            }
            CharacterState.TRICK_UPSIDE_DOWN -> {
                state = CharacterState.TRICK_UPSIDE_DOWN
                rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 22f * density, 12f * density)
                showSpeechBubble("Upside down~ 🙃")
            }
            CharacterState.TRICK_SMOKE_TELEPORT -> {
                state = CharacterState.TRICK_SMOKE_TELEPORT
                showSpeechBubble("Ninja vanish! 💨")
            }
            CharacterState.EDGE_WALKING -> {
                state = CharacterState.EDGE_WALKING
                edgeProgress = rope.anchorX
                edgeDirection = if (Random.nextBoolean()) 1f else -1f
                showSpeechBubble("Strolling~ 🐾")
            }
            else -> {
                state = CharacterState.IDLE_HANGING
            }
        }
    }

    fun onDragCharacter(rawX: Float, rawY: Float) {
        if (state == CharacterState.HIDDEN || state == CharacterState.CLIMBING_UP) return

        state = CharacterState.DRAGGING_SLINGSHOT
        stateTimer = 0f

        val dx = rawX - rope.anchorX
        val dy = (rawY - rope.anchorY).coerceAtLeast(10f * density)
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val maxPull = rope.totalLengthPx * 2.1f
        val clampedDist = dist.coerceAtMost(maxPull)

        val targetX = rope.anchorX + (dx / dist) * clampedDist
        val targetY = rope.anchorY + (dy / dist) * clampedDist

        currentStretchRatio = clampedDist / rope.totalLengthPx.coerceAtLeast(1f)
        rope.pinTip(targetX, targetY)
        startAnimationLoop()
    }

    fun onReleaseCharacter(velocityX: Float, velocityY: Float) {
        if (state != CharacterState.DRAGGING_SLINGSHOT) return

        val stretch = rope.releaseSlingshot(velocityX, velocityY)
        stateTimer = 0f
        emitParticleBurst(charX, charY, 7, 0, Color.parseColor("#FFBE0B"))

        if (stretch >= 1.65f && velocityY <= 400f) {
            state = CharacterState.SLINGSHOT_LAUNCH
            showSpeechBubble("Woooosh!! 🚀")
        } else {
            state = CharacterState.TRICK_BACKFLIP
            showSpeechBubble("Boing~ ✨")
        }
    }

    fun triggerBatteryReaction(isChargingSurge: Boolean, batteryPct: Int) {
        currentBatteryPct = batteryPct
        if (state == CharacterState.HIDDEN) {
            advanceCharacterIfAutoSwap()
            rope.resetToAnchor(rope.anchorX, rope.anchorY)
            rope.deployedRatio = 0.85f
        } else {
            rope.deployedRatio = 1.0f
        }

        stateTimer = 0f
        portalScale = 1f

        if (isChargingSurge) {
            state = CharacterState.REACT_CHARGING_SURGE
            rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 35f * density, -25f * density)
            emitParticleBurst(charX, charY, 8, 0, Color.parseColor("#00E676"))
            showSpeechBubble("Charging~ ⚡$batteryPct%")
        } else {
            state = CharacterState.REACT_LOW_BATTERY
            rope.applyTipImpulse(0f, 20f * density)
            showSpeechBubble("Sleepy... 🪫$batteryPct%")
        }
        startAnimationLoop()
    }

    fun triggerMusicDance(bpm: Float) {
        activeBpm = bpm.coerceIn(85f, 132f)
        if (state == CharacterState.HIDDEN) {
            advanceCharacterIfAutoSwap()
            rope.resetToAnchor(rope.anchorX, rope.anchorY)
            rope.deployedRatio = 0.88f
        }
        state = CharacterState.DANCE_MUSIC_GROOVE
        stateTimer = 0f
        portalScale = 1f
        showSpeechBubble("Grooving~ 🎵")
        startAnimationLoop()
    }

    private fun startAnimationLoop() {
        if (!isLoopRunning) {
            visibility = VISIBLE
            isLoopRunning = true
            lastFrameNanos = System.nanoTime()
            if (!isSensorRegistered && gravitySensor != null) {
                sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
                isSensorRegistered = true
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun forceSleepNow() {
        rope.isTipPinned = false
        state = CharacterState.HIDDEN
        isLoopRunning = false
        if (isSensorRegistered) {
            sensorManager.unregisterListener(this)
            isSensorRegistered = false
        }
        Choreographer.getInstance().removeFrameCallback(this)
        onCharacterBoundsMoved(0f, 0f, false)
        visibility = GONE
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        // Extra-smooth low-pass filter on phone tilt so gravity feels fluid and weighted
        val rawTilt = -event.values[0]
        phoneTiltX = phoneTiltX * 0.92f + rawTilt * 0.08f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun doFrame(frameTimeNanos: Long) {
        if (!isLoopRunning) return

        val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.04f)
        lastFrameNanos = frameTimeNanos

        updatePhysicsAndStateMachine(dt)
        invalidate()

        if (state == CharacterState.HIDDEN) {
            forceSleepNow()
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun updatePhysicsAndStateMachine(dt: Float) {
        stateTimer += dt
        if (speechTimer > 0f) {
            speechTimer = (speechTimer - dt).coerceAtLeast(0f)
        }

        // Natural eye blinking + gentle curious gaze shifting left/right
        blinkTimer += dt
        if (blinkTimer > 4.2f) {
            isBlinking = true
            if (blinkTimer > 4.38f) {
                isBlinking = false
                blinkTimer = 0f
            }
        }

        lookChangeTimer += dt
        if (lookChangeTimer > 2.8f) {
            lookChangeTimer = 0f
            targetLookOffsetX = (Random.nextFloat() - 0.5f) * 3.2f * density
        }
        lookOffsetX += (targetLookOffsetX - lookOffsetX) * (dt * 4.5f).coerceIn(0f, 1f)

        // Apply gentle phone tilt gravity to the rope
        if (!rope.isTipPinned && abs(phoneTiltX) > 0.35f) {
            rope.applyTipImpulse(phoneTiltX * 0.65f * density * dt * 60f, 0f)
        }

        rope.step(dt)
        updateParticles(dt)

        // Compute lagging secondary follow-through from real rope velocity (for tails, scarves, ears)
        val targetFollowX = (-rope.tipVelocityX() * 0.045f).coerceIn(-22f * density, 22f * density)
        val targetFollowY = (-rope.tipVelocityY() * 0.035f).coerceIn(-16f * density, 16f * density)
        followThroughX += (targetFollowX - followThroughX) * (dt * 7.5f).coerceIn(0f, 1f)
        followThroughY += (targetFollowY - followThroughY) * (dt * 7.5f).coerceIn(0f, 1f)

        val naturalRopeTilt = Math.toDegrees(rope.tipAngleRad().toDouble()).toFloat() * -0.55f

        when (state) {
            CharacterState.DESCENDING -> {
                // Studio 2-Stage Rappel (2.6s):
                // 0.0 - 0.22: Peek head out of punch hole curiously
                // 0.22 - 1.0: Smooth slow-in-slow-out glide down the cord
                val duration = 2.6f
                val p = (stateTimer / duration).coerceIn(0f, 1f)

                if (p < 0.22f) {
                    val peekP = (p / 0.22f).coerceIn(0f, 1f)
                    portalScale = easeOutCubic(peekP) * 0.88f
                    rope.deployedRatio = 0.04f
                    charX = rope.anchorX
                    charY = rope.anchorY + 10f * density * portalScale
                    charRotationDeg = sin(peekP * PI.toFloat()) * 8f
                    scaleX = 1f
                    scaleY = 1f
                } else {
                    val glideP = ((p - 0.22f) / 0.78f).coerceIn(0f, 1f)
                    portalScale = 0.88f + 0.12f * glideP
                    rope.deployedRatio = easeOutSoftBack(glideP)

                    charX = rope.tipX()
                    charY = rope.tipY()
                    charRotationDeg = naturalRopeTilt

                    // Gentle aerodynamic stretch mid-glide, soft cushion at bottom
                    val midGlide = sin(glideP * PI.toFloat())
                    scaleX = 1f - 0.06f * midGlide
                    scaleY = 1f + 0.06f * midGlide
                }

                if (p >= 1f) {
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.IDLE_HANGING -> {
                ropePaint.strokeWidth = ropeThicknessPx
                rope.deployedRatio = 1f
                portalScale = 1f

                // Calm, slow pendulum breeze (period ~5.2 seconds)
                rope.applyTipImpulse(sin(stateTimer * 1.2f) * 0.55f * density * dt * 60f, 0f)

                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = naturalRopeTilt + sin(stateTimer * 1.2f) * 3f

                // Slow, peaceful breathing cycle
                val breath = sin(stateTimer * 2.2f) * 0.03f
                scaleX = 1f - breath
                scaleY = 1f + breath

                if (stateTimer >= idleDurationTarget) {
                    if (Random.nextFloat() < 0.55f) {
                        triggerSingleTapTrick(CompanionRepository.config.value.allowEdgeWalk)
                        idleDurationTarget = 9f
                    } else {
                        retreatToPunchHole()
                    }
                }
            }

            CharacterState.DRAGGING_SLINGSHOT -> {
                charX = rope.tipX()
                charY = rope.tipY()

                val dx = rope.anchorX - charX
                val dy = rope.anchorY - charY
                charRotationDeg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()

                val stretchFactor = (currentStretchRatio - 1f).coerceIn(0f, 1.2f)
                scaleX = 1f - stretchFactor * 0.18f
                scaleY = 1f + stretchFactor * 0.22f
                ropePaint.strokeWidth = (ropeThicknessPx / currentStretchRatio.coerceIn(0.8f, 1.6f))
            }

            CharacterState.SLINGSHOT_LAUNCH -> {
                ropePaint.strokeWidth = ropeThicknessPx
                val duration = 1.25f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                val eased = easeInOutCubic(p)

                rope.deployedRatio = (1f - eased).coerceAtLeast(0.02f)
                portalScale = (1f - eased * eased).coerceIn(0f, 1f)
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = eased * 360f
                scaleX = portalScale * 0.92f
                scaleY = portalScale * 1.12f

                if (p >= 1f) {
                    state = CharacterState.HIDDEN
                }
            }

            CharacterState.TRICK_BACKFLIP -> {
                // Studio 3-Phase Backflip (2.2s):
                // Phase 1 (0.00 - 0.24): Anticipation crouch down & pull rope taut
                // Phase 2 (0.24 - 0.82): Graceful high arc flip with slow-mo apex
                // Phase 3 (0.82 - 1.00): Soft landing cushion squash & recovery
                ropePaint.strokeWidth = ropeThicknessPx
                val duration = 2.2f
                val p = (stateTimer / duration).coerceIn(0f, 1f)

                if (p < 0.24f) {
                    val crouchP = easeInOutSine(p / 0.24f)
                    rope.deployedRatio = 1f + 0.08f * crouchP
                    charX = rope.tipX()
                    charY = rope.tipY() + crouchP * 8f * density
                    charRotationDeg = naturalRopeTilt - crouchP * 14f
                    scaleX = 1f + 0.14f * crouchP
                    scaleY = 1f - 0.14f * crouchP
                } else if (p < 0.82f) {
                    val airP = ((p - 0.24f) / 0.58f).coerceIn(0f, 1f)
                    rope.deployedRatio = 1f - 0.06f * sin(airP * PI.toFloat())
                    val jumpArc = sin(airP * PI.toFloat()) * 46f * density
                    charX = rope.tipX()
                    charY = rope.tipY() - jumpArc
                    // Smooth cubic rotation so the flip slows down gracefully at the peak
                    charRotationDeg = easeInOutCubic(airP) * 360f
                    val stretch = sin(airP * PI.toFloat()) * 0.08f
                    scaleX = 1f - stretch
                    scaleY = 1f + stretch
                } else {
                    val landP = ((p - 0.82f) / 0.18f).coerceIn(0f, 1f)
                    val cushion = sin(landP * PI.toFloat()) * 0.12f
                    rope.deployedRatio = 1f + cushion * 0.35f
                    charX = rope.tipX()
                    charY = rope.tipY()
                    charRotationDeg = naturalRopeTilt
                    scaleX = 1f + cushion
                    scaleY = 1f - cushion
                }

                if (p >= 1f) {
                    rope.deployedRatio = 1f
                    rope.applyTipImpulse(0f, 16f * density)
                    emitParticleBurst(charX, charY, 5, 1, Color.parseColor("#FF3366"))
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_BUNGEE_JUMP -> {
                // Studio Weighted Bungee (2.9s): Anticipation lift -> Deep slow elastic dip -> Gentle settle
                val duration = 2.9f
                val p = (stateTimer / duration).coerceIn(0f, 1f)

                if (p < 0.18f) {
                    val prep = easeInOutSine(p / 0.18f)
                    rope.deployedRatio = 1f - 0.12f * prep
                    charX = rope.tipX()
                    charY = rope.tipY()
                    scaleX = 1f + 0.08f * prep
                    scaleY = 1f - 0.08f * prep
                } else {
                    val diveP = ((p - 0.18f) / 0.82f).coerceIn(0f, 1f)
                    // 1.5 slow, heavy damped oscillations
                    val elasticWave = sin(diveP * PI.toFloat() * 2.5f) * (1f - diveP).pow(1.4f)
                    rope.deployedRatio = 1f + 0.42f * elasticWave
                    charX = rope.tipX()
                    charY = rope.tipY()
                    charRotationDeg = naturalRopeTilt + sin(diveP * PI.toFloat() * 2f) * 10f
                    scaleX = (1f - elasticWave * 0.14f).coerceIn(0.86f, 1.14f)
                    scaleY = (1f + elasticWave * 0.14f).coerceIn(0.86f, 1.16f)
                }

                if (p >= 1f) {
                    rope.deployedRatio = 1f
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_SWING_WAVE -> {
                val duration = 3.4f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.applyTipImpulse(cos(p * PI.toFloat() * 2f) * 2.8f * density * dt * 60f, 0f)
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = naturalRopeTilt * 1.15f
                scaleX = 1f
                scaleY = 1f

                if (p >= 1f) {
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_TARZAN_SWING -> {
                val duration = 4.2f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.applyTipImpulse(cos(p * PI.toFloat() * 2f) * 5.2f * density * dt * 60f, 0f)
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = naturalRopeTilt * 1.25f

                if (Random.nextFloat() < 0.18f) {
                    emitParticleBurst(charX, charY, 1, 0, Color.parseColor("#FFBE0B"))
                }

                if (p >= 1f) {
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_UPSIDE_DOWN -> {
                val duration = 5.2f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.applyTipImpulse(sin(stateTimer * 1.5f) * 1.1f * density * dt * 60f, 0f)
                charX = rope.tipX()
                charY = rope.tipY()

                // Smooth 0.9s transition into 180° upside-down hang, and smooth flip back at the end
                val flipIn = easeInOutCubic((stateTimer / 0.9f).coerceIn(0f, 1f))
                val flipOut = easeInOutCubic(((duration - stateTimer) / 0.9f).coerceIn(0f, 1f))
                val flipRatio = min(flipIn, flipOut)

                charRotationDeg = 180f * flipRatio + naturalRopeTilt
                scaleX = 1f
                scaleY = 1f

                if (p >= 1f) {
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_SMOKE_TELEPORT -> {
                // Anticipation hand-sign pause (0.45s) -> Smoke poof vanish (0.65s)
                val duration = 1.1f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                if (p < 0.4f) {
                    val prep = p / 0.4f
                    charX = rope.tipX()
                    charY = rope.tipY()
                    scaleX = 1f + 0.12f * prep
                    scaleY = 1f - 0.12f * prep
                } else {
                    if (stateTimer - dt < 0.4f * duration) {
                        emitParticleBurst(charX, charY, 12, 2, Color.parseColor("#E0E6ED"))
                    }
                    val vanishP = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
                    portalScale = (1f - easeOutCubic(vanishP)).coerceIn(0f, 1f)
                    rope.deployedRatio = (1f - easeInOutCubic(vanishP)).coerceAtLeast(0.02f)
                    charX = rope.tipX()
                    charY = rope.tipY()
                }

                if (p >= 1f) {
                    state = CharacterState.HIDDEN
                }
            }

            CharacterState.EDGE_WALKING -> {
                // Unhurried, jaunty studio stroll along the bezel (8.4s total)
                rope.deployedRatio = (rope.deployedRatio - dt * 1.4f).coerceAtLeast(0.02f)
                val speed = 115f * density
                val w = width.toFloat().coerceAtLeast(1080f)
                val h = height.toFloat().coerceAtLeast(2400f)
                val margin = 24f * density

                edgeProgress += speed * dt * edgeDirection

                val totalRunTime = 8.4f
                if (stateTimer < 2.6f) {
                    charX = edgeProgress.coerceIn(margin, w - margin)
                    charY = rope.anchorY + 14f * density
                    charRotationDeg = edgeDirection * 6f // Slight forward lean while walking
                    if (charX <= margin + 2f || charX >= w - margin - 2f) {
                        stateTimer = 2.6f
                    }
                } else if (stateTimer < 6.0f) {
                    val sidePhase = easeInOutSine((stateTimer - 2.6f) / 3.4f)
                    val verticalDrop = sin(sidePhase * PI.toFloat()) * (h * 0.32f)
                    val onRightEdge = edgeDirection > 0
                    charX = if (onRightEdge) w - margin else margin
                    charY = rope.anchorY + verticalDrop
                    charRotationDeg = if (onRightEdge) -90f else 90f
                } else {
                    val returnPhase = easeInOutCubic(((stateTimer - 6.0f) / 2.4f).coerceIn(0f, 1f))
                    val startX = if (edgeDirection > 0) w - margin else margin
                    charX = startX + (rope.anchorX - startX) * returnPhase
                    charY = rope.anchorY + 14f * density
                    charRotationDeg = -edgeDirection * 6f
                }

                // Soft, bouncy studio trot cadence
                val stepBounce = abs(sin(stateTimer * 7.5f))
                scaleX = 1f + 0.05f * (0.5f - stepBounce)
                scaleY = 1f - 0.05f * (0.5f - stepBounce)

                if (stateTimer >= totalRunTime) {
                    state = CharacterState.CLIMBING_UP
                    stateTimer = 0f
                }
            }

            CharacterState.REACT_CHARGING_SURGE -> {
                val duration = 5.0f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.deployedRatio = 1f

                // Smooth levitation aura instead of frantic shaking
                charX = rope.tipX()
                charY = rope.tipY() - sin(p * PI.toFloat()) * 16f * density
                charRotationDeg = naturalRopeTilt + sin(stateTimer * 4f) * 5f

                val pulse = sin(stateTimer * 6f) * 0.05f
                scaleX = 1f + pulse
                scaleY = 1f - pulse

                if (p >= 1f) {
                    retreatToPunchHole()
                }
            }

            CharacterState.REACT_LOW_BATTERY -> {
                val duration = 5.5f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.deployedRatio = 1.10f

                rope.applyTipImpulse(sin(stateTimer * 1.0f) * 0.6f * density * dt * 60f, 0f)
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = 14f + sin(stateTimer * 1.5f) * 6f

                scaleX = 1.04f + sin(stateTimer * 1.6f) * 0.03f
                scaleY = 0.94f - sin(stateTimer * 1.6f) * 0.03f

                if (p >= 1f) {
                    retreatToPunchHole()
                }
            }

            CharacterState.REACT_NOTIFICATION -> {
                val duration = 5.0f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.deployedRatio = 0.78f

                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = naturalRopeTilt + sin(stateTimer * 5f) * (1f - p) * 10f

                val pop = sin(stateTimer * 5f) * (1f - p) * 0.07f
                scaleX = 1f + pop
                scaleY = 1f - pop

                if (p >= 1f) {
                    cutoutRingPaint.color = Color.parseColor("#00E5FF")
                    retreatToPunchHole()
                }
            }

            CharacterState.DANCE_MUSIC_GROOVE -> {
                val duration = 16.0f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.deployedRatio = 0.92f

                // Smooth half-tempo groove so the character bops naturally to the beat
                val beatFreq = (activeBpm / 60f) * PI.toFloat()
                val beatWave = sin(stateTimer * beatFreq)
                val swayWave = cos(stateTimer * beatFreq * 0.5f)

                rope.applyTipImpulse(
                    vx = swayWave * 1.8f * density * dt * 60f,
                    vy = abs(beatWave) * 1.4f * density * dt * 60f
                )

                charX = rope.tipX()
                charY = rope.tipY() - abs(beatWave) * 7f * density
                charRotationDeg = swayWave * 14f

                scaleX = 1f + beatWave * 0.06f
                scaleY = 1f - beatWave * 0.06f

                if (p >= 1f || !CompanionEventBus.isMusicCurrentlyPlaying) {
                    retreatToPunchHole()
                }
            }

            CharacterState.CLIMBING_UP -> {
                // Studio Hand-Over-Hand Climb (2.1s):
                // Character pulls itself up in 3 rhythmic climbing steps before slipping into the cutout
                ropePaint.strokeWidth = ropeThicknessPx
                val duration = 2.1f
                val p = (stateTimer / duration).coerceIn(0f, 1f)

                // Stepped hand-over-hand progress curve
                val climbPull = p + 0.045f * sin(p * PI.toFloat() * 6f)
                val easedClimb = easeInOutCubic(climbPull.coerceIn(0f, 1f))

                rope.deployedRatio = (1f - easedClimb).coerceAtLeast(0.01f)
                portalScale = if (p < 0.75f) 1f else ((1f - p) / 0.25f).coerceIn(0f, 1f)

                charX = rope.tipX()
                charY = rope.tipY()
                // Gentle side-to-side shoulder tilt with each hand pull
                charRotationDeg = sin(p * PI.toFloat() * 6f) * 9f * portalScale

                val pullStretch = sin(p * PI.toFloat() * 6f) * 0.05f
                scaleX = (1f - pullStretch) * portalScale
                scaleY = (1f + pullStretch) * portalScale

                if (p >= 1f) {
                    state = CharacterState.HIDDEN
                }
            }

            CharacterState.HIDDEN -> Unit
        }

        // Smoothly interpolate angular rotation so the character never snaps between states
        val rotDiff = charRotationDeg - smoothRotationDeg
        smoothRotationDeg += rotDiff * (dt * 14f).coerceIn(0f, 1f)

        lottieRig?.setAnimationState(state)
        lottieRig?.update(dt)

        onCharacterBoundsMoved(charX, charY, state != CharacterState.HIDDEN)
    }

    private fun updateParticles(dt: Float) {
        for (i in 0 until maxParticles) {
            if (partActive[i]) {
                partLife[i] -= dt
                if (partLife[i] <= 0f) {
                    partActive[i] = false
                } else {
                    partX[i] += partVx[i] * dt
                    partY[i] += partVy[i] * dt
                    partVx[i] *= 0.97f
                    partVy[i] += 42f * density * dt // Feather-light studio float
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == CharacterState.HIDDEN) return

        // 1. Cutout Portal Glow Ring
        if (state == CharacterState.DESCENDING || state == CharacterState.CLIMBING_UP) {
            cutoutRingPaint.alpha = (sin(portalScale * PI.toFloat()) * 190).toInt().coerceIn(0, 210)
            canvas.drawCircle(rope.anchorX, rope.anchorY, 19f * density, cutoutRingPaint)
        }

        // 2. Slingshot Aim Vector
        if (state == CharacterState.DRAGGING_SLINGSHOT && currentStretchRatio > 1.15f) {
            canvas.drawLine(charX, charY, rope.anchorX, rope.anchorY, tensionIndicatorPaint)
        }

        // 3. Studio Cel-Shaded Braided Rope + Knot Ring
        if (rope.deployedRatio > 0.04f) {
            ropePath.reset()
            ropePath.moveTo(rope.x[0], rope.y[0])
            for (i in 1 until rope.nodeCount - 1) {
                val midX = (rope.x[i] + rope.x[i + 1]) * 0.5f
                val midY = (rope.y[i] + rope.y[i + 1]) * 0.5f
                ropePath.quadTo(rope.x[i], rope.y[i], midX, midY)
            }
            val last = rope.nodeCount - 1
            ropePath.lineTo(rope.x[last], rope.y[last])

            canvas.drawPath(ropePath, ropeShadowPaint)
            canvas.drawPath(ropePath, ropeOutlinePaint)
            canvas.drawPath(ropePath, ropePaint)
            canvas.drawPath(ropePath, ropeBraidPaint)

            val tipX = rope.x[last]
            val tipY = rope.y[last]
            canvas.drawCircle(tipX, tipY, ropeThicknessPx * 1.15f, accentPaint)
            canvas.drawCircle(tipX, tipY, ropeThicknessPx * 1.15f, cartoonOutlinePaint)
        }

        // 4. Character Rig
        val rig = lottieRig
        if (rig != null && rig.isLoaded) {
            rig.drawAtRopeTip(
                canvas = canvas,
                charX = charX,
                charY = charY,
                rotationDeg = smoothRotationDeg,
                scaleX = scaleX * portalScale,
                scaleY = scaleY * portalScale
            )
        } else {
            drawStudioCartoonCharacter(canvas)
        }

        // 5. Studio Particles
        drawParticles(canvas)

        // 6. Cartoon Speech Bubble Callout
        if (speechTimer > 0f && portalScale > 0.4f) {
            drawCartoonSpeechBubble(canvas)
        }

        // 7. Battery / Music VFX Overlays
        drawStateVfxOverlays(canvas)
    }

    private fun drawParticles(canvas: Canvas) {
        for (i in 0 until maxParticles) {
            if (!partActive[i]) continue
            val progress = (partLife[i] / partMaxLife[i]).coerceIn(0f, 1f)
            val smoothAlpha = sin(progress * PI.toFloat())
            particlePaint.color = partColor[i]
            particlePaint.alpha = (smoothAlpha * 235).toInt().coerceIn(0, 255)

            val px = partX[i]
            val py = partY[i]
            val sz = partSize[i] * (0.45f + 0.55f * smoothAlpha)

            when (partType[i]) {
                0 -> {
                    starPath.reset()
                    starPath.moveTo(px, py - sz)
                    starPath.quadTo(px, py, px + sz, py)
                    starPath.quadTo(px, py, px, py + sz)
                    starPath.quadTo(px, py, px - sz, py)
                    starPath.quadTo(px, py, px, py - sz)
                    starPath.close()
                    canvas.drawPath(starPath, particlePaint)
                }
                1 -> {
                    canvas.drawCircle(px, py, sz * 0.75f, particlePaint)
                }
                else -> {
                    val smokeRadius = partSize[i] * (1.5f - progress * 0.5f)
                    canvas.drawCircle(px, py, smokeRadius, particlePaint)
                    canvas.drawCircle(px, py, smokeRadius, cartoonOutlinePaint)
                }
            }
        }
    }

    private fun drawCartoonSpeechBubble(canvas: Canvas) {
        val popScale = easeOutSoftBack(((speechDuration - speechTimer) / 0.38f).coerceIn(0f, 1f))
        val fadeOut = (speechTimer / 0.45f).coerceIn(0f, 1f)
        val finalScale = popScale * fadeOut

        val textWidth = bubbleTextPaint.measureText(speechText)
        val padH = 12f * density
        val boxW = textWidth + padH * 2f
        val boxH = 25f * density

        val placeLeft = charX > (width * 0.62f)
        val offsetX = if (placeLeft) -(boxW * 0.65f + 22f * density) else (boxW * 0.65f + 22f * density)
        val bx = charX + offsetX
        val by = (charY - 22f * density + sin(stateTimer * 2.5f) * 2.5f * density).coerceAtLeast(36f * density)

        canvas.save()
        canvas.translate(bx, by)
        canvas.scale(finalScale, finalScale)

        bubbleRect.set(-boxW / 2f, -boxH / 2f, boxW / 2f, boxH / 2f)
        canvas.drawRoundRect(bubbleRect, 11f * density, 11f * density, bubbleFillPaint)
        canvas.drawRoundRect(bubbleRect, 11f * density, 11f * density, cartoonOutlinePaint)

        canvas.drawText(speechText, 0f, 4f * density, bubbleTextPaint)
        canvas.restore()
    }

    private fun drawStudioCartoonCharacter(canvas: Canvas) {
        canvas.save()
        canvas.translate(charX, charY)
        canvas.rotate(smoothRotationDeg)
        canvas.scale(scaleX * portalScale, scaleY * portalScale)

        val r = 19.5f * density

        when (currentSkin) {
            CharacterSkin.MOCHI_CAT -> drawMochiCat(canvas, r)
            CharacterSkin.SHADOW_NINJA -> drawShadowNinja(canvas, r)
            CharacterSkin.ASTRO_BOT -> drawAstroBot(canvas, r)
            CharacterSkin.KITSUNE_SPIRIT -> drawKitsuneSpirit(canvas, r)
        }

        canvas.restore()
    }

    private fun drawMochiCat(canvas: Canvas, r: Float) {
        // 1. Inertia-Driven S-Curve Cat Tail (Trails smoothly behind swing direction)
        val gentleWag = sin(stateTimer * 2.6f) * 9f * density + followThroughX * 0.85f
        val verticalLag = followThroughY * 0.6f
        scarfOrTailPath.reset()
        scarfOrTailPath.moveTo(0f, r * 1.15f)
        scarfOrTailPath.cubicTo(
            r * 1.05f + followThroughX * 0.4f, r * 1.35f + verticalLag,
            r * 1.50f + gentleWag, r * 0.45f + gentleWag * 0.5f,
            r * 1.90f + gentleWag * 1.1f, r * 0.12f - gentleWag * 0.35f
        )
        tailStrokePaint.strokeWidth = 8.5f * density
        tailStrokePaint.color = Color.parseColor("#14161F")
        canvas.drawPath(scarfOrTailPath, tailStrokePaint)
        tailStrokePaint.strokeWidth = 5.5f * density
        tailStrokePaint.color = currentSkin.accentColor
        canvas.drawPath(scarfOrTailPath, tailStrokePaint)

        // 2. Plump Chibi Cat Body + Cream Belly Patch
        bodyRect.set(-r * 0.72f, r * 0.4f, r * 0.72f, r * 1.55f)
        canvas.drawRoundRect(bodyRect, r * 0.55f, r * 0.55f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.55f, r * 0.55f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.42f, r * 0.62f, r * 0.42f, r * 1.38f)
        canvas.drawOval(bodyRect, secondaryPaint)

        // Soft Chin Depth Shadow on Upper Chest
        bodyRect.set(-r * 0.55f, r * 0.52f, r * 0.55f, r * 0.82f)
        canvas.drawOval(bodyRect, chinShadowPaint)

        // 3. Cute Dangling Hind Paws (Lag softly with vertical inertia)
        val pawSwing = sin(stateTimer * 2.8f) * 2.2f * density + followThroughY * 0.25f
        canvas.drawCircle(-r * 0.38f + followThroughX * 0.15f, r * 1.58f + pawSwing, r * 0.22f, bodyPaint)
        canvas.drawCircle(-r * 0.38f + followThroughX * 0.15f, r * 1.58f + pawSwing, r * 0.22f, cartoonOutlinePaint)
        canvas.drawCircle(r * 0.38f + followThroughX * 0.15f, r * 1.58f - pawSwing, r * 0.22f, bodyPaint)
        canvas.drawCircle(r * 0.38f + followThroughX * 0.15f, r * 1.58f - pawSwing, r * 0.22f, cartoonOutlinePaint)

        // 4. Pointed Cat Ears with Gentle Ear-Tip Follow-Through
        val earTwitch = followThroughX * 0.18f
        for (side in listOf(-1f, 1f)) {
            earPath.reset()
            earPath.moveTo(side * r * 0.25f, -r * 0.35f)
            earPath.lineTo(side * r * 0.92f + earTwitch, -r * 0.95f + abs(earTwitch) * 0.2f)
            earPath.lineTo(side * r * 0.95f, r * 0.05f)
            earPath.close()
            canvas.drawPath(earPath, bodyPaint)
            canvas.drawPath(earPath, cartoonOutlinePaint)

            earPath.reset()
            earPath.moveTo(side * r * 0.38f, -r * 0.28f)
            earPath.lineTo(side * r * 0.80f + earTwitch * 0.8f, -r * 0.75f)
            earPath.lineTo(side * r * 0.82f, -r * 0.05f)
            earPath.close()
            canvas.drawPath(earPath, secondaryPaint)
        }

        // 5. Rounded Cat Head + Calico Ginger Forehead Spot + 3D Rim Highlight
        bodyRect.set(-r, -r * 0.48f, r, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.75f, r * 0.75f, bodyPaint)

        bodyRect.set(r * 0.1f, -r * 0.45f, r * 0.85f, r * 0.05f)
        canvas.drawArc(bodyRect, 200f, 140f, true, accentPaint)

        // Studio 2.5D Top-Left Specular Rim Light
        bodyRect.set(-r * 0.86f, -r * 0.38f, r * 0.2f, r * 0.35f)
        canvas.drawArc(bodyRect, 195f, 75f, false, rimLightPaint)

        bodyRect.set(-r, -r * 0.48f, r, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.75f, r * 0.75f, cartoonOutlinePaint)

        // 6. Red Collar + Golden Bell
        bodyRect.set(-r * 0.62f, r * 0.58f, r * 0.62f, r * 0.76f)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, accentPaint)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, cartoonOutlinePaint)

        // 7. Studio Anime Eyes + Whiskers
        drawStudioEyes(canvas, r, yCenter = r * 0.12f, isCatMouth = true)

        val whiskerWidth = cartoonOutlinePaint.strokeWidth
        cartoonOutlinePaint.strokeWidth = 1.6f * density
        for (side in listOf(-1f, 1f)) {
            canvas.drawLine(side * r * 0.72f, r * 0.18f, side * r * 1.18f, r * 0.10f + followThroughY * 0.1f, cartoonOutlinePaint)
            canvas.drawLine(side * r * 0.72f, r * 0.28f, side * r * 1.20f, r * 0.28f + followThroughY * 0.1f, cartoonOutlinePaint)
        }
        cartoonOutlinePaint.strokeWidth = whiskerWidth

        // 8. Front Paws Reaching Up to Grip the Rope Knot
        val climbHandOffset = if (state == CharacterState.CLIMBING_UP) sin(stateTimer * 9f) * 3.5f * density else 0f
        canvas.drawCircle(-r * 0.20f, -r * 0.15f + climbHandOffset, r * 0.22f, bodyPaint)
        canvas.drawCircle(-r * 0.20f, -r * 0.15f + climbHandOffset, r * 0.22f, cartoonOutlinePaint)
        canvas.drawCircle(-r * 0.20f, -r * 0.15f + climbHandOffset, r * 0.10f, secondaryPaint)

        canvas.drawCircle(r * 0.20f, -r * 0.15f - climbHandOffset, r * 0.22f, bodyPaint)
        canvas.drawCircle(r * 0.20f, -r * 0.15f - climbHandOffset, r * 0.22f, cartoonOutlinePaint)
        canvas.drawCircle(r * 0.20f, -r * 0.15f - climbHandOffset, r * 0.10f, secondaryPaint)
    }

    private fun drawShadowNinja(canvas: Canvas, r: Float) {
        // 1. Inertia-Driven Silk Ninja Scarf (Billows smoothly with swing velocity)
        val wave1 = sin(stateTimer * 3.2f) * 9f * density + followThroughX * 0.9f
        val wave2 = cos(stateTimer * 3.2f) * 12f * density + followThroughY * 0.8f
        scarfOrTailPath.reset()
        scarfOrTailPath.moveTo(0f, r * 0.45f)
        scarfOrTailPath.cubicTo(
            -r * 1.25f + followThroughX * 0.5f, r * 0.2f + wave1 * 0.4f,
            -r * 2.0f + followThroughX * 0.9f, r * 0.45f + wave2,
            -r * 2.75f + followThroughX * 1.3f, r * 0.80f + wave1
        )
        scarfOrTailPath.lineTo(-r * 2.35f + followThroughX * 1.1f, r * 1.18f + wave1)
        scarfOrTailPath.close()
        canvas.drawPath(scarfOrTailPath, accentPaint)
        canvas.drawPath(scarfOrTailPath, cartoonOutlinePaint)

        // 2. Diagonal Katana Hilt Strapped Behind Back
        canvas.save()
        canvas.rotate(32f)
        bodyRect.set(-r * 0.15f, -r * 0.95f, r * 0.15f, r * 0.6f)
        canvas.drawRoundRect(bodyRect, 3f * density, 3f * density, accentPaint)
        canvas.drawRoundRect(bodyRect, 3f * density, 3f * density, cartoonOutlinePaint)
        canvas.restore()

        // 3. Chibi Ninja Torso
        bodyRect.set(-r * 0.65f, r * 0.45f, r * 0.65f, r * 1.48f)
        canvas.drawRoundRect(bodyRect, r * 0.45f, r * 0.45f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.45f, r * 0.45f, cartoonOutlinePaint)

        // 4. Rounded Ninja Hood Head + 2.5D Rim Highlight
        bodyRect.set(-r * 0.95f, -r * 0.42f, r * 0.95f, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, bodyPaint)

        bodyRect.set(-r * 0.82f, -r * 0.34f, r * 0.2f, r * 0.35f)
        canvas.drawArc(bodyRect, 195f, 75f, false, rimLightPaint)

        bodyRect.set(-r * 0.95f, -r * 0.42f, r * 0.95f, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, cartoonOutlinePaint)

        // 5. Warm Skin-Tone Face Slit Opening
        bodyRect.set(-r * 0.72f, -r * 0.04f, r * 0.72f, r * 0.36f)
        canvas.drawRoundRect(bodyRect, r * 0.22f, r * 0.22f, secondaryPaint)
        canvas.drawRoundRect(bodyRect, r * 0.22f, r * 0.22f, cartoonOutlinePaint)

        // 6. Crimson Headband + Metallic Forehead Plate
        bodyRect.set(-r * 0.96f, -r * 0.26f, r * 0.96f, -r * 0.02f)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, accentPaint)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, cartoonOutlinePaint)

        bodyRect.set(-r * 0.35f, -r * 0.22f, r * 0.35f, -r * 0.06f)
        canvas.drawRoundRect(bodyRect, 2f * density, 2f * density, highlightPaint)

        // 7. Expressive Glowing Ninja Eyes
        drawStudioEyes(canvas, r, yCenter = r * 0.16f, isCatMouth = false)

        // 8. Ninja Hands Gripping the Rope Knot
        val climbHandOffset = if (state == CharacterState.CLIMBING_UP) sin(stateTimer * 9f) * 3.5f * density else 0f
        canvas.drawCircle(-r * 0.18f, -r * 0.14f + climbHandOffset, r * 0.19f, bodyPaint)
        canvas.drawCircle(-r * 0.18f, -r * 0.14f + climbHandOffset, r * 0.19f, cartoonOutlinePaint)
        canvas.drawCircle(r * 0.18f, -r * 0.14f - climbHandOffset, r * 0.19f, bodyPaint)
        canvas.drawCircle(r * 0.18f, -r * 0.14f - climbHandOffset, r * 0.19f, cartoonOutlinePaint)
    }

    private fun drawAstroBot(canvas: Canvas, r: Float) {
        // 1. Soft Breathing Jetpack Thruster Flame Under Bot
        val flameLen = (0.32f + abs(sin(stateTimer * 6.5f)) * 0.32f) * r
        earPath.reset()
        earPath.moveTo(-r * 0.30f, r * 1.42f)
        earPath.lineTo(followThroughX * 0.25f, r * 1.42f + flameLen)
        earPath.lineTo(r * 0.30f, r * 1.42f)
        earPath.close()
        canvas.drawPath(earPath, accentPaint)

        // 2. Glossy Ceramic Bot Body Pod
        bodyRect.set(-r * 0.68f, r * 0.50f, r * 0.68f, r * 1.45f)
        canvas.drawRoundRect(bodyRect, r * 0.5f, r * 0.5f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.5f, r * 0.5f, cartoonOutlinePaint)

        canvas.drawCircle(0f, r * 0.98f, r * 0.20f, eyePaint)
        canvas.drawCircle(0f, r * 0.98f, r * 0.20f, cartoonOutlinePaint)

        // 3. Top Radar Antenna (Bends slightly with momentum)
        val antTipX = followThroughX * 0.3f
        canvas.drawLine(0f, -r * 0.45f, antTipX, -r * 0.82f, cartoonOutlinePaint)
        canvas.drawCircle(antTipX, -r * 0.88f, r * 0.16f, accentPaint)
        canvas.drawCircle(antTipX, -r * 0.88f, r * 0.16f, cartoonOutlinePaint)

        // 4. Rounded Astro Helmet + Side Ear Cups
        bodyRect.set(-r * 1.12f, -r * 0.05f, r * 1.12f, r * 0.35f)
        canvas.drawRoundRect(bodyRect, r * 0.2f, r * 0.2f, accentPaint)
        canvas.drawRoundRect(bodyRect, r * 0.2f, r * 0.2f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.98f, -r * 0.46f, r * 0.98f, r * 0.76f)
        canvas.drawRoundRect(bodyRect, r * 0.68f, r * 0.68f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.68f, r * 0.68f, cartoonOutlinePaint)

        // 5. Dark Curved OLED Glass Visor
        bodyRect.set(-r * 0.78f, -r * 0.22f, r * 0.78f, r * 0.52f)
        canvas.drawRoundRect(bodyRect, r * 0.42f, r * 0.42f, secondaryPaint)
        canvas.drawRoundRect(bodyRect, r * 0.42f, r * 0.42f, cartoonOutlinePaint)

        canvas.drawCircle(-r * 0.52f, -r * 0.06f, r * 0.08f, highlightPaint)

        // 6. Glowing Digital Visor Eyes
        drawStudioEyes(canvas, r, yCenter = r * 0.15f, isCatMouth = false)

        // 7. Clamp Hands Gripping the Rope Knot
        val climbHandOffset = if (state == CharacterState.CLIMBING_UP) sin(stateTimer * 9f) * 3.5f * density else 0f
        canvas.drawCircle(-r * 0.18f, -r * 0.14f + climbHandOffset, r * 0.18f, accentPaint)
        canvas.drawCircle(-r * 0.18f, -r * 0.14f + climbHandOffset, r * 0.18f, cartoonOutlinePaint)
        canvas.drawCircle(r * 0.18f, -r * 0.14f - climbHandOffset, r * 0.18f, accentPaint)
        canvas.drawCircle(r * 0.18f, -r * 0.14f - climbHandOffset, r * 0.18f, cartoonOutlinePaint)
    }

    private fun drawKitsuneSpirit(canvas: Canvas, r: Float) {
        // 1. Giant Fluffy Fox Tail with Graceful Inertia Follow-Through
        val sway = sin(stateTimer * 2.2f) * 9f * density + followThroughX * 0.9f
        scarfOrTailPath.reset()
        scarfOrTailPath.moveTo(0f, r * 1.1f)
        scarfOrTailPath.cubicTo(
            -r * 1.45f + followThroughX * 0.5f, r * 1.4f + followThroughY * 0.4f,
            -r * 1.95f + sway, r * 0.3f + sway * 0.6f,
            -r * 1.25f + sway * 1.1f, -r * 0.2f + sway * 0.5f
        )
        scarfOrTailPath.cubicTo(
            -r * 0.78f + sway * 0.6f, r * 0.1f + sway * 0.3f,
            -r * 0.58f, r * 0.8f,
            0f, r * 1.1f
        )
        scarfOrTailPath.close()
        canvas.drawPath(scarfOrTailPath, bodyPaint)
        canvas.drawPath(scarfOrTailPath, cartoonOutlinePaint)

        // 2. Fox Body + Cream Chest Fur
        bodyRect.set(-r * 0.68f, r * 0.42f, r * 0.68f, r * 1.50f)
        canvas.drawRoundRect(bodyRect, r * 0.52f, r * 0.52f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.52f, r * 0.52f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.40f, r * 0.58f, r * 0.40f, r * 1.32f)
        canvas.drawOval(bodyRect, secondaryPaint)

        // 3. Oversized Pointed Kitsune Ears with Inertia Lag
        val earLag = followThroughX * 0.22f
        for (side in listOf(-1f, 1f)) {
            earPath.reset()
            earPath.moveTo(side * r * 0.20f, -r * 0.35f)
            earPath.lineTo(side * r * 1.08f + earLag, -r * 1.12f)
            earPath.lineTo(side * r * 0.95f, r * 0.08f)
            earPath.close()
            canvas.drawPath(earPath, bodyPaint)
            canvas.drawPath(earPath, cartoonOutlinePaint)

            earPath.reset()
            earPath.moveTo(side * r * 0.35f, -r * 0.28f)
            earPath.lineTo(side * r * 0.92f + earLag * 0.8f, -r * 0.88f)
            earPath.lineTo(side * r * 0.82f, -r * 0.02f)
            earPath.close()
            canvas.drawPath(earPath, secondaryPaint)
        }

        // 4. Fox Head + 2.5D Rim Highlight
        bodyRect.set(-r * 0.96f, -r * 0.46f, r * 0.96f, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, bodyPaint)

        bodyRect.set(-r * 0.82f, -r * 0.36f, r * 0.2f, r * 0.35f)
        canvas.drawArc(bodyRect, 195f, 75f, false, rimLightPaint)

        bodyRect.set(-r * 0.96f, -r * 0.46f, r * 0.96f, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, cartoonOutlinePaint)

        // 5. Golden Shrine Bell at Neck
        canvas.drawCircle(0f, r * 0.68f, r * 0.20f, accentPaint)
        canvas.drawCircle(0f, r * 0.68f, r * 0.20f, cartoonOutlinePaint)

        // 6. Expressive Eyes + Cute Nose
        drawStudioEyes(canvas, r, yCenter = r * 0.14f, isCatMouth = true)

        // 7. Paws Gripping the Rope Knot
        val climbHandOffset = if (state == CharacterState.CLIMBING_UP) sin(stateTimer * 9f) * 3.5f * density else 0f
        canvas.drawCircle(-r * 0.20f, -r * 0.15f + climbHandOffset, r * 0.20f, secondaryPaint)
        canvas.drawCircle(-r * 0.20f, -r * 0.15f + climbHandOffset, r * 0.20f, cartoonOutlinePaint)
        canvas.drawCircle(r * 0.20f, -r * 0.15f - climbHandOffset, r * 0.20f, secondaryPaint)
        canvas.drawCircle(r * 0.20f, -r * 0.15f - climbHandOffset, r * 0.20f, cartoonOutlinePaint)
    }

    private fun drawStudioEyes(
        canvas: Canvas,
        r: Float,
        yCenter: Float,
        isCatMouth: Boolean
    ) {
        canvas.drawOval(
            -r * 0.72f, yCenter + r * 0.08f,
            -r * 0.38f, yCenter + r * 0.28f,
            blushPaint
        )
        canvas.drawOval(
            r * 0.38f, yCenter + r * 0.08f,
            r * 0.72f, yCenter + r * 0.28f,
            blushPaint
        )

        val isHappyArc = state == CharacterState.TRICK_BACKFLIP ||
                state == CharacterState.DANCE_MUSIC_GROOVE ||
                state == CharacterState.REACT_CHARGING_SURGE ||
                state == CharacterState.TRICK_TARZAN_SWING

        if (isBlinking || isHappyArc) {
            for (side in listOf(-1f, 1f)) {
                val cx = side * r * 0.38f + lookOffsetX * 0.5f
                bodyRect.set(cx - r * 0.18f, yCenter - r * 0.12f, cx + r * 0.18f, yCenter + r * 0.14f)
                canvas.drawArc(bodyRect, 200f, 140f, false, cartoonOutlinePaint)
            }
        } else {
            val eyeW = r * 0.20f
            val eyeH = r * 0.24f
            for (side in listOf(-1f, 1f)) {
                val cx = side * r * 0.38f + lookOffsetX
                bodyRect.set(cx - eyeW, yCenter - eyeH, cx + eyeW, yCenter + eyeH)
                canvas.drawOval(bodyRect, eyePaint)

                canvas.drawCircle(cx - eyeW * 0.32f, yCenter - eyeH * 0.32f, eyeW * 0.38f, highlightPaint)
                canvas.drawCircle(cx + eyeW * 0.30f, yCenter + eyeH * 0.30f, eyeW * 0.18f, highlightPaint)
            }
        }

        if (isCatMouth) {
            canvas.drawCircle(lookOffsetX * 0.4f, yCenter + r * 0.14f, r * 0.07f, cartoonOutlinePaint)
            bodyRect.set(-r * 0.18f + lookOffsetX * 0.4f, yCenter + r * 0.14f, lookOffsetX * 0.4f, yCenter + r * 0.28f)
            canvas.drawArc(bodyRect, 0f, 180f, false, cartoonOutlinePaint)
            bodyRect.set(lookOffsetX * 0.4f, yCenter + r * 0.14f, r * 0.18f + lookOffsetX * 0.4f, yCenter + r * 0.28f)
            canvas.drawArc(bodyRect, 0f, 180f, false, cartoonOutlinePaint)
        }
    }

    private fun drawStateVfxOverlays(canvas: Canvas) {
        when (state) {
            CharacterState.REACT_CHARGING_SURGE -> {
                electricArcPaint.alpha = (130 + sin(stateTimer * 8f) * 90).toInt().coerceIn(40, 235)
                canvas.drawCircle(rope.anchorX, rope.anchorY, 21f * density, electricArcPaint)

                lightningPath.reset()
                lightningPath.moveTo(rope.x[0], rope.y[0])
                for (i in 1 until rope.nodeCount) {
                    val wave = sin(stateTimer * 10f + i * 0.7f) * 6f * density
                    lightningPath.lineTo(rope.x[i] + wave, rope.y[i])
                }
                canvas.drawPath(lightningPath, electricArcPaint)

                canvas.drawText(
                    "⚡ $currentBatteryPct%",
                    charX,
                    charY + 42f * density,
                    batteryTextPaint
                )
            }

            CharacterState.REACT_LOW_BATTERY -> {
                val blinkAlpha = ((sin(stateTimer * 4.5f) + 1f) * 0.5f * 235).toInt().coerceIn(50, 235)
                lowBatteryPaint.alpha = blinkAlpha
                val bx = charX - 12f * density
                val by = charY - 38f * density
                bodyRect.set(bx, by, bx + 24f * density, by + 12f * density)
                canvas.drawRoundRect(bodyRect, 3f * density, 3f * density, lowBatteryPaint)
            }

            CharacterState.DANCE_MUSIC_GROOVE -> {
                canvas.save()
                canvas.translate(charX, charY)
                canvas.rotate(smoothRotationDeg)
                val r = 20f * density
                bodyRect.set(-r, -r * 0.65f, r, r * 0.6f)
                canvas.drawArc(bodyRect, 180f, 180f, false, headphonePaint)
                canvas.drawCircle(-r, r * 0.08f, 5f * density, accentPaint)
                canvas.drawCircle(r, r * 0.08f, 5f * density, accentPaint)
                canvas.restore()

                for (i in 0..1) {
                    val phase = (stateTimer * 0.45f + i * 0.5f) % 1.0f
                    val noteX = charX + (if (i == 0) -1 else 1) * (20f + phase * 16f) * density + sin(phase * 5f) * 5f * density
                    val noteY = charY - (18f + phase * 40f) * density
                    notePaint.alpha = (sin(phase * PI.toFloat()) * 235).toInt().coerceIn(0, 235)
                    canvas.drawText(if (i == 0) "♪" else "♫", noteX, noteY, notePaint)
                }
            }

            else -> Unit
        }
    }

    // Studio Easing Functions (Slow-In / Slow-Out)
    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

    private fun easeInOutSine(t: Float): Float = -(cos(PI.toFloat() * t) - 1f) / 2f

    private fun easeOutSoftBack(t: Float): Float {
        // Gentle overshoot (c1 = 0.85 instead of harsh 1.70) for organic cushion settle
        val c1 = 0.85f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }
}
