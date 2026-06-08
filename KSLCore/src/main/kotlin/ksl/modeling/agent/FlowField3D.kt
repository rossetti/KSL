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

import kotlin.math.floor

/**
 *  A precomputed distance-from-sources field over a [VoxelGraph],
 *  exposed at point-level granularity for continuous-space 3D agents
 *  (drones, UAVs, AUVs). The 3D analog of [FlowField].
 *
 *  Wraps three things that previously had to be carried in parallel:
 *  the [graph], a set of goal voxels ([sources]), and the
 *  [distanceField][VoxelGraph.distanceField] map. Adds point-level
 *  conversions ([voxelOf] / [centerOf]) so drones holding
 *  continuous 3D positions can query the field directly.
 *
 *  Construction runs one multi-source Dijkstra. Queries are O(1)
 *  for [distanceAt] / [arrivedAt] and O(|passable neighbors|) — up
 *  to 26 with Moore-26 — for [directionAt]. Reconstruct (with the
 *  same or different sources) to refresh; reconstruction is cheap,
 *  so building a fresh `FlowField3D` in `initialize()` of each
 *  replication is the standard pattern.
 *
 *  Typical use (drones routing to nearest charging volume):
 *
 *  ```
 *  class DroneDelivery(parent: ModelElement) : AgentModel(parent, "delivery") {
 *      val graph: VoxelGraph = ...
 *      val chargers: Set<Voxel> = setOf(Voxel(0, 0, 0), Voxel(10, 0, 0))
 *      lateinit var field: FlowField3D
 *      override fun initialize() {
 *          super.initialize()
 *          field = FlowField3D(graph, chargers)
 *      }
 *  }
 *  // Per-drone: while (!field.arrivedAt(myPos)) {
 *  //     step in field.directionAt(myPos) * speed
 *  // }
 *  ```
 *
 *  Geometry model: voxels form a uniform 3D grid of side [cellSize]
 *  anchored at [origin]. Voxel `(c, r, l)` covers the half-open box
 *  `[origin.x + c*cellSize, origin.x + (c+1)*cellSize)` and similarly
 *  on y and z, with center at `centerOf(Voxel(c, r, l))`. For non-
 *  uniform or transformed layouts, subclass or compose; the simple
 *  uniform case covers every shipped example.
 *
 *  @param graph the underlying 3D lattice
 *  @param sources voxels treated as goals (distance = 0). Must not
 *    be empty.
 *  @param cellSize side length of each voxel in continuous space.
 *    Must be positive.
 *  @param origin continuous-space anchor of voxel `(0, 0, 0)`'s
 *    lower-left-bottom corner.
 */
class FlowField3D @JvmOverloads constructor(
    val graph: VoxelGraph,
    val sources: Set<Voxel>,
    val cellSize: Double = Defaults.cellSize,
    val origin: Point3D = Defaults.origin,
) {
    init {
        require(cellSize > 0.0) { "cellSize must be positive; was $cellSize" }
        require(sources.isNotEmpty()) { "sources must not be empty" }
    }

    /**
     *  Mutable global defaults for [FlowField3D] construction.
     *  Mirrors [FlowField.Defaults].
     */
    companion object Defaults {
        /** Default side length of each voxel in continuous space. Must be positive. */
        var cellSize: Double by positive(1.0)

        /** Default continuous-space anchor of voxel (0, 0, 0)'s lower-left-bottom corner. */
        var origin: Point3D = Point3D.ORIGIN
    }

    /**
     *  Distance-to-nearest-source for every reachable voxel.
     *  Unreachable voxels are absent. Source voxels map to 0.0.
     */
    val distances: Map<Voxel, Double> = graph.distanceField(sources)

    /** Convert a 3D point in continuous space to the voxel containing it. */
    fun voxelOf(point: Point3D): Voxel {
        val col = floor((point.x - origin.x) / cellSize).toInt()
        val row = floor((point.y - origin.y) / cellSize).toInt()
        val layer = floor((point.z - origin.z) / cellSize).toInt()
        return Voxel(col, row, layer)
    }

    /** Center coordinates of [voxel] in continuous space. */
    fun centerOf(voxel: Voxel): Point3D = Point3D(
        origin.x + (voxel.col + 0.5) * cellSize,
        origin.y + (voxel.row + 0.5) * cellSize,
        origin.z + (voxel.layer + 0.5) * cellSize,
    )

    /** True if [voxel] is within the graph's bounds. Torus always returns true. */
    private fun isInBounds(voxel: Voxel): Boolean {
        if (graph.torus) return true
        return voxel.col in 0 until graph.columns &&
            voxel.row in 0 until graph.rows &&
            voxel.layer in 0 until graph.layers
    }

    /**
     *  Distance from [point]'s voxel to the nearest source. Returns
     *  `Double.POSITIVE_INFINITY` for points whose voxel is out of
     *  bounds, blocked, or unreachable from any source.
     */
    fun distanceAt(point: Point3D): Double {
        val voxel = voxelOf(point)
        if (!isInBounds(voxel)) return Double.POSITIVE_INFINITY
        return distances[voxel] ?: Double.POSITIVE_INFINITY
    }

    /**
     *  True if [point]'s voxel is a source voxel — i.e., the agent
     *  at [point] has reached its goal. Out-of-bounds points are
     *  not arrived.
     */
    fun arrivedAt(point: Point3D): Boolean {
        val voxel = voxelOf(point)
        if (!isInBounds(voxel)) return false
        return voxel in sources
    }

    /**
     *  Unit vector pointing from [point] toward the center of the
     *  passable neighbor of [point]'s voxel with the smallest field
     *  value (i.e., the most-downhill step in the 3D gradient).
     *
     *  Returns `Point3D.ORIGIN` when the agent should *not* move:
     *   - already at a source (goal reached),
     *   - voxel is unreachable from any source,
     *   - voxel is blocked or out of bounds,
     *   - no passable neighbor strictly improves the field value,
     *   - the chosen neighbor's center coincides with [point]
     *     (degenerate; should not occur for well-formed inputs).
     *
     *  The zero-vector return lets a force-summation expression like
     *  `field.directionAt(pos) * speed` short-circuit cleanly when
     *  the agent has nowhere to go.
     */
    fun directionAt(point: Point3D): Point3D {
        val voxel = voxelOf(point)
        if (!isInBounds(voxel)) return Point3D.ORIGIN
        if (voxel in sources) return Point3D.ORIGIN
        if (!graph.isPassable(voxel)) return Point3D.ORIGIN
        val currentDist = distances[voxel] ?: return Point3D.ORIGIN

        val neighbors = graph.passableNeighbors(voxel)
        if (neighbors.isEmpty()) return Point3D.ORIGIN

        // Smallest reachable neighbor field value.
        var bestDist = Double.POSITIVE_INFINITY
        for (n in neighbors) {
            val d = distances[n] ?: continue
            if (d < bestDist) bestDist = d
        }
        // No strict improvement → plateau / local min: stop.
        if (bestDist >= currentDist) return Point3D.ORIGIN

        // Average the unit directions of all neighbors tied for the best
        // value (within tolerance) to avoid iteration-order bias.
        val tol = 1e-9 * maxOf(1.0, kotlin.math.abs(bestDist))
        var ax = 0.0
        var ay = 0.0
        var az = 0.0
        for (n in neighbors) {
            val d = distances[n] ?: continue
            if (d <= bestDist + tol) {
                val dir = (centerOf(n) - point).normalized()
                ax += dir.x
                ay += dir.y
                az += dir.z
            }
        }
        val avg = Point3D(ax, ay, az)
        if (avg.magnitude > 1e-12) return avg.normalized()
        val best = neighbors.minByOrNull { distances[it] ?: Double.POSITIVE_INFINITY }
            ?: return Point3D.ORIGIN
        return (centerOf(best) - point).normalized()
    }
}
