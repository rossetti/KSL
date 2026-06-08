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
import kotlin.math.sqrt

/**
 *  A 3D Euclidean projection — the volumetric analog of
 *  [ContinuousProjection]. Each agent in the context has an optional
 *  [Point3D] position; positions are set explicitly via [placeAt] /
 *  [moveTo] from user code.
 *
 *  Spatial queries use an internal **uniform 3D spatial hash**: the
 *  volume is partitioned into cubic cells of [cellSize] units, agents
 *  are bucketed by the cell their position falls into, and [within]
 *  scans only the cells overlapping the query sphere rather than
 *  every agent. [nearest] uses progressive radius doubling on top of
 *  [within]. Insertion / move / remove are O(1) amortized.
 *
 *  The default [cellSize] is `min(xSize, ySize, zSize) / cellSizeDivisor`,
 *  where the divisor defaults to 10 (settable on [Defaults]). For
 *  typical UAV-airspace use cases — large horizontal extent, sparse
 *  vertical density — the §13.6 Q3 resolution is to start with the
 *  direct 3D bucket map and benchmark on a real model before
 *  considering an octree. Sparse-layer scenarios still work; they
 *  just allocate a `Triple`-keyed map entry per occupied layer (cheap
 *  in absolute terms — `Triple` of Ints is ~32 bytes plus map
 *  overhead).
 *
 *  Boundaries: positions are not enforced to be inside [xRange] /
 *  [yRange] / [zRange] — the ranges describe the projection's logical
 *  domain but [placeAt] / [moveTo] accept any coordinates. When
 *  [torus] is true, distances wrap on all three axes and bucket
 *  coordinates wrap accordingly. (A "ground+ceiling" model — wrap
 *  horizontally only, hard boundary vertically — should use
 *  `torus = false` and handle the vertical clamp in the user's
 *  motion loop.)
 *
 *  @param context the context whose agents this projection positions
 *  @param xRange logical x bounds
 *  @param yRange logical y bounds
 *  @param zRange logical z bounds
 *  @param torus if true, distances wrap at all three boundaries
 *  @param cellSize cubic-cell side length. Defaults to
 *    `min(xSize, ySize, zSize) / cellSizeDivisor`, or 1.0 for
 *    degenerate ranges. Must be positive.
 *  @param name display name
 */
class ContinuousVolume<A : AgentLike> @JvmOverloads constructor(
    val context: AgentModel.Context<A>,
    val xRange: ClosedRange<Double>,
    val yRange: ClosedRange<Double>,
    val zRange: ClosedRange<Double>,
    val torus: Boolean = false,
    val cellSize: Double = run {
        val xs = xRange.endInclusive - xRange.start
        val ys = yRange.endInclusive - yRange.start
        val zs = zRange.endInclusive - zRange.start
        val smallest = minOf(xs, ys, zs)
        if (smallest > 0.0) smallest / Defaults.cellSizeDivisor else 1.0
    },
    override val name: String = "continuousVolume",
) : Projection<A> {

    /**
     *  Mutable global defaults for [ContinuousVolume] internals,
     *  mirroring [ContinuousProjection.Defaults].
     */
    companion object Defaults {
        /**
         *  Divisor used to derive the default spatial-hash cell size
         *  from the smallest of the three ranges: `cellSize =
         *  min(xSize, ySize, zSize) / cellSizeDivisor`. Must be
         *  positive.
         */
        var cellSizeDivisor: Double by positive(10.0)

        /**
         *  Multiplicative growth factor used by [nearest] when its
         *  initial guess radius doesn't return enough candidates.
         *  Must be strictly greater than 1.0; otherwise the radius
         *  never grows.
         */
        var nearestRadiusGrowthFactor: Double by greaterThan(1.0, 2.0)
    }

    init {
        require(cellSize > 0.0) { "cellSize must be positive; was $cellSize" }
    }

    // ── Internal storage ────────────────────────────────────────────────────

    /** Authoritative agent → position map. */
    private val positions: MutableMap<A, Point3D> = mutableMapOf()

    /**
     *  Spatial-hash buckets: (col, row, layer) → set of agents
     *  currently bucketed there. Direct 3D bucket map (the §13.6 Q3
     *  resolution); benchmark before switching to an octree for
     *  sparse-vertical scenarios.
     */
    private val buckets: MutableMap<Triple<Int, Int, Int>, MutableSet<A>> = mutableMapOf()

    /**
     *  Per-agent cached bucket index. Speeds up [moveTo] when the
     *  agent stays in the same cell.
     */
    private val agentBucket: MutableMap<A, Triple<Int, Int, Int>> = mutableMapOf()

    private val xSize: Double = xRange.endInclusive - xRange.start
    private val ySize: Double = yRange.endInclusive - yRange.start
    private val zSize: Double = zRange.endInclusive - zRange.start
    private val numCols: Int = if (xSize > 0.0) ceil(xSize / cellSize).toInt().coerceAtLeast(1) else 1
    private val numRows: Int = if (ySize > 0.0) ceil(ySize / cellSize).toInt().coerceAtLeast(1) else 1
    private val numLayers: Int = if (zSize > 0.0) ceil(zSize / cellSize).toInt().coerceAtLeast(1) else 1

    init {
        context.addProjection(this)
    }

    /** Number of agents currently placed in this projection. */
    val size: Int
        get() = positions.size

    // ── Bucketing helpers ───────────────────────────────────────────────────

    /**
     *  Compute the (col, row, layer) bucket key for a position. Uses
     *  `Math.floor` rather than truncation so negative coordinates
     *  map correctly. For torus projections the result is *not*
     *  wrapped here — wrapping happens at query lookup time so that
     *  raw out-of-range placements produce a well-defined bucket
     *  without modifying the user's stored coordinate.
     */
    private fun bucketOf(x: Double, y: Double, z: Double): Triple<Int, Int, Int> {
        val col = floor((x - xRange.start) / cellSize).toInt()
        val row = floor((y - yRange.start) / cellSize).toInt()
        val layer = floor((z - zRange.start) / cellSize).toInt()
        return Triple(col, row, layer)
    }

    /**
     *  Wrap bucket coordinates into the grid domain when [torus] is
     *  true; pass-through otherwise.
     */
    private fun wrap(col: Int, row: Int, layer: Int): Triple<Int, Int, Int> {
        if (!torus) return Triple(col, row, layer)
        val c = ((col % numCols) + numCols) % numCols
        val r = ((row % numRows) + numRows) % numRows
        val l = ((layer % numLayers) + numLayers) % numLayers
        return Triple(c, r, l)
    }

    // ── Placement / movement ────────────────────────────────────────────────

    /** Place [agent] at the given coordinates. Idempotent — equivalent to [moveTo]. */
    fun placeAt(agent: A, x: Double, y: Double, z: Double) {
        placeAt(agent, Point3D(x, y, z))
    }

    fun placeAt(agent: A, point: Point3D) {
        val raw = bucketOf(point.x, point.y, point.z)
        val newBucket = wrap(raw.first, raw.second, raw.third)
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
    fun moveTo(agent: A, x: Double, y: Double, z: Double) = placeAt(agent, x, y, z)
    fun moveTo(agent: A, point: Point3D) = placeAt(agent, point)

    /**
     *  Return the position of [agent], or null if the agent has no
     *  assigned position (never placed, or has left the context).
     */
    fun positionOf(agent: A): Point3D? = positions[agent]

    // ── Distance ────────────────────────────────────────────────────────────

    /**
     *  Distance between two agents. Returns `Double.NaN` if either
     *  agent has no assigned position. Euclidean, or wrapped Euclidean
     *  if [torus] is true.
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
    fun distance(a: Point3D, b: Point3D): Double {
        if (!torus) return a.distanceTo(b)
        val dx = wrapDelta(abs(a.x - b.x), xSize)
        val dy = wrapDelta(abs(a.y - b.y), ySize)
        val dz = wrapDelta(abs(a.z - b.z), zSize)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     *  Signed shortest-direction delta from [from] to [to] under this
     *  projection's geometry. For non-torus this is `to - from`. For
     *  a torus, each component picks whichever wrap (positive or
     *  negative) is shorter on its axis.
     *
     *  Use this for direction-aware forces (3D separation, cohesion)
     *  where averaging absolute positions would break across the
     *  wrap boundary.
     */
    fun delta(from: Point3D, to: Point3D): Point3D {
        if (!torus) return to - from
        return Point3D(
            signedWrapDelta(to.x - from.x, xSize),
            signedWrapDelta(to.y - from.y, ySize),
            signedWrapDelta(to.z - from.z, zSize),
        )
    }

    private fun signedWrapDelta(d: Double, span: Double): Double {
        if (span <= 0.0) return d
        // Reduce into [0, span) first so the result is correct even when
        // the raw delta exceeds one period (positions are stored verbatim
        // and may be outside the declared range).
        val m = ((d % span) + span) % span
        return if (m > span / 2.0) m - span else m
    }

    private fun wrapDelta(d: Double, span: Double): Double {
        if (span <= 0.0) return d
        val m = ((d % span) + span) % span
        return minOf(m, span - m)
    }

    // ── Spatial queries (use the bucket index) ──────────────────────────────

    /**
     *  All agents whose position is within [radius] of [center],
     *  ordered by distance. Includes any agent exactly at [center].
     *  Uses the spatial hash: only buckets overlapping the query
     *  sphere are scanned.
     */
    fun within(center: Point3D, radius: Double): List<A> {
        require(radius >= 0.0) { "radius must be non-negative" }
        // Pre-wrap the center bucket on a torus. This does not change the
        // visited-bucket set (modular arithmetic) but keeps the offset
        // arithmetic in-range, avoiding Int overflow for far-out-of-range
        // query coordinates.
        val rawCenter = bucketOf(center.x, center.y, center.z)
        val (cc, cr, cl) = if (torus) {
            wrap(rawCenter.first, rawCenter.second, rawCenter.third)
        } else {
            rawCenter
        }
        val cellRadius = ceil(radius / cellSize).toInt()
        val effectiveRadius = if (torus) {
            minOf(cellRadius, maxOf(numCols, numRows, numLayers))
        } else {
            cellRadius
        }

        // Dedupe via a visited set in case torus wrapping causes the
        // same cell to appear in multiple offsets.
        val visited = HashSet<Triple<Int, Int, Int>>()
        val collected = mutableListOf<Pair<A, Double>>()
        for (dl in -effectiveRadius..effectiveRadius) {
            for (dr in -effectiveRadius..effectiveRadius) {
                for (dc in -effectiveRadius..effectiveRadius) {
                    val key = wrap(cc + dc, cr + dr, cl + dl)
                    if (!visited.add(key)) continue
                    val bucket = buckets[key] ?: continue
                    for (agent in bucket) {
                        val pos = positions[agent] ?: continue
                        val d = distance(center, pos)
                        if (d <= radius) collected.add(agent to d)
                    }
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
     *  Implemented as progressive radius doubling on top of [within]:
     *  start with one cell, grow by [Defaults.nearestRadiusGrowthFactor]
     *  until enough candidates are found, then trim.
     */
    fun nearest(center: Point3D, k: Int): List<A> {
        require(k >= 0) { "k must be non-negative" }
        if (k == 0 || positions.isEmpty()) return emptyList()

        if (k >= positions.size) {
            return positions.asSequence()
                .map { (a, p) -> a to distance(center, p) }
                .sortedBy { it.second }
                .map { it.first }
                .toList()
        }

        var radius = cellSize
        // 3D safety cap: diagonal of the bounding box plus one cell.
        val maxRadius = sqrt(xSize * xSize + ySize * ySize + zSize * zSize) * 2.0 + cellSize
        while (radius <= maxRadius) {
            val candidates = within(center, radius)
            if (candidates.size >= k) {
                return candidates.take(k)
            }
            radius *= Defaults.nearestRadiusGrowthFactor
        }

        // Fallback: full linear scan. Unreachable when positions are
        // inside the projection's domain; the cap handles pathologies.
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

    // ── Diagnostics / introspection ────────────────────────────────────────

    /** Number of non-empty buckets. Diagnostic for [cellSize] tuning. */
    val occupiedBucketCount: Int
        get() = buckets.size

    /** Maximum occupancy of any single bucket. Diagnostic for [cellSize] tuning. */
    val maxBucketOccupancy: Int
        get() = buckets.values.maxOfOrNull { it.size } ?: 0
}
