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
import kotlin.math.hypot
import kotlin.math.max

/**
 *  A 2D lattice projection: each agent in the [context] occupies an
 *  integer-coordinate [Cell]. The grid has fixed [columns] and
 *  [rows]; cells are addressed by `(col, row)` with `col ∈ [0, columns)`
 *  and `row ∈ [0, rows)`.
 *
 *  Occupancy semantics:
 *   - [GridOccupancy.MULTIPLE] (default) — multiple agents may share
 *     a cell. Typical for swarm / crowd / movement models.
 *   - [GridOccupancy.SINGLE] — at most one agent per cell.
 *     [placeAt] throws if the target cell is already occupied by a
 *     different agent; use [tryPlaceAt] for the conditional case.
 *
 *  Boundary semantics:
 *   - Bounded (default) — coordinates outside `[0, columns) × [0, rows)`
 *     throw. Cells near the edge have fewer neighbors.
 *   - Torus (`torus = true`) — coordinates wrap; the top-left and
 *     top-right cells are neighbors. Useful for finite-but-edge-free
 *     models (Game of Life, infinite-plane approximations).
 *
 *  Spatial queries — [neighborsOf], [agentsAt], [cellsWithin] — do
 *  linear scans over the occupancy map. Performance is fine for
 *  typical agent counts; a future spatial index could swap in
 *  without changing this class's public API.
 */
class GridProjection<A : AgentLike> @JvmOverloads constructor(
    val context: AgentModel.Context<A>,
    val columns: Int,
    val rows: Int,
    val occupancy: GridOccupancy = GridOccupancy.MULTIPLE,
    val torus: Boolean = false,
    override val name: String = "grid",
) : Projection<A> {

    /**
     *  Mutable global defaults for [GridProjection] queries.
     */
    companion object Defaults {
        /** Default radius for [neighborsOf] when none is specified. Must be non-negative. */
        var neighborhoodRadius: Int by nonNegative(1)
    }

    init {
        require(columns > 0) { "columns must be positive; was $columns" }
        require(rows > 0) { "rows must be positive; was $rows" }
    }

    private val agentToCell: MutableMap<A, Cell> = mutableMapOf()
    private val cellToAgents: MutableMap<Cell, MutableList<A>> = mutableMapOf()

    init {
        context.addProjection(this)
    }

    /** Number of agents currently placed on the grid. */
    val size: Int
        get() = agentToCell.size

    /**
     *  Normalize coordinates into the grid's domain. For a bounded
     *  grid, out-of-range coordinates throw. For a torus, they wrap.
     */
    private fun normalize(col: Int, row: Int): Cell {
        if (torus) {
            val c = ((col % columns) + columns) % columns
            val r = ((row % rows) + rows) % rows
            return Cell(c, r)
        }
        require(col in 0 until columns) { "col $col out of range [0, $columns) for non-torus grid" }
        require(row in 0 until rows) { "row $row out of range [0, $rows) for non-torus grid" }
        return Cell(col, row)
    }

    private fun normalize(cell: Cell): Cell = normalize(cell.col, cell.row)

    /**
     *  Place [agent] at the given cell. For [GridOccupancy.SINGLE]
     *  grids, throws if the target cell is already occupied by a
     *  different agent.
     */
    fun placeAt(agent: A, cell: Cell) {
        val c = normalize(cell)
        if (occupancy == GridOccupancy.SINGLE) {
            val occupants = cellToAgents[c]
            if (occupants != null && occupants.isNotEmpty()) {
                val other = occupants.first()
                check(other === agent) {
                    "cell $c already occupied by '${other.name}' in single-occupancy grid '$name'"
                }
                return  // already placed at this cell
            }
        }
        // Remove from previous cell, if any.
        agentToCell[agent]?.let { prev ->
            cellToAgents[prev]?.remove(agent)
            if (cellToAgents[prev]?.isEmpty() == true) cellToAgents.remove(prev)
        }
        agentToCell[agent] = c
        cellToAgents.getOrPut(c) { mutableListOf() }.add(agent)
    }

    fun placeAt(agent: A, col: Int, row: Int) = placeAt(agent, Cell(col, row))

    /** Equivalent to [placeAt]; provided for symmetry with movement-style code. */
    fun moveTo(agent: A, cell: Cell) = placeAt(agent, cell)
    fun moveTo(agent: A, col: Int, row: Int) = placeAt(agent, Cell(col, row))

    /**
     *  Conditional placement for single-occupancy grids: returns
     *  true if the cell was free (or already held this agent) and
     *  the placement succeeded; false if the cell was occupied by
     *  another agent. Always succeeds for multi-occupancy grids.
     */
    fun tryPlaceAt(agent: A, cell: Cell): Boolean {
        val c = normalize(cell)
        if (occupancy == GridOccupancy.SINGLE) {
            val occupants = cellToAgents[c]
            if (occupants != null && occupants.isNotEmpty()) {
                val other = occupants.first()
                if (other !== agent) return false
            }
        }
        placeAt(agent, c)
        return true
    }

    /** Cell currently occupied by [agent], or null if not placed. */
    fun cellOf(agent: A): Cell? = agentToCell[agent]

    /** All agents currently on [cell]. Empty list if the cell is unoccupied. */
    fun agentsAt(cell: Cell): List<A> {
        val c = normalize(cell)
        return cellToAgents[c]?.toList() ?: emptyList()
    }

    fun agentsAt(col: Int, row: Int): List<A> = agentsAt(Cell(col, row))

    /** True if [cell] has no occupants. */
    fun isEmpty(cell: Cell): Boolean = agentsAt(cell).isEmpty()

    // ── Cell-only neighborhood queries ──────────────────────────────────────

    /**
     *  The 8 cells in the Moore neighborhood (Chebyshev distance 1)
     *  around [cell]. If [includeSelf] is true, [cell] is included.
     *  Cells outside the bounds are dropped for non-torus grids; for
     *  torus grids the full 8 (or 9 with self) are always returned.
     */
    fun mooreNeighborhood(cell: Cell, includeSelf: Boolean = false): List<Cell> =
        cellsWithin(cell, radius = 1, metric = GridMetric.CHEBYSHEV, includeSelf = includeSelf)

    /**
     *  The 4 cells in the Von Neumann neighborhood (Manhattan distance
     *  1) around [cell]. If [includeSelf] is true, [cell] is included.
     */
    fun vonNeumannNeighborhood(cell: Cell, includeSelf: Boolean = false): List<Cell> =
        cellsWithin(cell, radius = 1, metric = GridMetric.MANHATTAN, includeSelf = includeSelf)

    /**
     *  All cells within [radius] of [cell] under the given [metric].
     *  Excludes [cell] itself unless [includeSelf] is true. Cells
     *  outside the bounds are dropped for non-torus grids.
     */
    fun cellsWithin(
        cell: Cell,
        radius: Int,
        metric: GridMetric = GridMetric.CHEBYSHEV,
        includeSelf: Boolean = false,
    ): List<Cell> {
        require(radius >= 0) { "radius must be non-negative; was $radius" }
        val center = normalize(cell)
        // Use a LinkedHashSet so we de-dupe (the same cell can appear
        // twice in the iteration when the grid is small relative to
        // the radius on a torus, e.g. radius >= columns/2) while
        // preserving iteration order.
        val out = LinkedHashSet<Cell>()
        for (dr in -radius..radius) {
            for (dc in -radius..radius) {
                if (!includeSelf && dr == 0 && dc == 0) continue
                // Distance is computed from the step offsets (dc, dr)
                // — these are the actual step counts regardless of
                // whether the candidate label wraps on a torus.
                val d = when (metric) {
                    GridMetric.CHEBYSHEV -> max(abs(dr), abs(dc)).toDouble()
                    GridMetric.MANHATTAN -> (abs(dr) + abs(dc)).toDouble()
                    GridMetric.EUCLIDEAN -> hypot(dr.toDouble(), dc.toDouble())
                }
                if (d > radius) continue
                val candidate = if (torus) {
                    Cell(
                        ((center.col + dc) % columns + columns) % columns,
                        ((center.row + dr) % rows + rows) % rows,
                    )
                } else {
                    val nc = center.col + dc
                    val nr = center.row + dr
                    if (nc !in 0 until columns || nr !in 0 until rows) continue
                    Cell(nc, nr)
                }
                out.add(candidate)
            }
        }
        return out.toList()
    }

    // ── Agent neighborhood queries ──────────────────────────────────────────

    /**
     *  All agents currently in cells within [radius] of [cell] under
     *  [metric]. Excludes occupants of [cell] itself unless
     *  [includeCenter] is true.
     */
    fun agentsWithin(
        cell: Cell,
        radius: Int,
        metric: GridMetric = GridMetric.CHEBYSHEV,
        includeCenter: Boolean = false,
    ): List<A> {
        val cells = cellsWithin(cell, radius, metric, includeSelf = includeCenter)
        return cells.flatMap { agentsAt(it) }
    }

    /**
     *  All other agents within [radius] of [agent]'s cell. Excludes
     *  [agent] itself but includes any co-occupants of its cell.
     */
    fun neighborsOf(
        agent: A,
        radius: Int = Defaults.neighborhoodRadius,
        metric: GridMetric = GridMetric.CHEBYSHEV,
    ): List<A> {
        val cell = agentToCell[agent] ?: return emptyList()
        return agentsWithin(cell, radius, metric, includeCenter = true).filter { it !== agent }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onAgentLeft(agent: A) {
        val cell = agentToCell.remove(agent) ?: return
        cellToAgents[cell]?.remove(agent)
        if (cellToAgents[cell]?.isEmpty() == true) cellToAgents.remove(cell)
    }
}

/** Occupancy rule for a [GridProjection]; see that class for details. */
enum class GridOccupancy {
    /** Multiple agents may share a cell. */
    MULTIPLE,
    /** At most one agent per cell; [GridProjection.placeAt] throws on conflict. */
    SINGLE,
}
