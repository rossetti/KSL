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

import kotlin.math.sqrt

/**
 *  Movement rule for a [GridGraph]. Selects the allowed neighbors of
 *  each cell:
 *   - [MOORE] — the 8 surrounding cells (orthogonal + diagonal).
 *     The standard for most grid pathfinding.
 *   - [VON_NEUMANN] — the 4 orthogonal cells only (no diagonals).
 *     Used when diagonals are not allowed (tile-aligned movement,
 *     some board games).
 */
enum class MovementRule { MOORE, VON_NEUMANN }

/**
 *  Pre-built heuristic functions for A* pathfinding on a
 *  [GridGraph]. All are admissible on uniform-cost grids (cell
 *  costs all ≥ 1.0) under the appropriate movement rule:
 *
 *  | Heuristic | Movement rule | Notes |
 *  |---|---|---|
 *  | [ZERO] | any | A* degenerates to Dijkstra |
 *  | [MANHATTAN] | VON_NEUMANN | tight for 4-way movement |
 *  | [CHEBYSHEV] | MOORE | tight when diagonals cost 1 (not used by default) |
 *  | [OCTILE] | MOORE | tight when diagonals cost √2 (the default) |
 *  | [EUCLIDEAN] | either | always admissible but never tight on a grid |
 *
 *  **Admissibility and cell costs.** Every non-[ZERO] heuristic here
 *  assumes each step costs at least its geometric length — i.e. all
 *  cell costs are ≥ 1.0. If any cell cost is < 1.0 (and in particular
 *  0.0), these heuristics *overestimate* the true cost and A* may
 *  return a sub-optimal path. For such grids, wrap the heuristic with
 *  [scaled] using the grid's [GridGraph.minCellCost]:
 *
 *  ```kotlin
 *  val h = GridHeuristics.scaled(graph.minCellCost, GridHeuristics.OCTILE)
 *  val path = graph.shortestPath(start, goal, h)
 *  ```
 *
 *  [ZERO] (plain Dijkstra) is always admissible regardless of costs.
 */
object GridHeuristics {
    val ZERO: (Cell, Cell) -> Double = { _, _ -> 0.0 }
    val MANHATTAN: (Cell, Cell) -> Double = { a, b -> a.manhattanDistanceTo(b).toDouble() }
    val CHEBYSHEV: (Cell, Cell) -> Double = { a, b -> a.chebyshevDistanceTo(b).toDouble() }
    val OCTILE: (Cell, Cell) -> Double = { a, b -> a.octileDistanceTo(b) }
    val EUCLIDEAN: (Cell, Cell) -> Double = { a, b -> a.euclideanDistanceTo(b) }

    /**
     *  Scale [base] by [scale] (typically [GridGraph.minCellCost]) so it
     *  stays admissible on grids with cell costs below 1.0. Since every
     *  step costs at least `scale × stepLength`, multiplying an
     *  otherwise-admissible geometric heuristic by `scale` keeps it a
     *  lower bound on the true cost. A `scale` of 0 yields [ZERO].
     */
    fun scaled(scale: Double, base: (Cell, Cell) -> Double): (Cell, Cell) -> Double {
        require(scale >= 0.0) { "scale must be non-negative; was $scale" }
        return { a, b -> scale * base(a, b) }
    }
}

/**
 *  A 2D-lattice graph: cells are first-class nodes, edges represent
 *  allowed movement between adjacent cells, and cells carry movement
 *  costs and blocked / passable status. Separate from [GridProjection],
 *  which places agents on a lattice but doesn't model the lattice's
 *  navigation structure.
 *
 *  Use `GridGraph` for:
 *   - **Pathfinding**: agents plan routes through an environment
 *     with obstacles (evacuation models, warehouse AGVs, robotics).
 *   - **Distance fields**: precompute "distance to goal" for every
 *     cell once via [distanceField], then have agents follow the
 *     gradient — typical pattern for many agents sharing a target.
 *   - **Terrain analysis**: reachability, bottleneck detection,
 *     impact of removing cells from passability.
 *   - **Dynamics on grids**: state propagation where edge weights
 *     reflect transmission likelihoods (fire spread, contagion on a
 *     spatial substrate).
 *
 *  Composition with the other agent-layer primitives:
 *   - An agent's position lives in a [GridProjection] (`cellOf`,
 *     `placeAt`, `moveTo`).
 *   - `GridGraph` is the world the agent navigates. The agent's
 *     `process { }` body calls `graph.shortestPath(currentCell,
 *     targetCell)` to plan, then `grid.moveTo(agent, nextCell)` to
 *     step. The two abstractions interact only through `Cell`
 *     values; neither owns the other.
 *
 *  Edge model:
 *   - Each cell has a `cellCost`, default 1.0. Set via [setCellCost]
 *     (and read via [cellCostOf]).
 *   - Edge cost from cell *u* to neighbor *v* is
 *     `cellCost(v) * stepLength`, where `stepLength` is 1.0 for
 *     orthogonal moves and √2 for diagonal moves (Moore only).
 *   - Blocked cells (set via [block]) have no incident edges and
 *     are unreachable from any other cell.
 *
 *  Boundary semantics:
 *   - Bounded (default) — coordinates outside `[0, columns) x [0, rows)`
 *     throw. Cells on the boundary have fewer neighbors.
 *   - Torus (`torus = true`) — coordinates wrap; the top-right cell
 *     is a neighbor of the top-left cell.
 *
 *  Time complexity:
 *   - [shortestPath] / [shortestPathLength]: O((V + E) log V) with
 *     a binary-heap priority queue (Dijkstra), often dramatically
 *     better in practice with a tight A* heuristic.
 *   - [distanceField]: O((V + E) log V), one multi-source Dijkstra.
 *   - [reachableFrom] / [isReachable]: O(V + E) BFS.
 *
 *  Corner-cutting (Moore only):
 *   - By default (`allowCornerCutting = false`) a diagonal move is
 *     permitted only when both orthogonally-adjacent "shoulder"
 *     cells it passes between are passable. This prevents agents
 *     from slipping diagonally between two blocked cells (clipping
 *     a wall corner) — the physically correct behavior for
 *     obstacle, evacuation, and warehouse models.
 *   - Set `allowCornerCutting = true` to recover the looser rule
 *     where a diagonal move only requires its destination to be
 *     passable (faster, occasionally desired for abstract grids).
 *   - Has no effect under VON_NEUMANN (no diagonal moves).
 *
 *  @param columns lattice width
 *  @param rows lattice height
 *  @param torus if true, coordinates wrap at boundaries
 *  @param movementRule MOORE (8-way) or VON_NEUMANN (4-way)
 *  @param allowCornerCutting if true, diagonal moves ignore the
 *    passability of the two shoulder cells (default false)
 */
class GridGraph @JvmOverloads constructor(
    val columns: Int,
    val rows: Int,
    val torus: Boolean = false,
    val movementRule: MovementRule = MovementRule.MOORE,
    val allowCornerCutting: Boolean = false,
) {
    init {
        require(columns > 0) { "columns must be positive; was $columns" }
        require(rows > 0) { "rows must be positive; was $rows" }
    }

    private val cellCosts: MutableMap<Cell, Double> = mutableMapOf()
    private val blockedCells: MutableSet<Cell> = mutableSetOf()

    /** Number of cells currently marked as blocked. */
    val blockedCount: Int
        get() = blockedCells.size

    /** Total cell count (columns × rows). */
    val cellCount: Int
        get() = columns * rows

    /**
     *  Set the per-step cost of entering [cell]. Must be ≥ 0.
     *  Cells with no explicit cost have cost 1.0 implicitly.
     *
     *  Note: costs **below 1.0** (including 0.0) make the non-[ZERO]
     *  [GridHeuristics] inadmissible — wrap your heuristic with
     *  [GridHeuristics.scaled] using [minCellCost], or use Dijkstra
     *  ([GridHeuristics.ZERO]). See [GridHeuristics] for details.
     */
    fun setCellCost(cell: Cell, cost: Double) {
        require(cost >= 0.0) { "cell cost must be non-negative; was $cost" }
        val c = normalize(cell)
        if (cost == 1.0) cellCosts.remove(c) else cellCosts[c] = cost
    }

    /** Read the cost of entering [cell]. Default 1.0 for cells not explicitly set. */
    fun cellCostOf(cell: Cell): Double = cellCosts[normalize(cell)] ?: 1.0

    /**
     *  The minimum cell cost across the grid: `min(1.0, smallest
     *  explicitly-set cost)`. Equals 1.0 for a uniform grid. Pass this
     *  to [GridHeuristics.scaled] to keep an A* heuristic admissible
     *  when some cells cost less than 1.0.
     */
    val minCellCost: Double
        get() = minOf(1.0, cellCosts.values.minOrNull() ?: 1.0)

    /** Mark [cell] as impassable. */
    fun block(cell: Cell) {
        blockedCells.add(normalize(cell))
    }

    /** Mark [cell] as passable (no-op if it wasn't blocked). */
    fun unblock(cell: Cell) {
        blockedCells.remove(normalize(cell))
    }

    /** True if [cell] is in bounds (always true on a torus) and not blocked. */
    fun isPassable(cell: Cell): Boolean {
        if (!torus) {
            if (cell.col !in 0 until columns || cell.row !in 0 until rows) return false
        }
        return normalize(cell) !in blockedCells
    }

    /** Normalize a cell into the grid's domain; bounded grids throw on out-of-range coordinates. */
    private fun normalize(cell: Cell): Cell {
        if (torus) {
            val c = ((cell.col % columns) + columns) % columns
            val r = ((cell.row % rows) + rows) % rows
            return Cell(c, r)
        }
        require(cell.col in 0 until columns) {
            "col ${cell.col} out of range [0, $columns) for non-torus grid"
        }
        require(cell.row in 0 until rows) {
            "row ${cell.row} out of range [0, $rows) for non-torus grid"
        }
        return cell
    }

    /**
     *  Passable neighbors of [cell] under the configured movement
     *  rule. Excludes blocked cells and (for non-torus grids)
     *  out-of-range cells. Returns the cells without their edge
     *  weights; use [edgeWeight] if you need the weight too.
     */
    fun passableNeighbors(cell: Cell): List<Cell> {
        val center = normalize(cell)
        val offsets = when (movementRule) {
            MovementRule.MOORE -> MOORE_OFFSETS
            MovementRule.VON_NEUMANN -> VON_NEUMANN_OFFSETS
        }
        val out = ArrayList<Cell>(offsets.size)
        for ((dc, dr) in offsets) {
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
            if (candidate in blockedCells) continue
            // Diagonal move: unless corner-cutting is allowed, forbid
            // squeezing between two blocked cells. The two shoulder
            // cells are the orthogonal neighbors the diagonal passes
            // between. For a bounded grid they are guaranteed in range
            // here (their coordinates lie between center and the
            // already-bounds-checked candidate), so normalize is safe.
            if (!allowCornerCutting && dc != 0 && dr != 0) {
                val shoulderA = normalize(Cell(center.col + dc, center.row))
                val shoulderB = normalize(Cell(center.col, center.row + dr))
                if (shoulderA in blockedCells || shoulderB in blockedCells) continue
            }
            out.add(candidate)
        }
        return out
    }

    /**
     *  Weight of the edge from [from] to [to]. Equals
     *  `cellCostOf(to) * stepLength`, where stepLength is 1.0 for
     *  orthogonal steps and √2 for diagonal steps. The caller is
     *  responsible for ensuring [to] is a neighbor of [from] under
     *  the movement rule; this function does not validate that.
     */
    fun edgeWeight(from: Cell, to: Cell): Double {
        val stepLength = if (from.col != to.col && from.row != to.row) SQRT2 else 1.0
        return cellCostOf(to) * stepLength
    }

    // ── Shortest path (Dijkstra / A*) ───────────────────────────────────────

    /**
     *  Shortest weighted path from [from] to [to]. Returns the path
     *  (including both endpoints) plus total cost, or null if [to]
     *  is unreachable. A self-path returns
     *  `WeightedPath(listOf(from), 0.0)`.
     *
     *  With the default zero heuristic this is Dijkstra. Pass a
     *  non-trivial heuristic for A*; see [GridHeuristics] for
     *  pre-built admissible heuristics.
     *
     *  Cell costs must be non-negative.
     */
    fun shortestPath(
        from: Cell,
        to: Cell,
        heuristic: (current: Cell, target: Cell) -> Double = GridHeuristics.ZERO,
    ): WeightedPath<Cell>? {
        val start = normalize(from)
        val goal = normalize(to)
        if (start == goal) return WeightedPath(listOf(start), 0.0)
        if (!isPassable(start) || !isPassable(goal)) return null

        val gScore: MutableMap<Cell, Double> = HashMap()
        val parents: MutableMap<Cell, Cell> = HashMap()
        val finalized: MutableSet<Cell> = HashSet()
        gScore[start] = 0.0

        val pq = java.util.PriorityQueue<Pair<Cell, Double>>(compareBy { it.second })
        pq.add(start to heuristic(start, goal))

        while (pq.isNotEmpty()) {
            val current = pq.poll().first
            if (current in finalized) continue
            finalized.add(current)

            if (current == goal) {
                val path = ArrayDeque<Cell>()
                var node: Cell? = current
                while (node != null) {
                    path.addFirst(node)
                    node = parents[node]
                }
                return WeightedPath(path.toList(), gScore[current]!!)
            }

            val currentG = gScore[current]!!
            for (neighbor in passableNeighbors(current)) {
                if (neighbor in finalized) continue
                val w = edgeWeight(current, neighbor)
                val tentativeG = currentG + w
                if (tentativeG < (gScore[neighbor] ?: Double.POSITIVE_INFINITY)) {
                    parents[neighbor] = current
                    gScore[neighbor] = tentativeG
                    pq.add(neighbor to (tentativeG + heuristic(neighbor, goal)))
                }
            }
        }
        return null
    }

    /**
     *  Convenience accessor: total weighted cost from [from] to
     *  [to], or `Double.POSITIVE_INFINITY` if unreachable. Returns
     *  0.0 for the self-path.
     */
    fun shortestPathLength(
        from: Cell,
        to: Cell,
        heuristic: (current: Cell, target: Cell) -> Double = GridHeuristics.ZERO,
    ): Double = shortestPath(from, to, heuristic)?.totalWeight ?: Double.POSITIVE_INFINITY

    // ── Distance fields ─────────────────────────────────────────────────────

    /**
     *  Single-source distance field: for each passable cell, the
     *  shortest-path cost from [source] to that cell. Unreachable
     *  cells are absent from the result map. Useful when many
     *  agents share the same goal; compute the field once via
     *  multi-source [distanceField], then look up distances
     *  per-cell in O(1).
     *
     *  Implemented as a multi-source Dijkstra over [source] alone.
     */
    fun distanceField(source: Cell): Map<Cell, Double> = distanceField(setOf(source))

    /**
     *  Multi-source distance field: for each passable cell, the
     *  shortest-path cost from the *nearest* source in [sources].
     *  The canonical use case is "distance from any exit cell" for
     *  evacuation models — compute once at setup, then every agent
     *  follows the gradient toward zero in O(1) per step.
     *
     *  Sources themselves have distance 0. Unreachable cells are
     *  absent from the result map.
     */
    fun distanceField(sources: Set<Cell>): Map<Cell, Double> {
        val dist: MutableMap<Cell, Double> = HashMap()
        val finalized: MutableSet<Cell> = HashSet()
        val pq = java.util.PriorityQueue<Pair<Cell, Double>>(compareBy { it.second })

        for (s in sources) {
            val ns = normalize(s)
            if (!isPassable(ns)) continue
            dist[ns] = 0.0
            pq.add(ns to 0.0)
        }

        while (pq.isNotEmpty()) {
            val current = pq.poll().first
            if (current in finalized) continue
            finalized.add(current)
            val currentDist = dist[current]!!
            for (neighbor in passableNeighbors(current)) {
                if (neighbor in finalized) continue
                val w = edgeWeight(current, neighbor)
                val tentative = currentDist + w
                if (tentative < (dist[neighbor] ?: Double.POSITIVE_INFINITY)) {
                    dist[neighbor] = tentative
                    pq.add(neighbor to tentative)
                }
            }
        }
        return dist
    }

    // ── Reachability ────────────────────────────────────────────────────────

    /**
     *  All cells reachable from [start] via passable neighbors
     *  (BFS). Includes [start] itself if it's passable; returns an
     *  empty set if [start] is blocked.
     */
    fun reachableFrom(start: Cell): Set<Cell> {
        val s = normalize(start)
        if (!isPassable(s)) return emptySet()
        val visited = LinkedHashSet<Cell>()
        visited.add(s)
        val queue = ArrayDeque<Cell>()
        queue.addLast(s)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in passableNeighbors(current)) {
                if (visited.add(next)) queue.addLast(next)
            }
        }
        return visited
    }

    /**
     *  True if [to] is reachable from [from]. Uses early-exit BFS;
     *  faster than `to in reachableFrom(from)` for large grids
     *  where the target is close.
     */
    fun isReachable(from: Cell, to: Cell): Boolean {
        val start = normalize(from)
        val goal = normalize(to)
        if (!isPassable(start) || !isPassable(goal)) return false
        if (start == goal) return true
        val visited = mutableSetOf(start)
        val queue = ArrayDeque<Cell>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in passableNeighbors(current)) {
                if (next == goal) return true
                if (visited.add(next)) queue.addLast(next)
            }
        }
        return false
    }

    companion object {
        private val SQRT2 = sqrt(2.0)
        private val MOORE_OFFSETS: List<Pair<Int, Int>> = listOf(
            -1 to -1, 0 to -1, 1 to -1,
            -1 to 0,           1 to 0,
            -1 to 1, 0 to 1, 1 to 1,
        )
        private val VON_NEUMANN_OFFSETS: List<Pair<Int, Int>> = listOf(
            0 to -1,
            -1 to 0, 1 to 0,
            0 to 1,
        )
    }
}
