package com.faroquetech.aospisland

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tiny critically-configurable damped spring (mass = 1). No external dependencies.
 * stiffness: higher = faster. dampingRatio: 1 = no overshoot, <1 = bouncy (iOS island feel ~0.72-0.8).
 */
class Spring(var value: Float, var stiffness: Float = 260f, var dampingRatio: Float = 0.75f, private val restDelta: Float = 0.4f) {
    var target: Float = value
    var velocity: Float = 0f

    val settled: Boolean get() = abs(value - target) < restDelta && abs(velocity) < restDelta * 4f

    fun snap(v: Float) { value = v; target = v; velocity = 0f }

    /** Advance by dt seconds. */
    fun step(dt: Float) {
        var remaining = dt.coerceIn(0f, 0.05f)
        val h = 1f / 240f
        val c = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val s = if (remaining < h) remaining else h
            val acc = -stiffness * (value - target) - c * velocity
            velocity += acc * s
            value += velocity * s
            remaining -= s
        }
        if (settled) { value = target; velocity = 0f }
    }
}
