package com.m57.mboard

import kotlin.math.abs

/** Converts horizontal positions in dp to relative caret steps, independent of touch event frequency. */
internal class CursorDrag {
    private var anchor = 0f
    private var furthest = 0f
    private var direction = 0
    private var sent = 0

    fun start(x: Float) {
        anchor = x
        furthest = x
        direction = 0
        sent = 0
    }

    fun move(x: Float): Int {
        if (direction == 0) {
            direction =
                when {
                    x > anchor -> 1
                    x < anchor -> -1
                    else -> return 0
                }
        }
        val travel = (x - furthest) * direction
        if (travel < 0f) {
            if (-travel <= REVERSE_SLOP_DP) return 0
            anchor = furthest - direction * REVERSE_SLOP_DP
            direction = -direction
            sent = 0
        }
        furthest = x

        // Integrate a gain of 1x for the fine zone, ramping smoothly up to 2.25x on sustained travel.
        val distance = abs(x - anchor)
        val extra = (distance - FINE_TRAVEL_DP).coerceAtLeast(0f)
        val ramp = extra.coerceAtMost(GAIN_RAMP_DP)
        val weighted = distance + (MAX_GAIN - 1f) * (ramp * ramp / (2 * GAIN_RAMP_DP) + extra - ramp)
        val target = (weighted / STEP_DP).toInt()
        val steps = (target - sent) * direction
        sent = target
        return steps
    }

    private companion object {
        const val STEP_DP = 12f
        const val REVERSE_SLOP_DP = 3f
        const val FINE_TRAVEL_DP = 36f
        const val GAIN_RAMP_DP = 72f
        const val MAX_GAIN = 2.25f
    }
}
