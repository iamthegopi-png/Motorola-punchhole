package com.edge60pro.punchholecompanion

import android.content.Context
import android.graphics.Canvas
import androidx.annotation.RawRes
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.RenderMode

class LottieCharacterRig(
    context: Context,
    @RawRes rawResId: Int
) {
    private val density = context.resources.displayMetrics.density
    private val drawable = LottieDrawable()

    var isLoaded = false
        private set

    private val spriteSizePx = (84f * density).toInt()
    private val gripPivotX = spriteSizePx * 0.50f
    private val gripPivotY = spriteSizePx * 0.15f

    private var currentClip: CharacterState? = null
    private var localProgress = 0f
    private var clipDurationSec = 1.0f
    private var isLoopingClip = true

    init {
        drawable.renderMode = RenderMode.HARDWARE
        drawable.setBounds(0, 0, spriteSizePx, spriteSizePx)

        LottieCompositionFactory.fromRawRes(context, rawResId).addListener { composition ->
            drawable.composition = composition
            isLoaded = true
        }
    }

    fun setAnimationState(state: CharacterState) {
        if (currentClip == state) return
        currentClip = state
        localProgress = 0f

        val (markerName, durationSec, loop) = when (state) {
            CharacterState.DESCENDING -> Triple("descend", 1.15f, false)
            CharacterState.IDLE_HANGING -> Triple("idle_hang", 2.0f, true)
            CharacterState.DRAGGING_SLINGSHOT -> Triple("slingshot_aim", 1.0f, true)
            CharacterState.SLINGSHOT_LAUNCH -> Triple("slingshot_launch", 0.65f, false)
            CharacterState.TRICK_BACKFLIP -> Triple("backflip", 0.95f, false)
            CharacterState.TRICK_BUNGEE_JUMP -> Triple("bungee", 1.25f, false)
            CharacterState.TRICK_SWING_WAVE -> Triple("swing_wave", 1.6f, true)
            CharacterState.TRICK_TARZAN_SWING -> Triple("tarzan_swing", 2.4f, true)
            CharacterState.TRICK_UPSIDE_DOWN -> Triple("upside_down", 3.8f, true)
            CharacterState.TRICK_SMOKE_TELEPORT -> Triple("smoke_teleport", 0.55f, false)
            CharacterState.EDGE_WALKING -> Triple("edge_run", 0.65f, true)
            CharacterState.REACT_CHARGING_SURGE -> Triple("charging_surge", 1.2f, true)
            CharacterState.REACT_LOW_BATTERY -> Triple("low_battery_dizzy", 2.4f, true)
            CharacterState.REACT_NOTIFICATION -> Triple("notification_ping", 1.5f, true)
            CharacterState.DANCE_MUSIC_GROOVE -> Triple("music_dance", 1.2f, true)
            CharacterState.CLIMBING_UP -> Triple("climb_up", 0.85f, false)
            CharacterState.HIDDEN -> Triple("idle_hang", 1.0f, false)
            else -> Triple("idle_hang", 1.0f, true)
        }

        clipDurationSec = durationSec
        isLoopingClip = loop

        val composition = drawable.composition
        if (composition != null && composition.getMarker(markerName) != null) {
            drawable.setMinAndMaxFrame(markerName)
        } else if (composition != null) {
            drawable.setMinAndMaxFrame(
                composition.startFrame.toInt(),
                composition.endFrame.toInt()
            )
        }
    }

    fun update(dt: Float) {
        if (!isLoaded) return
        localProgress += dt / clipDurationSec
        if (isLoopingClip) {
            localProgress %= 1.0f
        } else {
            localProgress = localProgress.coerceIn(0f, 1f)
        }
        drawable.progress = localProgress
    }

    fun drawAtRopeTip(
        canvas: Canvas,
        charX: Float,
        charY: Float,
        rotationDeg: Float,
        scaleX: Float,
        scaleY: Float
    ) {
        if (!isLoaded) return
        canvas.save()
        canvas.translate(charX, charY)
        canvas.rotate(rotationDeg)
        canvas.scale(scaleX, scaleY)
        canvas.translate(-gripPivotX, -gripPivotY)
        drawable.draw(canvas)
        canvas.restore()
    }
}
