package com.m57.mboard

class TouchSample(
    val key: Char,
    val x: Float,
    val y: Float,
    val geometry: Geometry,
) {
    data class Bounds(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
    )

    class Geometry(
        bounds: Map<Char, Bounds>,
    ) {
        private val bounds = bounds.toMap()

        internal fun boundsFor(key: Char): Bounds? = bounds[key.lowercaseChar()]
    }

    fun normalizedDistanceSquared(intended: Char): Double? = distanceSquared(x, y, intended)

    internal fun centeredDistanceSquared(intended: Char): Double? {
        val pressed = geometry.boundsFor(key) ?: return null
        return distanceSquared(pressed.centerX, pressed.centerY, intended)
    }

    private fun distanceSquared(
        x: Float,
        y: Float,
        intended: Char,
    ): Double? {
        val target = geometry.boundsFor(intended) ?: return null
        if (!x.isFinite() || !y.isFinite() || !target.centerX.isFinite() || !target.centerY.isFinite() ||
            !target.width.isFinite() || !target.height.isFinite() || target.width <= 0f || target.height <= 0f
        ) {
            return null
        }
        val dx = (x - target.centerX).toDouble() / target.width
        val dy = (y - target.centerY).toDouble() / target.height
        return dx * dx + dy * dy
    }
}

/** A bounded adjustment around the existing edit prior, neutral at the rendered key center. */
internal class TouchCorrectionEvidence(
    private val samples: List<TouchSample?>,
) : WordSuggestions.CorrectionEvidence {
    override fun logWeight(
        typed: String,
        candidate: String,
    ): Double {
        if (typed.length != candidate.length) return 0.0
        var changed = -1
        for (i in typed.indices) {
            if (typed[i].equals(candidate[i], ignoreCase = true)) continue
            // Insertions, deletions, transpositions and multi-edit alignment have no one-to-one touch evidence.
            if (changed >= 0) return 0.0
            changed = i
        }
        if (changed < 0) return 0.0
        val touch = samples.getOrNull(changed) ?: return 0.0
        if (!touch.key.equals(typed[changed], ignoreCase = true)) return 0.0
        val target = candidate[changed]
        val actual = touch.normalizedDistanceSquared(target) ?: return 0.0
        val centered = touch.centeredDistanceSquared(target) ?: return 0.0
        return ((centered - actual) * 0.5).coerceIn(-0.75, 0.75)
    }
}
