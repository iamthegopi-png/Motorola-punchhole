package com.edge60pro.punchholecompanion

import kotlin.math.atan2
import kotlin.math.hypot

class VerletRopeEngine(val nodeCount: Int = 14) {
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

    private val gravity = 1950f
    private val damping = 0.985f
    private val constraintIterations = 6

    fun resetToAnchor(ax: Float, ay: Float) {
        anchorX = ax
        anchorY = ay
        isTipPinned = false
        for (i in 0 until nodeCount) {
            x[i] = ax
            y[i] = ay
            oldX[i] = ax
            oldY[i] = ay
        }
    }

    fun applyTipImpulse(vx: Float, vy: Float) {
        val last = nodeCount - 1
        oldX[last] -= vx
        oldY[last] -= vy
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

        val recoilSpeed = overStretch * 48f
        val flingStepX = (flingVxPxPerSec * dt).coerceIn(-65f, 65f)
        val flingStepY = (flingVyPxPerSec * dt).coerceIn(-65f, 65f)

        for (i in (nodeCount - 4).coerceAtLeast(1) until nodeCount) {
            val weight = (i.toFloat() / last)
            oldX[i] = x[i] - (dirX * recoilSpeed + flingStepX) * weight
            oldY[i] = y[i] - (dirY * recoilSpeed + flingStepY) * weight
        }

        return stretchRatio
    }

    fun step(dt: Float) {
        val clampedDt = dt.coerceIn(0.004f, 0.025f)
        val activeSegmentLen = (totalLengthPx * deployedRatio.coerceAtLeast(0.02f)) / (nodeCount - 1)

        for (i in 1 until nodeCount) {
            val vx = (x[i] - oldX[i]) * damping
            val vy = (y[i] - oldY[i]) * damping
            oldX[i] = x[i]
            oldY[i] = y[i]
            x[i] += vx
            y[i] += vy + gravity * clampedDt * clampedDt
        }

        x[0] = anchorX
        y[0] = anchorY

        if (isTipPinned) {
            val last = nodeCount - 1
            x[last] = pinnedTipX
            y[last] = pinnedTipY
            oldX[last] = pinnedTipX
            oldY[last] = pinnedTipY
        }

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
                val last = nodeCount - 1
                x[last] = pinnedTipX
                y[last] = pinnedTipY
            }
        }
    }

    fun tipX(): Float = x[nodeCount - 1]
    fun tipY(): Float = y[nodeCount - 1]

    fun tipAngleRad(): Float {
        val n = nodeCount - 1
        return atan2((x[n] - x[n - 2]), (y[n] - y[n - 2]))
    }
}
