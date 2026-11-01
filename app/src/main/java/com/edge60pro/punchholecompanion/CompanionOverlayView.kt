package com.edge60pro.punchholecompanion

import android.content.Context
import android.graphics.*
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
) : View(context), Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density
    private val rope = VerletRopeEngine(14)

    var state: CharacterState = CharacterState.HIDDEN
        private set

    private var lastFrameNanos = 0L
    private var isLoopRunning = false
    private var stateTimer = 0f
    private var idleDurationTarget = 12f

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

    // Pre-allocated Paths & RectF (Zero GC allocations during 120Hz onDraw)
    private val ropePath = Path()
    private val scarfOrTailPath = Path()
    private val earPath = Path()
    private val lightningPath = Path()
    private val bodyRect = RectF()

    // Studio Braided Rope Paints (with Paint.Join.ROUND fix)
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

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(135, 255, 105, 135)
    }

    private val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val tailStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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

    private val alertBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF5252")
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
        limbPaint.color = skin.bodyColor
        tailStrokePaint.color = skin.bodyColor
    }

    /**
     * Rotates to the next studio character every time the companion emerges from the punch hole.
     */
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

    fun toggleCharacterVisibility() {
        if (state == CharacterState.HIDDEN) {
            spawnFromPunchHole()
        } else if (state != CharacterState.CLIMBING_UP) {
            retreatToPunchHole()
        }
    }

    fun spawnFromPunchHole() {
        if (state != CharacterState.HIDDEN) return
        advanceCharacterIfAutoSwap()
        rope.resetToAnchor(rope.anchorX, rope.anchorY)
        rope.deployedRatio = 0.02f
        state = CharacterState.DESCENDING
        stateTimer = 0f
        idleDurationTarget = Random.nextFloat() * 6f + 10f
        rope.applyTipImpulse((Random.nextFloat() - 0.5f) * 35f * density, 15f * density)
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

        val nextTrick = if (state == CharacterState.EDGE_WALKING) {
            CharacterState.DESCENDING
        } else {
            val pool = mutableListOf(
                CharacterState.TRICK_BACKFLIP,
                CharacterState.TRICK_BUNGEE_JUMP,
                CharacterState.TRICK_SWING_WAVE
            )
            if (allowEdgeWalk) pool.add(CharacterState.EDGE_WALKING)
            pool.random()
        }

        when (nextTrick) {
            CharacterState.TRICK_BACKFLIP -> {
                state = CharacterState.TRICK_BACKFLIP
                rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 55f * density, -85f * density)
            }
            CharacterState.TRICK_BUNGEE_JUMP -> {
                state = CharacterState.TRICK_BUNGEE_JUMP
                rope.applyTipImpulse(0f, 120f * density)
            }
            CharacterState.TRICK_SWING_WAVE -> {
                state = CharacterState.TRICK_SWING_WAVE
                rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 95f * density, 0f)
            }
            CharacterState.EDGE_WALKING -> {
                state = CharacterState.EDGE_WALKING
                edgeProgress = rope.anchorX
                edgeDirection = if (Random.nextBoolean()) 1f else -1f
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

        if (stretch >= 1.65f && velocityY <= 400f) {
            state = CharacterState.SLINGSHOT_LAUNCH
        } else {
            state = CharacterState.TRICK_BACKFLIP
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
        } else {
            state = CharacterState.REACT_LOW_BATTERY
            rope.applyTipImpulse(0f, 40f * density)
        }
        startAnimationLoop()
    }

    fun triggerNotificationAlert(accentColor: Int) {
        cutoutRingPaint.color = accentColor
        alertBadgePaint.color = accentColor

        if (state == CharacterState.HIDDEN) {
            advanceCharacterIfAutoSwap()
            rope.resetToAnchor(rope.anchorX, rope.anchorY)
            rope.deployedRatio = 0.72f
        }
        state = CharacterState.REACT_NOTIFICATION
        stateTimer = 0f
        portalScale = 1f
        rope.applyTipImpulse((if (Random.nextBoolean()) 1 else -1) * 35f * density, 90f * density)
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
        startAnimationLoop()
    }

    private fun startAnimationLoop() {
        if (!isLoopRunning) {
            visibility = VISIBLE
            isLoopRunning = true
            lastFrameNanos = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun forceSleepNow() {
        rope.isTipPinned = false
        state = CharacterState.HIDDEN
        isLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        onCharacterBoundsMoved(0f, 0f, false)
        visibility = GONE
    }

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
        blinkTimer += dt
        if (blinkTimer > 3.2f) {
            isBlinking = true
            if (blinkTimer > 3.36f) {
                isBlinking = false
                blinkTimer = 0f
            }
        }

        rope.step(dt)

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
                    if (Random.nextFloat() < 0.45f) {
                        triggerSingleTapTrick(CompanionRepository.config.value.allowEdgeWalk)
                        idleDurationTarget = 5f
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

                val jumpArc = sin(p * PI.toFloat()) * 42f * density
                charX = rope.tipX()
                charY = rope.tipY() - jumpArc
                charRotationDeg = p * 360f

                scaleX = 1f - 0.18f * sin(p * PI.toFloat() * 2f)
                scaleY = 1f + 0.18f * sin(p * PI.toFloat() * 2f)

                if (p >= 1f) {
                    rope.applyTipImpulse(0f, 45f * density)
                    state = CharacterState.IDLE_HANGING
                    stateTimer = 0f
                }
            }

            CharacterState.TRICK_BUNGEE_JUMP -> {
                val duration = 1.25f
                val p = (stateTimer / duration).coerceIn(0f, 1f)

                val elasticWave = sin(p * PI.toFloat() * 3f) * (1f - p)
                rope.deployedRatio = 1f + 0.45f * elasticWave
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
                val duration = 1.6f
                val p = (stateTimer / duration).coerceIn(0f, 1f)
                rope.applyTipImpulse(cos(p * PI.toFloat() * 2f) * 9f * density * dt * 60f, 0f)
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

            CharacterState.EDGE_WALKING -> {
                rope.deployedRatio = (rope.deployedRatio - dt * 2.5f).coerceAtLeast(0.02f)
                val speed = 220f * density
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

            // 4-Pass Studio Rope: Drop Shadow -> Cel Outline -> Base Cord -> Braided Fiber Texture
            canvas.drawPath(ropePath, ropeShadowPaint)
            canvas.drawPath(ropePath, ropeOutlinePaint)
            canvas.drawPath(ropePath, ropePaint)
            canvas.drawPath(ropePath, ropeBraidPaint)

            // Studio Rope Knot / Clasp where the character's hands grip
            val tipX = rope.x[last]
            val tipY = rope.y[last]
            canvas.drawCircle(tipX, tipY, ropeThicknessPx * 1.15f, accentPaint)
            canvas.drawCircle(tipX, tipY, ropeThicknessPx * 1.15f, cartoonOutlinePaint)
        }

        // 4. Character Rig (Lottie or Studio Cel-Shaded Cartoon Procedural Rig)
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

        // 5. Battery / Notification / Music VFX Overlays
        drawStateVfxOverlays(canvas)
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
        // 1. Swishing S-Curve Cat Tail behind body
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

        // 2. Plump Chibi Cat Body + Cream Belly Patch
        bodyRect.set(-r * 0.72f, r * 0.4f, r * 0.72f, r * 1.55f)
        canvas.drawRoundRect(bodyRect, r * 0.55f, r * 0.55f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.55f, r * 0.55f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.42f, r * 0.62f, r * 0.42f, r * 1.38f)
        canvas.drawOval(bodyRect, secondaryPaint)

        // 3. Cute Dangling Hind Paws
        val kick = sin(stateTimer * 8f) * 3.5f * density
        canvas.drawCircle(-r * 0.38f, r * 1.58f + kick, r * 0.22f, bodyPaint)
        canvas.drawCircle(-r * 0.38f, r * 1.58f + kick, r * 0.22f, cartoonOutlinePaint)
        canvas.drawCircle(r * 0.38f, r * 1.58f - kick, r * 0.22f, bodyPaint)
        canvas.drawCircle(r * 0.38f, r * 1.58f - kick, r * 0.22f, cartoonOutlinePaint)

        // 4. Pointed Left & Right Cat Ears
        for (side in listOf(-1f, 1f)) {
            earPath.reset()
            earPath.moveTo(side * r * 0.25f, -r * 0.35f)
            earPath.lineTo(side * r * 0.92f, -r * 0.95f)
            earPath.lineTo(side * r * 0.95f, r * 0.05f)
            earPath.close()
            canvas.drawPath(earPath, bodyPaint)
            canvas.drawPath(earPath, cartoonOutlinePaint)

            // Pink Inner Ear Triangle
            earPath.reset()
            earPath.moveTo(side * r * 0.38f, -r * 0.28f)
            earPath.lineTo(side * r * 0.80f, -r * 0.75f)
            earPath.lineTo(side * r * 0.82f, -r * 0.05f)
            earPath.close()
            canvas.drawPath(earPath, secondaryPaint)
        }

        // 5. Rounded Cat Head + Calico Ginger Forehead Spot
        bodyRect.set(-r, -r * 0.48f, r, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.75f, r * 0.75f, bodyPaint)

        // Calico Ginger Patch on Right Brow
        bodyRect.set(r * 0.1f, -r * 0.45f, r * 0.85f, r * 0.05f)
        canvas.drawArc(bodyRect, 200f, 140f, true, accentPaint)

        bodyRect.set(-r, -r * 0.48f, r, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.75f, r * 0.75f, cartoonOutlinePaint)

        // 6. Red Collar + Golden Bell
        bodyRect.set(-r * 0.62f, r * 0.58f, r * 0.62f, r * 0.76f)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, accentPaint)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, cartoonOutlinePaint)

        // 7. Studio Anime Eyes + Catchlights + Whiskers + 'w' Cat Mouth
        drawStudioEyes(canvas, r, yCenter = r * 0.12f, isCatMouth = true)

        // Whiskers
        val whiskerWidth = cartoonOutlinePaint.strokeWidth
        cartoonOutlinePaint.strokeWidth = 1.6f * density
        for (side in listOf(-1f, 1f)) {
            canvas.drawLine(side * r * 0.72f, r * 0.18f, side * r * 1.18f, r * 0.10f, cartoonOutlinePaint)
            canvas.drawLine(side * r * 0.72f, r * 0.28f, side * r * 1.20f, r * 0.28f, cartoonOutlinePaint)
        }
        cartoonOutlinePaint.strokeWidth = whiskerWidth

        // 8. Front Paws Reaching Up to Grip the Rope Knot at (0, 0)
        for (side in listOf(-1f, 1f)) {
            canvas.drawCircle(side * r * 0.20f, -r * 0.15f, r * 0.22f, bodyPaint)
            canvas.drawCircle(side * r * 0.20f, -r * 0.15f, r * 0.22f, cartoonOutlinePaint)
            canvas.drawCircle(side * r * 0.20f, -r * 0.15f, r * 0.10f, secondaryPaint)
        }
    }

    private fun drawShadowNinja(canvas: Canvas, r: Float) {
        // 1. Flowing Dual-Tail Crimson Ninja Scarf
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

        // 2. Diagonal Katana Hilt Strapped Behind Back
        canvas.save()
        canvas.rotate(32f)
        bodyRect.set(-r * 0.15f, -r * 0.95f, r * 0.15f, r * 0.6f)
        canvas.drawRoundRect(bodyRect, 3f * density, 3f * density, accentPaint)
        canvas.drawRoundRect(bodyRect, 3f * density, 3f * density, cartoonOutlinePaint)
        canvas.restore()

        // 3. Chibi Ninja Torso & Stepping Legs
        bodyRect.set(-r * 0.65f, r * 0.45f, r * 0.65f, r * 1.48f)
        canvas.drawRoundRect(bodyRect, r * 0.45f, r * 0.45f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.45f, r * 0.45f, cartoonOutlinePaint)

        // 4. Rounded Ninja Hood Head
        bodyRect.set(-r * 0.95f, -r * 0.42f, r * 0.95f, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, bodyPaint)
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
        for (side in listOf(-1f, 1f)) {
            canvas.drawCircle(side * r * 0.18f, -r * 0.14f, r * 0.19f, bodyPaint)
            canvas.drawCircle(side * r * 0.18f, -r * 0.14f, r * 0.19f, cartoonOutlinePaint)
        }
    }

    private fun drawAstroBot(canvas: Canvas, r: Float) {
        // 1. Pulsing Jetpack Thruster Flame Under Bot
        val flameLen = (0.35f + abs(sin(stateTimer * 28f)) * 0.45f) * r
        earPath.reset()
        earPath.moveTo(-r * 0.32f, r * 1.42f)
        earPath.lineTo(0f, r * 1.42f + flameLen)
        earPath.lineTo(r * 0.32f, r * 1.42f)
        earPath.close()
        canvas.drawPath(earPath, accentPaint)

        // 2. Glossy Ceramic Bot Body Pod
        bodyRect.set(-r * 0.68f, r * 0.50f, r * 0.68f, r * 1.45f)
        canvas.drawRoundRect(bodyRect, r * 0.5f, r * 0.5f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.5f, r * 0.5f, cartoonOutlinePaint)

        // Glowing Chest Core
        canvas.drawCircle(0f, r * 0.98f, r * 0.20f, eyePaint)
        canvas.drawCircle(0f, r * 0.98f, r * 0.20f, cartoonOutlinePaint)

        // 3. Top Radar Antenna + Glowing Orb
        canvas.drawLine(0f, -r * 0.45f, 0f, -r * 0.82f, cartoonOutlinePaint)
        canvas.drawCircle(0f, -r * 0.88f, r * 0.16f, accentPaint)
        canvas.drawCircle(0f, -r * 0.88f, r * 0.16f, cartoonOutlinePaint)

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

        // Visor Glass Reflection Highlight
        canvas.drawCircle(-r * 0.52f, -r * 0.06f, r * 0.08f, highlightPaint)

        // 6. Glowing Digital Visor Eyes
        drawStudioEyes(canvas, r, yCenter = r * 0.15f, isCatMouth = false)

        // 7. Clamp Hands Gripping the Rope Knot
        for (side in listOf(-1f, 1f)) {
            canvas.drawCircle(side * r * 0.18f, -r * 0.14f, r * 0.18f, accentPaint)
            canvas.drawCircle(side * r * 0.18f, -r * 0.14f, r * 0.18f, cartoonOutlinePaint)
        }
    }

    private fun drawKitsuneSpirit(canvas: Canvas, r: Float) {
        // 1. Giant Fluffy Fox Tail with Cream Tip
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

        // 2. Fox Body + Cream Chest Fur
        bodyRect.set(-r * 0.68f, r * 0.42f, r * 0.68f, r * 1.50f)
        canvas.drawRoundRect(bodyRect, r * 0.52f, r * 0.52f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.52f, r * 0.52f, cartoonOutlinePaint)

        bodyRect.set(-r * 0.40f, r * 0.58f, r * 0.40f, r * 1.32f)
        canvas.drawOval(bodyRect, secondaryPaint)

        // 3. Oversized Pointed Kitsune Ears
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

        // 4. Fox Head + Cream Cheek Fur
        bodyRect.set(-r * 0.96f, -r * 0.46f, r * 0.96f, r * 0.78f)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, bodyPaint)
        canvas.drawRoundRect(bodyRect, r * 0.72f, r * 0.72f, cartoonOutlinePaint)

        // 5. Golden Shrine Bell at Neck
        canvas.drawCircle(0f, r * 0.68f, r * 0.20f, accentPaint)
        canvas.drawCircle(0f, r * 0.68f, r * 0.20f, cartoonOutlinePaint)

        // 6. Expressive Eyes + Cute Nose
        drawStudioEyes(canvas, r, yCenter = r * 0.14f, isCatMouth = true)

        // 7. Paws Gripping the Rope Knot
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
        // Rosy Cartoon Blush Cheeks
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
                state == CharacterState.REACT_CHARGING_SURGE

        if (isBlinking || isHappyArc) {
            // Happy Curved Anime Eye Arcs (^ ^)
            for (side in listOf(-1f, 1f)) {
                val cx = side * r * 0.38f
                bodyRect.set(cx - r * 0.18f, yCenter - r * 0.12f, cx + r * 0.18f, yCenter + r * 0.14f)
                canvas.drawArc(bodyRect, 200f, 140f, false, cartoonOutlinePaint)
            }
        } else {
            // Big Glossy Studio Eyes with Dual White Specular Catchlights
            val eyeW = r * 0.20f
            val eyeH = r * 0.24f
            for (side in listOf(-1f, 1f)) {
                val cx = side * r * 0.38f
                bodyRect.set(cx - eyeW, yCenter - eyeH, cx + eyeW, yCenter + eyeH)
                canvas.drawOval(bodyRect, eyePaint)

                // Large top-left catchlight + tiny bottom-right catchlight
                canvas.drawCircle(cx - eyeW * 0.32f, yCenter - eyeH * 0.32f, eyeW * 0.38f, highlightPaint)
                canvas.drawCircle(cx + eyeW * 0.30f, yCenter + eyeH * 0.30f, eyeW * 0.18f, highlightPaint)
            }
        }

        if (isCatMouth) {
            // Tiny Nose + 'w' Cat Mouth
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

            CharacterState.REACT_NOTIFICATION -> {
                val pulseRadius = (18f + (stateTimer * 28f) % 26f) * density
                cutoutRingPaint.alpha = (220 * (1f - ((stateTimer * 28f) % 26f) / 26f)).toInt()
                canvas.drawCircle(rope.anchorX, rope.anchorY, pulseRadius, cutoutRingPaint)

                val badgeY = charY - 36f * density + sin(stateTimer * 10f) * 3f * density
                bodyRect.set(
                    charX - 14f * density,
                    badgeY - 9f * density,
                    charX + 14f * density,
                    badgeY + 9f * density
                )
                canvas.drawRoundRect(bodyRect, 6f * density, 6f * density, alertBadgePaint)
                canvas.drawRoundRect(bodyRect, 6f * density, 6f * density, cartoonOutlinePaint)
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
