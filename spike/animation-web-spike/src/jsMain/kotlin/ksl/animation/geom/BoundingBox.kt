package ksl.animation.geom

/**
 * PHASE S SPIKE — the shape `BoundingBox` needs to take in Phase 1.
 *
 * Replaces `java.awt.geom.Rectangle2D.Double` in the replay layer. The spike validates that this is the
 * only geometry type the *player-relevant* core needs: the authoring-side files that used the richer
 * `Rectangle2D` API (`createUnion`, `.maxX`, mutating `.add`) turned out not to be player-relevant at all.
 *
 * Deliberately immutable — `Rectangle2D.Double.add()` mutates in place, and two of the call sites relied
 * on that. `union` returning a new box is the shape that ports cleanly.
 */
data class BoundingBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {

    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY

    fun union(other: BoundingBox): BoundingBox = BoundingBox(
        minOf(minX, other.minX), minOf(minY, other.minY),
        maxOf(maxX, other.maxX), maxOf(maxY, other.maxY)
    )

    fun including(x: Double, y: Double): BoundingBox =
        BoundingBox(minOf(minX, x), minOf(minY, y), maxOf(maxX, x), maxOf(maxY, y))

    fun grown(margin: Double): BoundingBox =
        BoundingBox(minX - margin, minY - margin, maxX + margin, maxY + margin)

    fun contains(x: Double, y: Double): Boolean = x in minX..maxX && y in minY..maxY

    companion object {
        /** Null-safe union, so accumulation over possibly-empty sources needs no sentinel box. */
        fun union(a: BoundingBox?, b: BoundingBox?): BoundingBox? = when {
            a == null -> b
            b == null -> a
            else -> a.union(b)
        }

        /**
         * Accumulates over (x, y) pairs, skipping NaN — the pattern `MotionTrack.bounds()` and
         * `ReplayModel.coordinateBounds()` each open-code today. Returns null when every point was NaN.
         */
        fun of(points: Sequence<Pair<Double, Double>>): BoundingBox? {
            var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            for ((x, y) in points) {
                if (x.isNaN() || y.isNaN()) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            return if (minX > maxX) null else BoundingBox(minX, minY, maxX, maxY)
        }
    }
}
