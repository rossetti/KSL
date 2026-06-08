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
 *  A precomputed distance-from-sources field over a [GridGraph],
 *  exposed at point-level granularity for continuous-space agents.
 *
 *  Wraps three things together that previously had to be carried in
 *  parallel by every continuous-space agent that wanted to follow a
 *  gradient: the [graph], a set of goal cells ([sources]), and the
 *  [distanceField][GridGraph.distanceField] map that quantifies
 *  "how far from any goal." Adds point-level conversions
 *  ([cellOf] / [centerOf]) so agents holding continuous positions
 *  can query the field without rewriting the same point-to-cell
 *  arithmetic at every call site.
 *
 *  Construction runs one multi-source Dijkstra (the
 *  [GridGraph.distanceField] call). Queries are O(1) for
 *  [distanceAt] / [arrivedAt] and O(|passable neighbors|) for
 *  [directionAt]. Reconstruct (with the same or different sources)
 *  to refresh — there is no in-place recompute. Reconstruction is
 *  cheap, so building a fresh `FlowField` in `initialize()` of each
 *  replication is the standard pattern when the graph or sources
 *  may change between replications.
 *
 *  Typical use (evacuation):
 *
 *  ```
 *  class MyModel(parent: ModelElement) : AgentModel(parent, "evac") {
 *      val graph: GridGraph = ...
 *      val exits: Set<Cell> = setOf(Cell(0, 0), Cell(29, 29))
 *      lateinit var field: FlowField
 *      override fun initialize() {
 *          super.initialize()
 *          field = FlowField(graph, exits)
 *      }
 *  }
 *  // Per-agent: while (!field.arrivedAt(myPos)) { step in field.directionAt(myPos) }
 *  ```
 *
 *  Geometry model: cells form a uniform grid of side [cellSize]
 *  anchored at [origin]. Cell `(c, r)` covers the continuous-space
 *  half-open rectangle `[origin.x + c*cellSize, origin.x + (c+1)*cellSize)`
 *  in x and similarly in y, with center at `centerOf(Cell(c, r))`.
 *  For non-uniform or transformed layouts, subclass or compose; the
 *  simple uniform case covers every shipped example.
 *
 *  @param graph the underlying lattice
 *  @param sources cells treated as goals (distance = 0). Must not be
 *    empty.
 *  @param cellSize side length of each cell in continuous space.
 *    Must be positive.
 *  @param origin continuous-space anchor of cell `(0, 0)`'s lower-left
 *    corner.
 */
class FlowField @JvmOverloads constructor(
    val graph: GridGraph,
    val sources: Set<Cell>,
    val cellSize: Double = Defaults.cellSize,
    val origin: Point2D = Defaults.origin,
) {
    init {
        require(cellSize > 0.0) { "cellSize must be positive; was $cellSize" }
        require(sources.isNotEmpty()) { "sources must not be empty" }
    }

    /**
     *  Mutable global defaults for [FlowField] construction. Set once
     *  at model setup to change the fallback used when constructors
     *  don't pass an explicit value.
     */
    companion object Defaults {
        /** Default side length of each cell in continuous space. Must be positive. */
        var cellSize: Double by positive(1.0)

        /** Default continuous-space anchor of cell (0, 0)'s lower-left corner. */
        var origin: Point2D = Point2D.ORIGIN
    }

    /**
     *  Distance-to-nearest-source for every reachable cell. Unreachable
     *  cells are absent. Source cells map to 0.0. Exposed for callers
     *  that want the raw map (e.g., to render a heatmap); most
     *  agent-side code should prefer [distanceAt].
     */
    val distances: Map<Cell, Double> = graph.distanceField(sources)

    /** Convert a point in continuous space to the cell containing it. */
    fun cellOf(point: Point2D): Cell {
        val col = floor((point.x - origin.x) / cellSize).toInt()
        val row = floor((point.y - origin.y) / cellSize).toInt()
        return Cell(col, row)
    }

    /** Center coordinates of [cell] in continuous space. */
    fun centerOf(cell: Cell): Point2D = Point2D(
        origin.x + (cell.col + 0.5) * cellSize,
        origin.y + (cell.row + 0.5) * cellSize,
    )

    /**
     *  True if [cell] is within the graph's bounds. Torus grids treat
     *  every cell as in-bounds (coordinates wrap).
     */
    private fun isInBounds(cell: Cell): Boolean {
        if (graph.torus) return true
        return cell.col in 0 until graph.columns && cell.row in 0 until graph.rows
    }

    /**
     *  Distance from [point]'s cell to the nearest source. Returns
     *  `Double.POSITIVE_INFINITY` for points whose cell is out of
     *  bounds, blocked, or unreachable from any source.
     */
    fun distanceAt(point: Point2D): Double {
        val cell = cellOf(point)
        if (!isInBounds(cell)) return Double.POSITIVE_INFINITY
        return distances[cell] ?: Double.POSITIVE_INFINITY
    }

    /**
     *  True if [point]'s cell is a source cell — i.e., the agent at
     *  [point] has reached its goal. Out-of-bounds points are not
     *  arrived (they may be invalid placement).
     */
    fun arrivedAt(point: Point2D): Boolean {
        val cell = cellOf(point)
        if (!isInBounds(cell)) return false
        return cell in sources
    }

    /**
     *  Unit vector pointing from [point] toward the center of the
     *  passable neighbor of [point]'s cell with the smallest field
     *  value (i.e., the most-downhill step in the gradient).
     *
     *  Returns `Point2D(0.0, 0.0)` when the agent should *not* move:
     *   - already at a source (goal reached),
     *   - cell is unreachable from any source,
     *   - cell is blocked or out of bounds,
     *   - no passable neighbor strictly improves the field value — i.e.
     *     the agent is on a flat plateau or local minimum (which can
     *     happen with equal- or zero-cost regions); such cells
     *     intentionally yield a stop.
     *
     *  When several neighbors tie for the best (lowest) field value, the
     *  unit directions toward each are averaged, so a symmetric layout
     *  produces an unbiased heading rather than arbitrarily snapping to
     *  whichever neighbor happens to come first in iteration order. If
     *  the tied directions cancel exactly, the single best neighbor is
     *  used as a fallback.
     *
     *  The zero-vector return lets a force-summation expression like
     *  `field.directionAt(pos) * speed` short-circuit cleanly when
     *  the agent has nowhere to go.
     */
    fun directionAt(point: Point2D): Point2D {
        val cell = cellOf(point)
        if (!isInBounds(cell)) return Point2D.ORIGIN
        if (cell in sources) return Point2D.ORIGIN
        if (!graph.isPassable(cell)) return Point2D.ORIGIN
        val currentDist = distances[cell] ?: return Point2D.ORIGIN

        val neighbors = graph.passableNeighbors(cell)
        if (neighbors.isEmpty()) return Point2D.ORIGIN

        // Smallest reachable neighbor field value.
        var bestDist = Double.POSITIVE_INFINITY
        for (n in neighbors) {
            val d = distances[n] ?: continue
            if (d < bestDist) bestDist = d
        }
        // No strict improvement → plateau / local min: stop.
        if (bestDist >= currentDist) return Point2D.ORIGIN

        // Average the unit directions of all neighbors tied for the best
        // value (within a small tolerance) to avoid iteration-order bias.
        val tol = 1e-9 * maxOf(1.0, kotlin.math.abs(bestDist))
        var ax = 0.0
        var ay = 0.0
        for (n in neighbors) {
            val d = distances[n] ?: continue
            if (d <= bestDist + tol) {
                val dir = (centerOf(n) - point).normalized()
                ax += dir.x
                ay += dir.y
            }
        }
        val avg = Point2D(ax, ay)
        if (avg.magnitude > 1e-12) return avg.normalized()
        // Tied directions canceled: fall back to a single best neighbor.
        val best = neighbors.minByOrNull { distances[it] ?: Double.POSITIVE_INFINITY }
            ?: return Point2D.ORIGIN
        return (centerOf(best) - point).normalized()
    }
}
