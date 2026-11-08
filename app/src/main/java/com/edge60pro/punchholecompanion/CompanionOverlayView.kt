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
    private val rope = VerletRopeEngine(14)

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
    private var idleDurationTarget = 14f

    private var charX = 540f
    private var charY = 45f
    private var charRotationDeg = 0f
    private var scaleX = 1f
    private var scaleY = 1f
    private var portalScale = 0f

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
    private var activeBpm = 120f

    // Cartoon Speech Bubble State
    private var speechText: String = ""
    private var speechTimer: Float = 0f
    private var speechDuration: Float = 2.4f

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
        color = Color.argb(85, 0, 0, 0)
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
            CharacterSkin.MOCHI_CAT -> listOf("Meow~ ✨", "Purrfect! 🐾", "Nyaa! 💕", "Playtime! 🧶").random()
            CharacterSkin.SHADOW_NINJA -> listOf("Nin-Nin! 🥷", "Swift! ⚡", "Stealth 100%", "Shadow Art!").random()
            CharacterSkin.ASTRO_BOT -> listOf("Turbo! 🚀", "120Hz Glow! ✨", "Beep-Boop! 🤖", "Systems Go!").random()
            CharacterSkin.KITSUNE_SPIRIT -> listOf("Kon-Kon! 🦊", "Fox Magic ✨", "Lucky Day! 🔔", "Spirit Hop!").random()
        }
        speechDuration = 2.4f
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
                partX[i] = originX + (Random.nextFloat() - 0.5f) * 18f * density
                partY[i] = originY + (Random.nextFloat() - 0.5f) * 18f * density
                val angle = Random.nextFloat() * PI.toFloat() * 2f
                val speed = (35f + Random.nextFloat() * 95f) * density
                partVx[i] = cos(angle) * speed
                partVy[i] = sin(angle) * speed - 35f * density
                partSize[i] = (if (type == 2) 11f else 6.5f) * density * (0.7f + Random.nextFloat() * 0.6f)
                partMaxLife[i] = 0.65f + Random.nextFloat() * 0.45f
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
        idleDurationTarget = Random.nextFloat() * 6f + 12f
        rope.applyTipImpulse((Random.nextFloat() - 0.5f) * 40f * density, 20f * density)
        emitParticleBurst(rope.anchorX, rope.anchorY + 12f * density, 7, 0, Color.parseColor("#00F5D4"))
        showSpeechBubble(greetingText)
        startAnimationLoop()
    }

    fun retreatToPunchHole() {
        if (state == CharacterState.HIDDEN || state == CharacterState.CLIMBING_UP) return
        rope.isTipPinned = false
        state = CharacterState.CLIMBING_UP
        stateTimer = 0f
        rope.applyTipImpulse((Random.nextFloat() - 0.5f) * 20f * density, -10f * density)
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
        emitParticleBurst(charX, charY, 6, 0, currentSkin.accentColor)

        when (nextTrick) {
            CharacterState.TRICK_BACKFLIP -> {
                state = CharacterState.TRICK_BACKFLIP
                rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 60f * density, -90f * density)
            }
            CharacterState.TRICK_BUNGEE_JUMP -> {
                state = CharacterState.TRICK_BUNGEE_JUMP
                rope.applyTipImpulse(0f, 130f * density)
            }
            CharacterState.TRICK_SWING_WAVE -> {
                state = CharacterState.TRICK_SWING_WAVE
                rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 95f * density, 0f)
            }
            CharacterState.TRICK_TARZAN_SWING -> {
                state = CharacterState.TRICK_TARZAN_SWING
                val dir = if (Random.nextBoolean()) 1 else -1
                rope.applyTipImpulse(dir * 165f * density, -35f * density)
                showSpeechBubble("Wheee!! 🌟")
            }
            CharacterState.TRICK_UPSIDE_DOWN -> {
                state = CharacterState.TRICK_UPSIDE_DOWN
                rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 45f * density, 25f * density)
                showSpeechBubble("Hangin' out! 🙃")
            }
            CharacterState.TRICK_SMOKE_TELEPORT -> {
                state = CharacterState.TRICK_SMOKE_TELEPORT
                emitParticleBurst(charX, charY, 12, 2, Color.parseColor("#E0E6ED"))
                showSpeechBubble("Poof! 💨")
            }
            CharacterState.EDGE_WALKING -> {
                state = CharacterState.EDGE_WALKING
                edgeProgress = rope.anchorX
                edgeDirection = if (Random.nextBoolean()) 1f else -1f
                showSpeechBubble("Parkour! ⚡")
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
        val maxPull = rope.totalLengthPx * 2.2f
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
        emitParticleBurst(charX, charY, 8, 0, Color.parseColor("#FFBE0B"))

        if (stretch >= 1.65f && velocityY <= 400f) {
            state = CharacterState.SLINGSHOT_LAUNCH
            showSpeechBubble(" Yeehaw!! 🚀")
        } else {
            state = CharacterState.TRICK_BACKFLIP
            showSpeechBubble("Boing! ✨")
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
            rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 75f * density, -60f * density)
            emitParticleBurst(charX, charY, 10, 0, Color.parseColor("#00E676"))
            showSpeechBubble("Power Up! ⚡$batteryPct%")
        } else {
            state = CharacterState.REACT_LOW_BATTERY
            rope.applyTipImpulse(0f, 40f * density)
            showSpeechBubble("Hungry... 🪫$batteryPct%")
        }
        startAnimationLoop()
    }

    fun triggerMusicDance(bpm: Float) {
        activeBpm = bpm.coerceIn(90f, 160f)
        if (state == CharacterState.HIDDEN) {
            advanceCharacterIfAutoSwap()
            rope.resetToAnchor(rope.anchorX, rope.anchorY)
            rope.deployedRatio = 0.88f
        }
        state = CharacterState.DANCE_MUSIC_GROOVE
        stateTimer = 0f
        portalScale = 1f
        showSpeechBubble("Let's Vibe! 🎵")
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
        // Smooth low-pass filter on horizontal gravity tilt (-9.8 to +9.8 m/s^2)
        val rawTilt = -event.values[0]
        phoneTiltX = phoneTiltX * 0.82f + rawTilt * 0.18f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun doFrame(frameTimeNanos: Long) {
        if (!isLoopRunning) return

        val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
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

        blinkTimer += dt
        if (blinkTimer > 3.2f) {
            isBlinking = true
            if (blinkTimer > 3.36f) {
                isBlinking = false
                blinkTimer = 0f
            }
        }

        // Apply live phone tilt gravity to the rope nodes
        if (!rope.isTipPinned && abs(phoneTiltX) > 0.35f) {
            rope.applyTipImpulse(phoneTiltX * 1.45f * density * dt * 60f, 0f)
        }

        rope.step(dt)
        updateParticles(dt)

        when (state) {
            CharacterState.DESCENDING -> {
                val progress = (stateTimer / 1.15f).coerceIn(0f, 1f)
                rope.deployedRatio = easeOutBack(progress)
                portalScale = (progress * 1.6f).coerceIn(0f, 1f)

                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = Math.toDegrees(rope.tipAngleRad().toDouble()).toFloat() * -0.6f
                scaleX = 1f - 0.12f * sin(progress * PI.toFloat())
                scaleY = 1f + 0.12f * sin(progress * PI.toFloat())

                if (progress >= 1f) {
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.IDLE_HANGING -> {
                ropePaint.strokeWidth = ropeThicknessPx
                rope.deployedRatio = 1f
                portalScale = 1f
                rope.applyTipImpulse(sin(stateTimer * 2.1f) * 1.6f * density * dt * 60f, 0f)

                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = Math.toDegrees(rope.tipAngleRad().toDouble()).toFloat() * -0.5f

                val breath = sin(stateTimer * 3.5f) * 0.04f
                scaleX = 1f - breath
                scaleY = 1f + breath

                if (stateTimer >= idleDurationTarget) {
                    if (Random.nextFloat() < 0.55f) {
                        triggerSingleTapTrick(CompanionRepository.config.value.allowEdgeWalk)
                        idleDurationTarget = 6f
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
                scaleX = 1f - stretchFactor * 0.22f
                scaleY = 1f + stretchFactor * 0.28f
                ropePaint.strokeWidth = (ropeThicknessPx / currentStretchRatio.coerceIn(0.8f, 1.7f))
            }

            CharacterState.SLINGSHOT_LAUNCH -> {
                ropePaint.strokeWidth = ropeThicknessPx
                val duration = 0.65f
                val p = (stateTimer / duration).coerceIn(0f, 1f)

                rope.deployedRatio = (1f - p).coerceAtLeast(0.02f)
                portalScale = (1f - p * p).coerceIn(0f, 1f)
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = p * 720f
                scaleX = portalScale * 0.85f
                scaleY = portalScale * 1.2f

                if (p >= 1f) {
                    state = CharacterState.HIDDEN
                }
            }

            CharacterState.TRICK_BACKFLIP -> {
                ropePaint.strokeWidth = ropeThicknessPx
                val duration = 0.95f
                val p = (stateTimer / duration).coerceIn(0f, 1f)

                val jumpArc = sin(p * PI.toFloat()) * 44f * density
                charX = rope.tipX()
                charY = rope.tipY() - jumpArc
                charRotationDeg = p * 360f

                scaleX = 1f - 0.18f * sin(p * PI.toFloat() * 2f)
                scaleY = 1f + 0.18f * sin(p * PI.toFloat() * 2f)

                if (p >= 1f) {
                    rope.applyTipImpulse(0f, 45f * density)
                    emitParticleBurst(charX, charY, 5, 1, Color.parseColor("#FF3366"))
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_BUNGEE_JUMP -> {
                val duration = 1.25f
                val p = (stateTimer / duration).coerceIn(0f, 1f)

                val elasticWave = sin(p * PI.toFloat() * 3f) * (1f - p)
                rope.deployedRatio = 1f + 0.48f * elasticWave
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = sin(p * PI.toFloat() * 4f) * 25f
                scaleX = (1f - elasticWave * 0.22f).coerceIn(0.75f, 1.25f)
                scaleY = (1f + elasticWave * 0.22f).coerceIn(0.75f, 1.3f)

                if (p >= 1f) {
                    rope.deployedRatio = 1f
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_SWING_WAVE -> {
                val duration = 1.8f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.applyTipImpulse(cos(p * PI.toFloat() * 2f) * 9.5f * density * dt * 60f, 0f)
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = Math.toDegrees(rope.tipAngleRad().toDouble()).toFloat() * -0.8f
                scaleX = 1f
                scaleY = 1f

                if (p >= 1f) {
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_TARZAN_SWING -> {
                val duration = 2.4f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.applyTipImpulse(cos(p * PI.toFloat() * 3f) * 16f * density * dt * 60f, 0f)
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = Math.toDegrees(rope.tipAngleRad().toDouble()).toFloat() * -0.95f

                if (Random.nextFloat() < 0.35f) {
                    emitParticleBurst(charX, charY, 1, 0, Color.parseColor("#FFBE0B"))
                }

                if (p >= 1f) {
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_UPSIDE_DOWN -> {
                val duration = 3.8f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.applyTipImpulse(sin(stateTimer * 3.2f) * 3.2f * density * dt * 60f, 0f)
                charX = rope.tipX()
                charY = rope.tipY()
                // Smoothly flip to 180 degrees upside down
                val flip = (stateTimer / 0.4f).coerceIn(0f, 1f)
                charRotationDeg = 180f * flip + sin(stateTimer * 4f) * 12f
                scaleX = 1f
                scaleY = 1f

                if (p >= 1f) {
                    state = CharacterState.TRICK_BACKFLIP
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_SMOKE_TELEPORT -> {
                val duration = 0.55f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                portalScale = (1f - p).coerceIn(0f, 1f)
                rope.deployedRatio = (1f - p).coerceAtLeast(0.02f)
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = p * 180f

                if (p >= 1f) {
                    state = CharacterState.HIDDEN
                }
            }

            CharacterState.EDGE_WALKING -> {
                rope.deployedRatio = (rope.deployedRatio - dt * 2.5f).coerceAtLeast(0.02f)
                val speed = 225f * density
                val w = width.toFloat().coerceAtLeast(1080f)
                val h = height.toFloat().coerceAtLeast(2400f)
                val margin = 24f * density

                edgeProgress += speed * dt * edgeDirection

                val totalRunTime = 5.5f
                if (stateTimer < 1.8f) {
                    charX = edgeProgress.coerceIn(margin, w - margin)
                    charY = rope.anchorY + 14f * density
                    charRotationDeg = 0f
                    if (charX <= margin + 2f || charX >= w - margin - 2f) {
                        stateTimer = 1.8f
                    }
                } else if (stateTimer < 4.0f) {
                    val sidePhase = (stateTimer - 1.8f) / 2.2f
                    val verticalDrop = sin(sidePhase * PI.toFloat()) * (h * 0.38f)
                    val onRightEdge = edgeDirection > 0
                    charX = if (onRightEdge) w - margin else margin
                    charY = rope.anchorY + verticalDrop
                    charRotationDeg = if (onRightEdge) -90f else 90f
                } else {
                    val returnPhase = ((stateTimer - 4.0f) / 1.2f).coerceIn(0f, 1f)
                    val startX = if (edgeDirection > 0) w - margin else margin
                    charX = startX + (rope.anchorX - startX) * returnPhase
                    charY = rope.anchorY + 14f * density
                    charRotationDeg = 0f
                }

                scaleX = 1f + 0.08f * sin(stateTimer * 18f)
                scaleY = 1f - 0.08f * sin(stateTimer * 18f)

                if (stateTimer >= totalRunTime) {
                    state = CharacterState.CLIMBING_UP
                    stateTimer = 0f
                }
            }

            CharacterState.REACT_CHARGING_SURGE -> {
                val duration = 4.2f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.deployedRatio = 1f

                val buzz = sin(stateTimer * 55f) * 3.5f * density
                rope.applyTipImpulse(buzz * dt * 60f, 0f)

                charX = rope.tipX() + sin(stateTimer * 45f) * 2.5f * density
                charY = rope.tipY() - sin(p * PI.toFloat()) * 18f * density
                charRotationDeg = sin(stateTimer * 30f) * 8f

                val pulse = sin(stateTimer * 22f) * 0.08f
                scaleX = 1f + pulse
                scaleY = 1f - pulse

                if (p >= 1f) {
                    retreatToPunchHole()
                }
            }

            CharacterState.REACT_LOW_BATTERY -> {
                val duration = 5.0f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.deployedRatio = 1.12f

                rope.applyTipImpulse(sin(stateTimer * 1.4f) * 1.2f * density * dt * 60f, 0f)
                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = 18f + sin(stateTimer * 2.5f) * 10f

                scaleX = 1.06f + sin(stateTimer * 2f) * 0.04f
                scaleY = 0.92f - sin(stateTimer * 2f) * 0.04f

                if (p >= 1f) {
                    retreatToPunchHole()
                }
            }

            CharacterState.REACT_NOTIFICATION -> {
                val duration = 4.5f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.deployedRatio = 0.78f

                if (stateTimer < 1.0f) {
                    rope.applyTipImpulse(sin(stateTimer * 35f) * 4f * density * dt * 60f, 0f)
                }

                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = sin(stateTimer * 18f) * (1f - p) * 16f

                val pop = sin(stateTimer * 12f) * (1f - p) * 0.12f
                scaleX = 1f + pop
                scaleY = 1f - pop

                if (p >= 1f) {
                    cutoutRingPaint.color = Color.parseColor("#00E5FF")
                    retreatToPunchHole()
                }
            }

            CharacterState.DANCE_MUSIC_GROOVE -> {
                val duration = 14.0f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.deployedRatio = 0.92f

                val beatFreq = (activeBpm / 60f) * PI.toFloat() * 2f
                val beatWave = sin(stateTimer * beatFreq)
                val halfBeatWave = cos(stateTimer * beatFreq * 0.5f)

                rope.applyTipImpulse(
                    vx = halfBeatWave * 5.2f * density * dt * 60f,
                    vy = abs(beatWave) * 3.8f * density * dt * 60f
                )

                charX = rope.tipX()
                charY = rope.tipY() - abs(beatWave) * 10f * density
                charRotationDeg = halfBeatWave * 22f

                scaleX = 1f + beatWave * 0.11f
                scaleY = 1f - beatWave * 0.11f

                if (p >= 1f || !CompanionEventBus.isMusicCurrentlyPlaying) {
                    retreatToPunchHole()
                }
            }

            CharacterState.CLIMBING_UP -> {
                ropePaint.strokeWidth = ropeThicknessPx
                val duration = 0.85f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.deployedRatio = (1f - p).coerceAtLeast(0.01f)
                portalScale = (1f - p * p).coerceIn(0f, 1f)

                charX = rope.tipX()
                charY = rope.tipY()
                charRotationDeg = sin(p * PI.toFloat() * 6f) * 14f
                scaleX = portalScale
                scaleY = portalScale

                if (p >= 1f) {
                    state = CharacterState.HIDDEN
                }
            }

            CharacterState.HIDDEN -> Unit
        }

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
                    partVy[i] += 110f * density * dt // Gentle float gravity
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == CharacterState.HIDDEN) return

        // 1. Cutout Portal Glow Ring
        if (state == CharacterState.DESCENDING || state == CharacterState.CLIMBING_UP) {
            cutoutRingPaint.alpha = (sin(portalScale * PI.toFloat()) * 200).toInt().coerceIn(0, 220)
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
                rotationDeg = charRotationDeg,
                scaleX = scaleX * portalScale,
                scaleY = scaleY * portalScale
            )
        } else {
            drawStudioCartoonCharacter(canvas)
        }

        // 5. Studio Particles (Stars, Hearts, Smoke Puffs)
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
            particlePaint.color = partColor[i]
            particlePaint.alpha = (progress * 245).toInt()

            val px = partX[i]
            val py = partY[i]
            val sz = partSize[i] * (0.5f + 0.5f * progress)

            when (partType[i]) {
                0 -> {
                    // 4-Point Twinkling Anime Star
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
                    // Cute Floating Heart / Spark Circle
                    canvas.drawCircle(px, py, sz * 0.75f, particlePaint)
                }
                else -> {
                    // Expanding Ninja Smoke Cloud
                    val smokeRadius = partSize[i] * (1.6f - progress * 0.6f)
                    canvas.drawCircle(px, py, smokeRadius, particlePaint)
                    canvas.drawCircle(px, py, smokeRadius, cartoonOutlinePaint)
                }
            }
        }
    }

    private fun drawCartoonSpeechBubble(canvas: Canvas) {
        val popScale = easeOutBack(((speechDuration - speechTimer) / 0.25f).coerceIn(0f, 1f))
        val fadeOut = (speechTimer / 0.3f).coerceIn(0f, 1f)
        val finalScale = popScale * fadeOut

        val textWidth = bubbleTextPaint.measureText(speechText)
        val padH = 11f * density
        val boxW = textWidth + padH * 2f
        val boxH = 24f * density

        // Place speech bubble to the right or left depending on screen space
        val placeLeft = charX > (width * 0.62f)
        val offsetX = if (placeLeft) -(boxW * 0.65f + 22f * density) else (boxW * 0.65f + 22f * density)
        val bx = charX + offsetX
        val by = (charY - 22f * density).coerceAtLeast(36f * density)

        canvas.save()
        canvas.translate(bx, by)
        canvas.scale(finalScale, finalScale)

        bubbleRect.set(-boxW / 2f, -boxH / 2f, boxW / 2f, boxH / 2f)
        canvas.drawRoundRect(bubbleRect, 10f * density, 10f * density, bubbleFillPaint)
        canvas.drawRoundRect(bubbleRect, 10f * density, 10f * density, cartoonOutlinePaint)

        canvas.drawText(speechText, 0f, 4f * density, bubbleTextPaint)
        canvas.restore()
    }

    private fun drawStudioCartoonCharacter(canvas: Canvas) {
        canvas.save()
        canvas.translate(charX, charY)
        canvas.rotate(charRotationDeg)
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
        val tailWag = sin(stateTimer * 6.5f) * 14f * density
        scarfOrTailPath.reset()
        scarfOrTailPath.moveTo(0f, r * 1.15f)
        scarfOrTailPath.cubicTo(
            r * 1.1f, r * 1.35f,
            r * 1.55f, r * 0.4f + tailWag,
            r * 1.95f, r * 0.1f - tailWag * 0.6f
        )
        tailStrokePaint.strokeWidth = 8.5f * density
        tailStrokePaint.color = Color.parseColor("#14161F")
        canvas.drawPath(scarfOrTailPath, tailStrokePaint)
        tailStrokePaint.strokeWidth = 5.5f * density
        tailStrokePaint.color = currentSkin.accentColor
        canvas.drawPath(scarfOrTailPath, tailStrokePaint)

        bodyRect.set(-r * 0.72f, r * 0.4f, r * 0.72f, r * 1.55f)
        canvas.drawRoundRect(bodyRect, r * 0.55f, r * 0.55f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.55f, r * 0.55f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.42f, r * 0.62f, r * 0.42f, r * 1.38f)
        canvas.drawOval(bodyRect, secondaryPaint)

        val kick = sin(stateTimer * 8f) * 3.5f * density
        canvas.drawCircle(-r * 0.38f, r * 1.58f + kick, r * 0.22f, bodyPaint)
        canvas.drawCircle(-r * 0.38f, r * 1.58f + kick, r * 0.22f, cartoonOutlinePaint)
        canvas.drawCircle(r * 0.38f, r * 1.58f - kick, r * 0.22f, bodyPaint)
        canvas.drawCircle(r * 0.38f, r * 1.58f - kick, r * 0.22f, cartoonOutlinePaint)

        for (side in listOf(-1f, 1f)) {
            earPath.reset()
            earPath.moveTo(side * r * 0.25f, -r * 0.35f)
            earPath.lineTo(side * r * 0.92f, -r * 0.95f)
            earPath.lineTo(side * r * 0.95f, r * 0.05f)
            earPath.close()
            canvas.drawPath(earPath, bodyPaint)
            canvas.drawPath(earPath, cartoonOutlinePaint)

            earPath.reset()
            earPath.moveTo(side * r * 0.38f, -r * 0.28f)
            earPath.lineTo(side * r * 0.80f, -r * 0.75f)
            earPath.lineTo(side * r * 0.82f, -r * 0.05f)
            earPath.close()
            canvas.drawPath(earPath, secondaryPaint)
        }

        bodyRect.set(-r, -r * 0.48f, r, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.75f, r * 0.75f, bodyPaint)

        bodyRect.set(r * 0.1f, -r * 0.45f, r * 0.85f, r * 0.05f)
        canvas.drawArc(bodyRect, 200f, 140f, true, accentPaint)

        bodyRect.set(-r, -r * 0.48f, r, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.75f, r * 0.75f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.62f, r * 0.58f, r * 0.62f, r * 0.76f)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, accentPaint)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, cartoonOutlinePaint)

        drawStudioEyes(canvas, r, yCenter = r * 0.12f, isCatMouth = true)

        val whiskerWidth = cartoonOutlinePaint.strokeWidth
        cartoonOutlinePaint.strokeWidth = 1.6f * density
        for (side in listOf(-1f, 1f)) {
            canvas.drawLine(side * r * 0.72f, r * 0.18f, side * r * 1.18f, r * 0.10f, cartoonOutlinePaint)
            canvas.drawLine(side * r * 0.72f, r * 0.28f, side * r * 1.20f, r * 0.28f, cartoonOutlinePaint)
        }
        cartoonOutlinePaint.strokeWidth = whiskerWidth

        for (side in listOf(-1f, 1f)) {
            canvas.drawCircle(side * r * 0.20f, -r * 0.15f, r * 0.22f, bodyPaint)
            canvas.drawCircle(side * r * 0.20f, -r * 0.15f, r * 0.22f, cartoonOutlinePaint)
            canvas.drawCircle(side * r * 0.20f, -r * 0.15f, r * 0.10f, secondaryPaint)
        }
    }

    private fun drawShadowNinja(canvas: Canvas, r: Float) {
        val wave1 = sin(stateTimer * 9.5f) * 14f * density
        val wave2 = cos(stateTimer * 9.5f) * 19f * density
        scarfOrTailPath.reset()
        scarfOrTailPath.moveTo(0f, r * 0.45f)
        scarfOrTailPath.cubicTo(
            -r * 1.3f, r * 0.2f + wave1 * 0.5f,
            -r * 2.1f, r * 0.5f + wave2,
            -r * 2.85f, r * 0.85f + wave1
        )
        scarfOrTailPath.lineTo(-r * 2.45f, r * 1.25f + wave1)
        scarfOrTailPath.close()
        canvas.drawPath(scarfOrTailPath, accentPaint)
        canvas.drawPath(scarfOrTailPath, cartoonOutlinePaint)

        canvas.save()
        canvas.rotate(32f)
        bodyRect.set(-r * 0.15f, -r * 0.95f, r * 0.15f, r * 0.6f)
        canvas.drawRoundRect(bodyRect, 3f * density, 3f * density, accentPaint)
        canvas.drawRoundRect(bodyRect, 3f * density, 3f * density, cartoonOutlinePaint)
        canvas.restore()

        bodyRect.set(-r * 0.65f, r * 0.45f, r * 0.65f, r * 1.48f)
        canvas.drawRoundRect(bodyRect, r * 0.45f, r * 0.45f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.45f, r * 0.45f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.95f, -r * 0.42f, r * 0.95f, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.72f, -r * 0.04f, r * 0.72f, r * 0.36f)
        canvas.drawRoundRect(bodyRect, r * 0.22f, r * 0.22f, secondaryPaint)
        canvas.drawRoundRect(bodyRect, r * 0.22f, r * 0.22f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.96f, -r * 0.26f, r * 0.96f, -r * 0.02f)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, accentPaint)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, cartoonOutlinePaint)

        bodyRect.set(-r * 0.35f, -r * 0.22f, r * 0.35f, -r * 0.06f)
        canvas.drawRoundRect(bodyRect, 2f * density, 2f * density, highlightPaint)

        drawStudioEyes(canvas, r, yCenter = r * 0.16f, isCatMouth = false)

        for (side in listOf(-1f, 1f)) {
            canvas.drawCircle(side * r * 0.18f, -r * 0.14f, r * 0.19f, bodyPaint)
            canvas.drawCircle(side * r * 0.18f, -r * 0.14f, r * 0.19f, cartoonOutlinePaint)
        }
    }

    private fun drawAstroBot(canvas: Canvas, r: Float) {
        val flameLen = (0.35f + abs(sin(stateTimer * 28f)) * 0.45f) * r
        earPath.reset()
        earPath.moveTo(-r * 0.32f, r * 1.42f)
        earPath.lineTo(0f, r * 1.42f + flameLen)
        earPath.lineTo(r * 0.32f, r * 1.42f)
        earPath.close()
        canvas.drawPath(earPath, accentPaint)

        bodyRect.set(-r * 0.68f, r * 0.50f, r * 0.68f, r * 1.45f)
        canvas.drawRoundRect(bodyRect, r * 0.5f, r * 0.5f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.5f, r * 0.5f, cartoonOutlinePaint)

        canvas.drawCircle(0f, r * 0.98f, r * 0.20f, eyePaint)
        canvas.drawCircle(0f, r * 0.98f, r * 0.20f, cartoonOutlinePaint)

        canvas.drawLine(0f, -r * 0.45f, 0f, -r * 0.82f, cartoonOutlinePaint)
        canvas.drawCircle(0f, -r * 0.88f, r * 0.16f, accentPaint)
        canvas.drawCircle(0f, -r * 0.88f, r * 0.16f, cartoonOutlinePaint)

        bodyRect.set(-r * 1.12f, -r * 0.05f, r * 1.12f, r * 0.35f)
        canvas.drawRoundRect(bodyRect, r * 0.2f, r * 0.2f, accentPaint)
        canvas.drawRoundRect(bodyRect, r * 0.2f, r * 0.2f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.98f, -r * 0.46f, r * 0.98f, r * 0.76f)
        canvas.drawRoundRect(bodyRect, r * 0.68f, r * 0.68f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.68f, r * 0.68f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.78f, -r * 0.22f, r * 0.78f, r * 0.52f)
        canvas.drawRoundRect(bodyRect, r * 0.42f, r * 0.42f, secondaryPaint)
        canvas.drawRoundRect(bodyRect, r * 0.42f, r * 0.42f, cartoonOutlinePaint)

        canvas.drawCircle(-r * 0.52f, -r * 0.06f, r * 0.08f, highlightPaint)

        drawStudioEyes(canvas, r, yCenter = r * 0.15f, isCatMouth = false)

        for (side in listOf(-1f, 1f)) {
            canvas.drawCircle(side * r * 0.18f, -r * 0.14f, r * 0.18f, accentPaint)
            canvas.drawCircle(side * r * 0.18f, -r * 0.14f, r * 0.18f, cartoonOutlinePaint)
        }
    }

    private fun drawKitsuneSpirit(canvas: Canvas, r: Float) {
        val sway = sin(stateTimer * 5.5f) * 12f * density
        scarfOrTailPath.reset()
        scarfOrTailPath.moveTo(0f, r * 1.1f)
        scarfOrTailPath.cubicTo(
            -r * 1.5f, r * 1.4f,
            -r * 2.0f, r * 0.3f + sway,
            -r * 1.3f, -r * 0.2f + sway
        )
        scarfOrTailPath.cubicTo(
            -r * 0.8f, r * 0.1f + sway,
            -r * 0.6f, r * 0.8f,
            0f, r * 1.1f
        )
        scarfOrTailPath.close()
        canvas.drawPath(scarfOrTailPath, bodyPaint)
        canvas.drawPath(scarfOrTailPath, cartoonOutlinePaint)

        bodyRect.set(-r * 0.68f, r * 0.42f, r * 0.68f, r * 1.50f)
        canvas.drawRoundRect(bodyRect, r * 0.52f, r * 0.52f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.52f, r * 0.52f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.40f, r * 0.58f, r * 0.40f, r * 1.32f)
        canvas.drawOval(bodyRect, secondaryPaint)

        for (side in listOf(-1f, 1f)) {
            earPath.reset()
            earPath.moveTo(side * r * 0.20f, -r * 0.35f)
            earPath.lineTo(side * r * 1.08f, -r * 1.12f)
            earPath.lineTo(side * r * 0.95f, r * 0.08f)
            earPath.close()
            canvas.drawPath(earPath, bodyPaint)
            canvas.drawPath(earPath, cartoonOutlinePaint)

            earPath.reset()
            earPath.moveTo(side * r * 0.35f, -r * 0.28f)
            earPath.lineTo(side * r * 0.92f, -r * 0.88f)
            earPath.lineTo(side * r * 0.82f, -r * 0.02f)
            earPath.close()
            canvas.drawPath(earPath, secondaryPaint)
        }

        bodyRect.set(-r * 0.96f, -r * 0.46f, r * 0.96f, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, cartoonOutlinePaint)

        canvas.drawCircle(0f, r * 0.68f, r * 0.20f, accentPaint)
        canvas.drawCircle(0f, r * 0.68f, r * 0.20f, cartoonOutlinePaint)

        drawStudioEyes(canvas, r, yCenter = r * 0.14f, isCatMouth = true)

        for (side in listOf(-1f, 1f)) {
            canvas.drawCircle(side * r * 0.20f, -r * 0.15f, r * 0.20f, secondaryPaint)
            canvas.drawCircle(side * r * 0.20f, -r * 0.15f, r * 0.20f, cartoonOutlinePaint)
        }
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
                val cx = side * r * 0.38f
                bodyRect.set(cx - r * 0.18f, yCenter - r * 0.12f, cx + r * 0.18f, yCenter + r * 0.14f)
                canvas.drawArc(bodyRect, 200f, 140f, false, cartoonOutlinePaint)
            }
        } else {
            val eyeW = r * 0.20f
            val eyeH = r * 0.24f
            for (side in listOf(-1f, 1f)) {
                val cx = side * r * 0.38f
                bodyRect.set(cx - eyeW, yCenter - eyeH, cx + eyeW, yCenter + eyeH)
                canvas.drawOval(bodyRect, eyePaint)

                canvas.drawCircle(cx - eyeW * 0.32f, yCenter - eyeH * 0.32f, eyeW * 0.38f, highlightPaint)
                canvas.drawCircle(cx + eyeW * 0.30f, yCenter + eyeH * 0.30f, eyeW * 0.18f, highlightPaint)
            }
        }

        if (isCatMouth) {
            canvas.drawCircle(0f, yCenter + r * 0.14f, r * 0.07f, cartoonOutlinePaint)
            bodyRect.set(-r * 0.18f, yCenter + r * 0.14f, 0f, yCenter + r * 0.28f)
            canvas.drawArc(bodyRect, 0f, 180f, false, cartoonOutlinePaint)
            bodyRect.set(0f, yCenter + r * 0.14f, r * 0.18f, yCenter + r * 0.28f)
            canvas.drawArc(bodyRect, 0f, 180f, false, cartoonOutlinePaint)
        }
    }

    private fun drawStateVfxOverlays(canvas: Canvas) {
        when (state) {
            CharacterState.REACT_CHARGING_SURGE -> {
                electricArcPaint.alpha = (150 + sin(stateTimer * 25f) * 100).toInt().coerceIn(40, 255)
                canvas.drawCircle(rope.anchorX, rope.anchorY, 21f * density, electricArcPaint)

                lightningPath.reset()
                lightningPath.moveTo(rope.x[0], rope.y[0])
                for (i in 1 until rope.nodeCount) {
                    val jitter = (Random.nextFloat() - 0.5f) * 14f * density
                    lightningPath.lineTo(rope.x[i] + jitter, rope.y[i])
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
                val blinkAlpha = ((sin(stateTimer * 10f) + 1f) * 0.5f * 255).toInt().coerceIn(50, 255)
                lowBatteryPaint.alpha = blinkAlpha
                val bx = charX - 12f * density
                val by = charY - 38f * density
                bodyRect.set(bx, by, bx + 24f * density, by + 12f * density)
                canvas.drawRoundRect(bodyRect, 3f * density, 3f * density, lowBatteryPaint)
            }

            CharacterState.DANCE_MUSIC_GROOVE -> {
                canvas.save()
                canvas.translate(charX, charY)
                canvas.rotate(charRotationDeg)
                val r = 20f * density
                bodyRect.set(-r, -r * 0.65f, r, r * 0.6f)
                canvas.drawArc(bodyRect, 180f, 180f, false, headphonePaint)
                canvas.drawCircle(-r, r * 0.08f, 5f * density, accentPaint)
                canvas.drawCircle(r, r * 0.08f, 5f * density, accentPaint)
                canvas.restore()

                for (i in 0..1) {
                    val phase = (stateTimer * 0.9f + i * 0.5f) % 1.0f
                    val noteX = charX + (if (i == 0) -1 else 1) * (20f + phase * 16f) * density + sin(phase * 6f) * 5f * density
                    val noteY = charY - (18f + phase * 42f) * density
                    notePaint.alpha = ((1f - phase) * 235).toInt()
                    canvas.drawText(if (i == 0) "♪" else "♫", noteX, noteY, notePaint)
                }
            }

            else -> Unit
        }
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }
}
