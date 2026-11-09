package com.edge60pro.punchholecompanion

import kotlin.math.atan2
import kotlin.math.hypot

class VerletRopeEngine(val nodeCount: Int = 18) {
    val x = FloatArray(nodeCount)
    val y = FloatArray(nodeCount)
    private val oldX = FloatArray(nodeCount)
    private val oldY = FloatArray(nodeCount)

    var anchorX = 540f
    var anchorY = 45f
    var totalLengthPx = 450f
    var deployedRatio = 0f

    var isTipPinned: Boolean = false
    var pinnedTipX: Float = 540f
    var pinnedTipY: Float = 300f

    // Tuned for slow, graceful, weighted pendulum arcs
    private val gravity = 1080f
    private val linearDamping = 0.968f
    private val airDrag = 0.0018f
    private val constraintIterations = 8

    private var smoothVelX = 0f
    private var smoothVelY = 0f

    fun resetToAnchor(ax: Float, ay: Float) {
        anchorX = ax
        anchorY = ay
        isTipPinned = false
        smoothVelX = 0f
        smoothVelY = 0f
        for (i in 0 until nodeCount) {
            x[i] = ax
            y[i] = ay
            oldX[i] = ax
            oldY[i] = ay
        }
    }

    /**
     * Distributes force smoothly across the lower third of the rope so it curves
     * like a real weighted cord instead of snapping sharply at the bottom knot.
     */
    fun applyTipImpulse(vx: Float, vy: Float) {
        val last = nodeCount - 1
        val startNode = (nodeCount * 2) / 3
        for (i in startNode..last) {
            val weight = (i - startNode + 1).toFloat() / (last - startNode + 1).toFloat()
            oldX[i] -= vx * weight * 0.55f
            oldY[i] -= vy * weight * 0.55f
        }
    }

    fun pinTip(xPos: Float, yPos: Float) {
        isTipPinned = true
        pinnedTipX = xPos
        pinnedTipY = yPos
    }

    fun releaseSlingshot(flingVxPxPerSec: Float, flingVyPxPerSec: Float, dt: Float = 0.016f): Float {
        isTipPinned = false
        val last = nodeCount - 1
        val dx = anchorX - x[last]
        val dy = anchorY - y[last]
        val currentDist = hypot(dx, dy)
        val restLen = (totalLengthPx * deployedRatio).coerceAtLeast(1f)
        val stretchRatio = currentDist / restLen

        val overStretch = (stretchRatio - 1.0f).coerceAtLeast(0f)
        val dirX = dx / currentDist.coerceAtLeast(1f)
        val dirY = dy / currentDist.coerceAtLeast(1f)

        val recoilSpeed = overStretch * 32f
        val flingStepX = (flingVxPxPerSec * dt).coerceIn(-42f, 42f)
        val flingStepY = (flingVyPxPerSec * dt).coerceIn(-42f, 42f)

        for (i in (nodeCount / 2) until nodeCount) {
            val weight = (i.toFloat() / last)
            oldX[i] = x[i] - (dirX * recoilSpeed + flingStepX) * weight
            oldY[i] = y[i] - (dirY * recoilSpeed + flingStepY) * weight
        }

        return stretchRatio
    }

    fun step(dt: Float) {
        val clampedDt = dt.coerceIn(0.004f, 0.022f)
        val activeSegmentLen = (totalLengthPx * deployedRatio.coerceAtLeast(0.02f)) / (nodeCount - 1)

        val last = nodeCount - 1
        val prevTipX = x[last]
        val prevTipY = y[last]

        // 1. Verlet integration with quadratic aerodynamic drag (prevents fast snapping)
        for (i in 1 until nodeCount) {
            var vx = (x[i] - oldX[i]) * linearDamping
            var vy = (y[i] - oldY[i]) * linearDamping

            val speed = hypot(vx, vy)
            if (speed > 0.01f) {
                val dragFactor = (1f - airDrag * speed).coerceIn(0.75f, 1f)
                vx *= dragFactor
                vy *= dragFactor
            }

            oldX[i] = x[i]
            oldY[i] = y[i]
            x[i] += vx
            y[i] += vy + gravity * clampedDt * clampedDt
        }

        x[0] = anchorX
        y[0] = anchorY

        if (isTipPinned) {
            x[last] = pinnedTipX
            y[last] = pinnedTipY
            oldX[last] = pinnedTipX
            oldY[last] = pinnedTipY
        }

        // 2. Multi-pass distance relaxation for silky catenary curves
        for (iter in 0 until constraintIterations) {
            for (i in 0 until nodeCount - 1) {
                val dx = x[i + 1] - x[i]
                val dy = y[i + 1] - y[i]
                val dist = hypot(dx, dy).coerceAtLeast(0.0001f)
                val diff = (activeSegmentLen - dist) / dist

                if (i == 0) {
                    x[i + 1] += dx * diff
                    y[i + 1] += dy * diff
                } else {
                    val halfDiff = diff * 0.5f
                    x[i] -= dx * halfDiff
                    y[i] -= dy * halfDiff
                    x[i + 1] += dx * halfDiff
                    y[i + 1] += dy * halfDiff
                }
            }
            if (isTipPinned) {
                x[last] = pinnedTipX
                y[last] = pinnedTipY
            }
        }

        // 3. Track smoothed tip velocity for secondary follow-through (tails, scarves, ears)
        val rawVx = (x[last] - prevTipX) / clampedDt
        val rawVy = (y[last] - prevTipY) / clampedDt
        smoothVelX = smoothVelX * 0.86f + rawVx * 0.14f
        smoothVelY = smoothVelY * 0.86f + rawVy * 0.14f
    }

    fun tipX(): Float = x[nodeCount - 1]
    fun tipY(): Float = y[nodeCount - 1]
    fun tipVelocityX(): Float = smoothVelX
    fun tipVelocityY(): Float = smoothVelY

    fun tipAngleRad(): Float {
        val n = nodeCount - 1
        return atan2((x[n] - x[n - 3]), (y[n] - y[n - 3]))
    }
}
