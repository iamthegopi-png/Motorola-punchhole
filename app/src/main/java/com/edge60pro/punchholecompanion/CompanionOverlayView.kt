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

    private var ropeThicknessPx = 3.2f * density
    private var currentSkin: CharacterSkin = CharacterSkin.SHADOW_NINJA
    private var currentStretchRatio: Float = 1.0f

    private var lottieRig: LottieCharacterRig? = null
    private var currentBatteryPct = 100
    private var activeBpm = 120f

    private val ropePath = Path()
    private val scarfPath = Path()
    private val lightningPath = Path()
    private val bodyRect = RectF()

    private val ropeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Cap.ROUND
        color = Color.argb(90, 0, 0, 0)
    }

    private val ropePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Cap.ROUND
        color = Color.parseColor("#E2C08D")
    }

    private val cutoutRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.parseColor("#00E5FF")
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1E222D")
    }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF5252")
    }

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1E222D")
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
        currentSkin = config.skin

        ropePaint.strokeWidth = ropeThicknessPx
        ropePaint.color = currentSkin.ropeColor
        ropeShadowPaint.strokeWidth = ropeThicknessPx + 2f * density
        bodyPaint.color = currentSkin.bodyColor
        accentPaint.color = currentSkin.accentColor
        eyePaint.color = currentSkin.eyeColor
        limbPaint.color = currentSkin.bodyColor
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
        if (blinkTimer > 3.4f) {
            isBlinking = true
            if (blinkTimer > 3.55f) {
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
                val margin = 22f * density

                edgeProgress += speed * dt * edgeDirection

                val totalRunTime = 5.5f
                if (stateTimer < 1.8f) {
                    charX = edgeProgress.coerceIn(margin, w - margin)
                    charY = rope.anchorY + 12f * density
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
                    charY = rope.anchorY + 12f * density
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

        if (state == CharacterState.DESCENDING || state == CharacterState.CLIMBING_UP) {
            cutoutRingPaint.alpha = (sin(portalScale * PI.toFloat()) * 200).toInt().coerceIn(0, 220)
            canvas.drawCircle(rope.anchorX, rope.anchorY, 19f * density, cutoutRingPaint)
        }

        if (state == CharacterState.DRAGGING_SLINGSHOT && currentStretchRatio > 1.15f) {
            canvas.drawLine(charX, charY, rope.anchorX, rope.anchorY, tensionIndicatorPaint)
        }

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
            canvas.drawPath(ropePath, ropePaint)
        }

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
            drawProceduralSkinCharacter(canvas)
        }

        drawStateVfxOverlays(canvas)
    }

    private fun drawProceduralSkinCharacter(canvas: Canvas) {
        canvas.save()
        canvas.translate(charX, charY)
        canvas.rotate(charRotationDeg)
        canvas.scale(scaleX * portalScale, scaleY * portalScale)

        val r = 17f * density

        when (currentSkin) {
            CharacterSkin.SHADOW_NINJA -> {
                scarfPath.reset()
                scarfPath.moveTo(0f, -r * 0.3f)
                val wave1 = sin(stateTimer * 9f) * 12f * density
                val wave2 = cos(stateTimer * 9f) * 18f * density
                scarfPath.cubicTo(
                    -r * 1.4f, -r * 0.5f + wave1 * 0.5f,
                    -r * 2.1f, -r * 0.2f + wave2,
                    -r * 2.8f, r * 0.2f + wave1
                )
                scarfPath.lineTo(-r * 2.4f, r * 0.55f + wave1)
                scarfPath.close()
                canvas.drawPath(scarfPath, accentPaint)

                val limbSwing = when (state) {
                    CharacterState.EDGE_WALKING -> sin(stateTimer * 20f) * 12f * density
                    CharacterState.TRICK_SWING_WAVE -> sin(stateTimer * 15f) * 14f * density
                    CharacterState.CLIMBING_UP -> sin(stateTimer * 24f) * 10f * density
                    else -> sin(stateTimer * 3.5f) * 3f * density
                }
                limbPaint.color = currentSkin.bodyColor
                limbPaint.strokeWidth = 5f * density
                canvas.drawLine(-r * 0.6f, 0f, -r * 0.9f, -r * 1.1f + limbSwing, limbPaint)
                canvas.drawLine(r * 0.6f, 0f, r * 1.1f, -r * 0.8f - limbSwing, limbPaint)
                canvas.drawLine(-r * 0.4f, r * 0.7f, -r * 0.6f + limbSwing * 0.5f, r * 1.6f, limbPaint)
                canvas.drawLine(r * 0.4f, r * 0.7f, r * 0.6f - limbSwing * 0.5f, r * 1.6f, limbPaint)

                bodyRect.set(-r, -r * 0.85f, r, r * 0.95f)
                canvas.drawRoundRect(bodyRect, r * 0.85f, r * 0.85f, bodyPaint)

                bodyRect.set(-r * 0.98f, -r * 0.52f, r * 0.98f, -r * 0.22f)
                canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, accentPaint)

                val eyeHeight = if (isBlinking) 1.2f * density else 4.2f * density
                val eyeWidth = 4.8f * density
                bodyRect.set(-r * 0.52f, -r * 0.1f - eyeHeight, -r * 0.52f + eyeWidth, -r * 0.1f + eyeHeight)
                canvas.drawOval(bodyRect, eyePaint)
                bodyRect.set(r * 0.52f - eyeWidth, -r * 0.1f - eyeHeight, r * 0.52f, -r * 0.1f + eyeHeight)
                canvas.drawOval(bodyRect, eyePaint)
            }

            CharacterSkin.CYBER_SPIDER -> {
                limbPaint.color = currentSkin.accentColor
                limbPaint.strokeWidth = 3.2f * density
                for (side in listOf(-1f, 1f)) {
                    for (legIdx in 0..3) {
                        val legPhase = sin(stateTimer * 16f + legIdx * 1.3f) * 6f * density
                        val baseAngleY = (-0.4f + legIdx * 0.3f) * r
                        val kneeX = side * r * 1.45f
                        val kneeY = baseAngleY - 6f * density + legPhase * 0.5f
                        val footX = side * r * 1.95f
                        val footY = baseAngleY + 8f * density + legPhase
                        canvas.drawLine(side * r * 0.7f, baseAngleY, kneeX, kneeY, limbPaint)
                        canvas.drawLine(kneeX, kneeY, footX, footY, limbPaint)
                    }
                }
                bodyRect.set(-r * 0.95f, -r * 0.85f, r * 0.95f, r * 0.95f)
                canvas.drawOval(bodyRect, bodyPaint)
                for (offset in listOf(-0.45f, -0.16f, 0.16f, 0.45f)) {
                    canvas.drawCircle(r * offset, -r * 0.1f, 2.6f * density, eyePaint)
                }
            }

            CharacterSkin.ASTRO_CADET -> {
                bodyRect.set(-r * 1.15f, -r * 0.6f, r * 1.15f, r * 0.7f)
                canvas.drawRoundRect(bodyRect, 6f * density, 6f * density, accentPaint)
                bodyRect.set(-r, -r * 0.9f, r, r * 0.95f)
                canvas.drawRoundRect(bodyRect, r * 0.9f, r * 0.9f, bodyPaint)
                bodyRect.set(-r * 0.78f, -r * 0.55f, r * 0.78f, r * 0.25f)
                canvas.drawRoundRect(bodyRect, r * 0.4f, r * 0.4f, accentPaint)
            }

            CharacterSkin.NEON_KODAMA -> {
                for (orb in 0..2) {
                    val angle = stateTimer * 4.5f + orb * 2.09f
                    val ox = cos(angle) * r * 1.45f
                    val oy = sin(angle) * r * 1.1f
                    accentPaint.alpha = 180
                    canvas.drawCircle(ox, oy, 4f * density, accentPaint)
                }
                accentPaint.alpha = 255
                bodyRect.set(-r * 0.9f, -r * 0.95f, r * 0.9f, r * 0.9f)
                canvas.drawRoundRect(bodyRect, r * 0.9f, r * 0.7f, bodyPaint)
                canvas.drawCircle(-r * 0.36f, -r * 0.15f, 4.5f * density, eyePaint)
                canvas.drawCircle(r * 0.36f, -r * 0.15f, 4.5f * density, eyePaint)
            }
        }

        canvas.restore()
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
                    charY + 38f * density,
                    batteryTextPaint
                )
            }

            CharacterState.REACT_LOW_BATTERY -> {
                val blinkAlpha = ((sin(stateTimer * 10f) + 1f) * 0.5f * 255).toInt().coerceIn(50, 255)
                lowBatteryPaint.alpha = blinkAlpha
                val bx = charX - 12f * density
                val by = charY - 36f * density
                bodyRect.set(bx, by, bx + 24f * density, by + 12f * density)
                canvas.drawRoundRect(bodyRect, 3f * density, 3f * density, lowBatteryPaint)
            }

            CharacterState.REACT_NOTIFICATION -> {
                val pulseRadius = (18f + (stateTimer * 28f) % 26f) * density
                cutoutRingPaint.alpha = (220 * (1f - ((stateTimer * 28f) % 26f) / 26f)).toInt()
                canvas.drawCircle(rope.anchorX, rope.anchorY, pulseRadius, cutoutRingPaint)

                val badgeY = charY - 34f * density + sin(stateTimer * 10f) * 3f * density
                bodyRect.set(
                    charX - 14f * density,
                    badgeY - 9f * density,
                    charX + 14f * density,
                    badgeY + 9f * density
                )
                canvas.drawRoundRect(bodyRect, 6f * density, 6f * density, alertBadgePaint)
            }

            CharacterState.DANCE_MUSIC_GROOVE -> {
                canvas.save()
                canvas.translate(charX, charY)
                canvas.rotate(charRotationDeg)
                val r = 18.5f * density
                bodyRect.set(-r, -r * 0.95f, r, r * 0.5f)
                canvas.drawArc(bodyRect, 180f, 180f, false, headphonePaint)
                canvas.drawCircle(-r, -r * 0.15f, 4.5f * density, eyePaint)
                canvas.drawCircle(r, -r * 0.15f, 4.5f * density, eyePaint)
                canvas.restore()

                for (i in 0..1) {
                    val phase = (stateTimer * 0.9f + i * 0.5f) % 1.0f
                    val noteX = charX + (if (i == 0) -1 else 1) * (18f + phase * 16f) * density + sin(phase * 6f) * 5f * density
                    val noteY = charY - (15f + phase * 42f) * density
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
