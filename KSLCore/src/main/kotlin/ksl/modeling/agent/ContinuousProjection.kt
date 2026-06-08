/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.agent

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 *  A 2D Euclidean projection: each agent in the context has an
 *  optional [Point2D] position. The projection tracks positions in a
 *  map keyed by agent reference; positions are set explicitly via
 *  [placeAt] / [moveTo] from user code.
 *
 *  Spatial queries are accelerated by an internal **uniform spatial
 *  hash**: the plane is partitioned into square cells of [cellSize]
 *  units, and agents are bucketed into the cell their position falls
 *  into. [within] scans only the cells overlapping the query disk
 *  rather than every agent; [nearest] does the same via progressive
 *  radius doubling. Insertion / move / remove are O(1) amortized.
 *
 *  The default [cellSize] is `min(xSize, ySize) / 10.0` — ten cells
 *  along the smaller dimension. Tune for your model: cell size
 *  roughly equal to typical query radius gives near-optimal
 *  performance. Smaller cells cost more bookkeeping but tighter
 *  query bounding boxes; larger cells cost more agents-per-cell to
 *  filter.
 *
 *  Boundaries: positions are not enforced to be inside [xRange] /
 *  [yRange] — the ranges describe the projection's logical domain
 *  but [placeAt] / [moveTo] accept any coordinates. When [torus] is
 *  true, [distance] computes wrapped distance using the ranges as
 *  the torus dimensions, and the spatial hash wraps bucket
 *  coordinates accordingly.
 *
 *  @param context the context whose agents this projection positions
 *  @param xRange logical x bounds (used for torus wrapping)
 *  @param yRange logical y bounds (used for torus wrapping)
 *  @param torus if true, distances wrap at the bounds
 *  @param cellSize side length of each spatial-hash cell. Defaults
 *    to `min(xSize, ySize) / 10.0`, or 1.0 for degenerate ranges.
 *    Must be positive.
 *  @param name display name
 */
class ContinuousProjection<A : AgentLike> @JvmOverloads constructor(
    val context: AgentModel.Context<A>,
    val xRange: ClosedRange<Double>,
    val yRange: ClosedRange<Double>,
    val torus: Boolean = false,
    val cellSize: Double = run {
        val xSize = xRange.endInclusive - xRange.start
        val ySize = yRange.endInclusive - yRange.start
        val smaller = minOf(xSize, ySize)
        if (smaller > 0.0) smaller / Defaults.cellSizeDivisor else 1.0
    },
    override val name: String = "continuous",
) : Projection<A> {

    /**
     *  Mutable global defaults for [ContinuousProjection] internals.
     */
    companion object Defaults {
        /**
         *  Divisor used to derive the default spatial-hash cell size
         *  from the smaller of the x and y ranges: `cellSize =
         *  min(xSize, ySize) / cellSizeDivisor`. Tunes the trade-off
         *  between bucket count and per-bucket occupancy for `within`
         *  / `nearest` queries. Must be positive.
         */
        var cellSizeDivisor: Double by positive(10.0)

        /**
         *  Multiplicative growth factor used by [nearest] when its
         *  initial guess radius doesn't return enough candidates and
         *  it expands the search. Must be strictly greater than 1.0;
         *  otherwise the radius never grows (or shrinks).
         */
        var nearestRadiusGrowthFactor: Double by greaterThan(1.0, 2.0)
    }

    init {
        require(cellSize > 0.0) { "cellSize must be positive; was $cellSize" }
    }

    // ── Internal storage ────────────────────────────────────────────────────

    /** Authoritative agent → position map. */
    private val positions: MutableMap<A, Point2D> = mutableMapOf()

    /**
     *  Spatial-hash buckets: (col, row) → set of agents currently
     *  bucketed in that cell. A set rather than a list so that
     *  duplicate-place calls (same agent, same cell) are idempotent.
     */
    private val buckets: MutableMap<Pair<Int, Int>, MutableSet<A>> = mutableMapOf()

    /**
     *  Per-agent cached bucket index. Speeds up [moveTo] when the
     *  agent stays in the same cell (no bucket-list mutation needed).
     */
    private val agentBucket: MutableMap<A, Pair<Int, Int>> = mutableMapOf()

    private val xSize: Double = xRange.endInclusive - xRange.start
    private val ySize: Double = yRange.endInclusive - yRange.start
    private val numCols: Int = if (xSize > 0.0) ceil(xSize / cellSize).toInt().coerceAtLeast(1) else 1
    private val numRows: Int = if (ySize > 0.0) ceil(ySize / cellSize).toInt().coerceAtLeast(1) else 1

    init {
        context.addProjection(this)
    }

    /** Number of agents currently placed in this projection. */
    val size: Int
        get() = positions.size

    // ── Bucketing helpers ───────────────────────────────────────────────────

    /**
     *  Compute the (col, row) bucket key for a position. Uses
     *  `Math.floor` rather than truncation so negative coordinates
     *  map correctly. For torus projections the result is *not*
     *  wrapped here — wrapping happens at query lookup time so that
     *  raw out-of-range placements still produce a well-defined
     *  bucket without modifying the user's stored coordinate.
     */
    private fun bucketOf(x: Double, y: Double): Pair<Int, Int> {
        val col = floor((x - xRange.start) / cellSize).toInt()
        val row = floor((y - yRange.start) / cellSize).toInt()
        return col to row
    }

    /**
     *  Wrap bucket coordinates into `[0, numCols) x [0, numRows)` when
     *  [torus] is true; pass-through otherwise. Used at lookup time
     *  so that query rectangles wrap around the bounds.
     */
    private fun wrap(col: Int, row: Int): Pair<Int, Int> {
        if (!torus) return col to row
        val c = ((col % numCols) + numCols) % numCols
        val r = ((row % numRows) + numRows) % numRows
        return c to r
    }

    // ── Placement / movement ────────────────────────────────────────────────

    /** Place [agent] at the given coordinates. Idempotent — equivalent to [moveTo]. */
    fun placeAt(agent: A, x: Double, y: Double) {
        placeAt(agent, Point2D(x, y))
    }

    fun placeAt(agent: A, point: Point2D) {
        val raw = bucketOf(point.x, point.y)
        val newBucket = wrap(raw.first, raw.second)
        val oldBucket = agentBucket[agent]
        if (oldBucket != null && oldBucket != newBucket) {
            buckets[oldBucket]?.remove(agent)
            if (buckets[oldBucket]?.isEmpty() == true) buckets.remove(oldBucket)
        }
        positions[agent] = point
        agentBucket[agent] = newBucket
        buckets.getOrPut(newBucket) { mutableSetOf() }.add(agent)
    }

    /** Update [agent]'s position. Identical to [placeAt]. */
    fun moveTo(agent: A, x: Double, y: Double) = placeAt(agent, x, y)
    fun moveTo(agent: A, point: Point2D) = placeAt(agent, point)

    /**
     *  Return the position of [agent], or null if the agent has no
     *  assigned position (never placed, or has left the context).
     */
    fun positionOf(agent: A): Point2D? = positions[agent]

    // ── Distance ────────────────────────────────────────────────────────────

    /**
     *  Distance between two agents. Returns `Double.NaN` if either
     *  agent has no assigned position. Uses Euclidean distance, or
     *  wrapped Euclidean if [torus] is true.
     */
    fun distance(a: A, b: A): Double {
        val pa = positions[a] ?: return Double.NaN
        val pb = positions[b] ?: return Double.NaN
        return distance(pa, pb)
    }

    /**
     *  Distance between two points under this projection's metric
     *  (Euclidean, with torus wrap if enabled).
     */
    fun distance(a: Point2D, b: Point2D): Double {
        if (!torus) return a.distanceTo(b)
        val xSpan = xSize
        val ySpan = ySize
        val dx = wrapDelta(abs(a.x - b.x), xSpan)
        val dy = wrapDelta(abs(a.y - b.y), ySpan)
        return hypot(dx, dy)
    }

    /**
     *  Signed shortest-direction delta from [from] to [to] under this
     *  projection's geometry. For a non-torus projection this is the
     *  trivial component-wise subtraction `to - from`. For a torus,
     *  each component picks whichever wrap (positive or negative) is
     *  shorter — so a boid at x = 99 querying a peer at x = 1 in a
     *  100-wide torus gets `dx = +2`, not `-98`.
     *
     *  Use this for direction-aware forces (separation, cohesion)
     *  where averaging absolute positions would break across the
     *  wrap boundary.
     */
    fun delta(from: Point2D, to: Point2D): Point2D {
        if (!torus) return to - from
        return Point2D(signedWrapDelta(to.x - from.x, xSize), signedWrapDelta(to.y - from.y, ySize))
    }

    private fun signedWrapDelta(d: Double, span: Double): Double {
        if (span <= 0.0) return d
        // Reduce into [0, span) first so the result is correct even when
        // the raw delta exceeds one period (positions may be placed
        // outside the declared range — they are stored verbatim).
        val m = ((d % span) + span) % span
        return if (m > span / 2.0) m - span else m
    }

    private fun wrapDelta(d: Double, span: Double): Double {
        if (span <= 0.0) return d
        // Shortest wrapped magnitude on one axis, correct for any d
        // (including |d| > span from out-of-range coordinates).
        val m = ((d % span) + span) % span
        return minOf(m, span - m)
    }

    // ── Spatial queries (use the bucket index) ──────────────────────────────

    /**
     *  All agents whose position is within [radius] of [center],
     *  ordered by distance. Includes any agent exactly at [center].
     *  Uses the spatial hash: only buckets overlapping the query
     *  disk are scanned.
     */
    fun within(center: Point2D, radius: Double): List<A> {
        require(radius >= 0.0) { "radius must be non-negative" }
        // Pre-wrap the center bucket on a torus. This does not change the
        // set of visited buckets (wrap(cc+dc) == wrap(wrap(cc)+dc) by
        // modular arithmetic) but keeps the offset arithmetic in-range,
        // avoiding Int overflow for far-out-of-range query coordinates.
        val rawCenter = bucketOf(center.x, center.y)
        val (cc, cr) = if (torus) wrap(rawCenter.first, rawCenter.second) else rawCenter
        val cellRadius = ceil(radius / cellSize).toInt()
        // For torus, never scan more than the full grid (avoids duplicate visits
        // when cellRadius >= numCols/2).
        val effectiveRadius = if (torus) minOf(cellRadius, maxOf(numCols, numRows)) else cellRadius

        // Use a set to dedupe in case torus wrapping causes the same cell to
        // appear in multiple offsets (small grid, large query).
        val visited = HashSet<Pair<Int, Int>>()
        val collected = mutableListOf<Pair<A, Double>>()
        for (dr in -effectiveRadius..effectiveRadius) {
            for (dc in -effectiveRadius..effectiveRadius) {
                val key = wrap(cc + dc, cr + dr)
                if (!visited.add(key)) continue
                val bucket = buckets[key] ?: continue
                for (agent in bucket) {
                    val pos = positions[agent] ?: continue
                    val d = distance(center, pos)
                    if (d <= radius) collected.add(agent to d)
                }
            }
        }
        return collected.sortedBy { it.second }.map { it.first }
    }

    /**
     *  All agents within [radius] of [agent]'s position (excluding
     *  the agent itself), ordered by distance.
     */
    fun neighborsOf(agent: A, radius: Double): List<A> {
        val p = positions[agent] ?: return emptyList()
        return within(p, radius).filter { it !== agent }
    }

    /**
     *  The [k] nearest agents to [center], ordered by distance.
     *  Implemented as a progressive radius doubling on top of
     *  [within]: start with a small radius (one cell), double until
     *  enough candidates are found, then trim to [k]. Bounded
     *  scanning rather than the O(n) linear sort the previous
     *  implementation used.
     *
     *  If [k] is larger than the number of placed agents, returns
     *  all of them.
     */
    fun nearest(center: Point2D, k: Int): List<A> {
        require(k >= 0) { "k must be non-negative" }
        if (k == 0 || positions.isEmpty()) return emptyList()

        // Fast path: if k >= total agents, just return them all sorted.
        if (k >= positions.size) {
            return positions.asSequence()
                .map { (a, p) -> a to distance(center, p) }
                .sortedBy { it.second }
                .map { it.first }
                .toList()
        }

        // Progressive radius doubling. Safety cap based on the
        // projection's domain.
        var radius = cellSize
        val maxRadius = (hypot(xSize, ySize)) * 2.0 + cellSize
        while (radius <= maxRadius) {
            val candidates = within(center, radius)
            if (candidates.size >= k) {
                return candidates.take(k)
            }
            radius *= Defaults.nearestRadiusGrowthFactor
        }

        // Fallback: linear scan everything. Should be unreachable
        // when positions are inside the projection's domain; the
        // safety cap covers pathological cases.
        return positions.asSequence()
            .map { (a, p) -> a to distance(center, p) }
            .sortedBy { it.second }
            .take(k)
            .map { it.first }
            .toList()
    }

    override fun onAgentLeft(agent: A) {
        positions.remove(agent)
        val bucket = agentBucket.remove(agent) ?: return
        buckets[bucket]?.remove(agent)
        if (buckets[bucket]?.isEmpty() == true) buckets.remove(bucket)
    }

    // ── Diagnostics / introspection (useful for tests and tuning) ───────────

    /**
     *  Number of non-empty buckets. Indicates how well-distributed
     *  agents are across the spatial hash. Pure diagnostic — useful
     *  for tuning [cellSize] on a model with known typical
     *  population.
     */
    val occupiedBucketCount: Int
        get() = buckets.size

    /** Maximum occupancy of any single bucket. Diagnostic for [cellSize] tuning. */
    val maxBucketOccupancy: Int
        get() = buckets.values.maxOfOrNull { it.size } ?: 0
}
