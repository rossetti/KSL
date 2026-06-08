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
 *  Movement rule for a [VoxelGraph]. Selects the allowed neighbors
 *  of each voxel:
 *   - [MOORE_26] — the 26 surrounding voxels (6 face-adjacent +
 *     12 edge-adjacent + 8 corner-adjacent). The standard for most
 *     3D pathfinding where diagonal moves are physically allowed.
 *   - [VON_NEUMANN_6] — the 6 face-adjacent voxels only (no
 *     diagonals). Used when diagonals are not allowed (axis-aligned
 *     storage, vertical-corridor routing).
 *
 *  The 3D analog of [MovementRule].
 */
enum class VoxelMovementRule { MOORE_26, VON_NEUMANN_6 }

/**
 *  Pre-built heuristic functions for A* pathfinding on a [VoxelGraph].
 *  All are admissible on uniform-cost grids (voxel costs all ≥ 1.0)
 *  under the appropriate movement rule:
 *
 *  | Heuristic | Movement rule | Notes |
 *  |---|---|---|
 *  | [ZERO] | any | A* degenerates to Dijkstra |
 *  | [MANHATTAN] | VON_NEUMANN_6 | tight for 6-way movement |
 *  | [CHEBYSHEV] | MOORE_26 | tight when all step costs are 1 (not the default) |
 *  | [OCTILE] | MOORE_26 | tight when orth = 1, face-diag = √2, body-diag = √3 (the default) |
 *  | [EUCLIDEAN] | either | always admissible but never tight on a grid |
 *
 *  **Admissibility and voxel costs.** Every non-[ZERO] heuristic
 *  assumes each step costs at least its geometric length — i.e. all
 *  voxel costs are ≥ 1.0. If any voxel cost is < 1.0 (including 0.0)
 *  these heuristics overestimate and A* may return a sub-optimal path;
 *  wrap the heuristic with [scaled] using [VoxelGraph.minVoxelCost], or
 *  use [ZERO] (Dijkstra), which is always admissible.
 */
object VoxelHeuristics {
    val ZERO: (Voxel, Voxel) -> Double = { _, _ -> 0.0 }
    val MANHATTAN: (Voxel, Voxel) -> Double = { a, b -> a.manhattanDistanceTo(b).toDouble() }
    val CHEBYSHEV: (Voxel, Voxel) -> Double = { a, b -> a.chebyshevDistanceTo(b).toDouble() }
    val OCTILE: (Voxel, Voxel) -> Double = { a, b -> a.octileDistanceTo(b) }
    val EUCLIDEAN: (Voxel, Voxel) -> Double = { a, b -> a.euclideanDistanceTo(b) }

    /**
     *  Scale [base] by [scale] (typically [VoxelGraph.minVoxelCost]) so
     *  it stays admissible on grids with voxel costs below 1.0. A
     *  `scale` of 0 yields [ZERO].
     */
    fun scaled(scale: Double, base: (Voxel, Voxel) -> Double): (Voxel, Voxel) -> Double {
        require(scale >= 0.0) { "scale must be non-negative; was $scale" }
        return { a, b -> scale * base(a, b) }
    }
}

/**
 *  A 3D-lattice graph: voxels are first-class nodes, edges represent
 *  allowed movement between adjacent voxels, and voxels carry
 *  movement costs and blocked / passable status. The 3D analog of
 *  [GridGraph]. Separate from [VoxelProjection], which places agents
 *  on a lattice but doesn't model the lattice's navigation
 *  structure.
 *
 *  Use `VoxelGraph` for:
 *   - **3D pathfinding**: drones / robots routing through an
 *     environment with 3D obstacles (no-fly zones, buildings,
 *     warehouse racks with vertical extent).
 *   - **3D distance fields**: precompute "distance to goal voxels"
 *     for every voxel via [distanceField], then have agents follow
 *     the gradient.
 *   - **3D reachability analysis**: which voxels can reach a given
 *     altitude / corridor / charging volume.
 *
 *  Edge model:
 *   - Each voxel has a [voxelCost], default 1.0. Set via
 *     [setVoxelCost] (and read via [voxelCostOf]).
 *   - Edge cost from voxel *u* to neighbor *v* is
 *     `voxelCost(v) * stepLength`, where `stepLength` is:
 *     - 1.0 for orthogonal (face-adjacent) moves
 *     - √2 for face-diagonal (edge-adjacent) moves
 *     - √3 for body-diagonal (corner-adjacent) moves
 *   - Blocked voxels (set via [block]) have no incident edges and
 *     are unreachable from any other voxel.
 *
 *  Boundary semantics:
 *   - Bounded (default) — coordinates outside the bounds throw.
 *     Voxels on the boundary have fewer neighbors.
 *   - Torus (`torus = true`) — coordinates wrap on all three axes.
 *
 *  Time complexity:
 *   - [shortestPath] / [shortestPathLength]: O((V + E) log V) with a
 *     binary-heap priority queue (Dijkstra), often dramatically
 *     better in practice with a tight A* heuristic (e.g.,
 *     [VoxelHeuristics.OCTILE]).
 *   - [distanceField]: O((V + E) log V), one multi-source Dijkstra.
 *   - [reachableFrom] / [isReachable]: O(V + E) BFS.
 *
 *  @param columns lattice width (x-axis)
 *  @param rows lattice depth (y-axis)
 *  @param layers lattice height (z-axis, typically altitude)
 *  Corner-cutting (MOORE_26 only):
 *   - By default (`allowCornerCutting = false`) a diagonal move
 *     (face-diagonal √2 or body-diagonal √3) is permitted only when
 *     every axis-neighbor it passes between is passable — i.e. for
 *     each nonzero offset component, the voxel reached by that
 *     component alone must be passable. This prevents agents from
 *     clipping through blocked voxels at shared edges/corners.
 *   - Set `allowCornerCutting = true` to require only that the
 *     destination voxel be passable.
 *   - Has no effect under VON_NEUMANN_6 (only orthogonal moves).
 *
 *  @param torus if true, coordinates wrap at boundaries
 *  @param movementRule MOORE_26 (26-way) or VON_NEUMANN_6 (6-way)
 *  @param allowCornerCutting if true, diagonal moves ignore the
 *    passability of the in-between axis-neighbor voxels (default false)
 */
class VoxelGraph @JvmOverloads constructor(
    val columns: Int,
    val rows: Int,
    val layers: Int,
    val torus: Boolean = false,
    val movementRule: VoxelMovementRule = VoxelMovementRule.MOORE_26,
    val allowCornerCutting: Boolean = false,
) {
    init {
        require(columns > 0) { "columns must be positive; was $columns" }
        require(rows > 0) { "rows must be positive; was $rows" }
        require(layers > 0) { "layers must be positive; was $layers" }
    }

    private val voxelCosts: MutableMap<Voxel, Double> = mutableMapOf()
    private val blockedVoxels: MutableSet<Voxel> = mutableSetOf()

    /** Number of voxels currently marked as blocked. */
    val blockedCount: Int
        get() = blockedVoxels.size

    /** Total voxel count (columns × rows × layers). */
    val voxelCount: Int
        get() = columns * rows * layers

    /**
     *  Set the per-step cost of entering [voxel]. Must be ≥ 0.
     *  Voxels with no explicit cost have cost 1.0 implicitly.
     *
     *  Note: costs **below 1.0** (including 0.0) make the non-[ZERO]
     *  [VoxelHeuristics] inadmissible — wrap your heuristic with
     *  [VoxelHeuristics.scaled] using [minVoxelCost], or use Dijkstra
     *  ([VoxelHeuristics.ZERO]).
     */
    fun setVoxelCost(voxel: Voxel, cost: Double) {
        require(cost >= 0.0) { "voxel cost must be non-negative; was $cost" }
        val v = normalize(voxel)
        if (cost == 1.0) voxelCosts.remove(v) else voxelCosts[v] = cost
    }

    /** Read the cost of entering [voxel]. Default 1.0 for voxels not explicitly set. */
    fun voxelCostOf(voxel: Voxel): Double = voxelCosts[normalize(voxel)] ?: 1.0

    /**
     *  The minimum voxel cost across the grid: `min(1.0, smallest
     *  explicitly-set cost)`. Equals 1.0 for a uniform grid. Pass to
     *  [VoxelHeuristics.scaled] to keep an A* heuristic admissible when
     *  some voxels cost less than 1.0.
     */
    val minVoxelCost: Double
        get() = minOf(1.0, voxelCosts.values.minOrNull() ?: 1.0)

    /** Mark [voxel] as impassable. */
    fun block(voxel: Voxel) {
        blockedVoxels.add(normalize(voxel))
    }

    /** Mark [voxel] as passable (no-op if it wasn't blocked). */
    fun unblock(voxel: Voxel) {
        blockedVoxels.remove(normalize(voxel))
    }

    /** True if [voxel] is in bounds (always true on a torus) and not blocked. */
    fun isPassable(voxel: Voxel): Boolean {
        if (!torus) {
            if (voxel.col !in 0 until columns) return false
            if (voxel.row !in 0 until rows) return false
            if (voxel.layer !in 0 until layers) return false
        }
        return normalize(voxel) !in blockedVoxels
    }

    /** Normalize a voxel into the grid's domain; bounded grids throw on out-of-range coordinates. */
    private fun normalize(voxel: Voxel): Voxel {
        if (torus) {
            val c = ((voxel.col % columns) + columns) % columns
            val r = ((voxel.row % rows) + rows) % rows
            val l = ((voxel.layer % layers) + layers) % layers
            return Voxel(c, r, l)
        }
        require(voxel.col in 0 until columns) {
            "col ${voxel.col} out of range [0, $columns) for non-torus grid"
        }
        require(voxel.row in 0 until rows) {
            "row ${voxel.row} out of range [0, $rows) for non-torus grid"
        }
        require(voxel.layer in 0 until layers) {
            "layer ${voxel.layer} out of range [0, $layers) for non-torus grid"
        }
        return voxel
    }

    /**
     *  Passable neighbors of [voxel] under the configured movement
     *  rule. Excludes blocked voxels and (for non-torus grids)
     *  out-of-range voxels.
     */
    fun passableNeighbors(voxel: Voxel): List<Voxel> {
        val center = normalize(voxel)
        val offsets = when (movementRule) {
            VoxelMovementRule.MOORE_26 -> MOORE_26_OFFSETS
            VoxelMovementRule.VON_NEUMANN_6 -> VON_NEUMANN_6_OFFSETS
        }
        val out = ArrayList<Voxel>(offsets.size)
        for ((dc, dr, dl) in offsets) {
            val candidate = if (torus) {
                Voxel(
                    ((center.col + dc) % columns + columns) % columns,
                    ((center.row + dr) % rows + rows) % rows,
                    ((center.layer + dl) % layers + layers) % layers,
                )
            } else {
                val nc = center.col + dc
                val nr = center.row + dr
                val nl = center.layer + dl
                if (nc !in 0 until columns) continue
                if (nr !in 0 until rows) continue
                if (nl !in 0 until layers) continue
                Voxel(nc, nr, nl)
            }
            if (candidate in blockedVoxels) continue
            // Diagonal move (two or more nonzero components): unless
            // corner-cutting is allowed, every axis-neighbor the move
            // passes between must be passable, so the agent cannot clip
            // through a blocked voxel at a shared face/edge/corner. For
            // a bounded grid these axis-neighbors are guaranteed in
            // range here (their coordinates lie between center and the
            // already-bounds-checked candidate), so normalize is safe.
            if (!allowCornerCutting) {
                val nonZero = (if (dc != 0) 1 else 0) + (if (dr != 0) 1 else 0) + (if (dl != 0) 1 else 0)
                if (nonZero >= 2) {
                    if (dc != 0 && normalize(Voxel(center.col + dc, center.row, center.layer)) in blockedVoxels) continue
                    if (dr != 0 && normalize(Voxel(center.col, center.row + dr, center.layer)) in blockedVoxels) continue
                    if (dl != 0 && normalize(Voxel(center.col, center.row, center.layer + dl)) in blockedVoxels) continue
                }
            }
            out.add(candidate)
        }
        return out
    }

    /**
     *  Weight of the edge from [from] to [to]. Equals
     *  `voxelCostOf(to) * stepLength`, where `stepLength` is:
     *   - 1.0 for orthogonal moves (one axis differs)
     *   - √2 for face-diagonal moves (two axes differ)
     *   - √3 for body-diagonal moves (all three axes differ)
     *
     *  The caller is responsible for ensuring [to] is a neighbor of
     *  [from] under the movement rule; this function does not
     *  validate that.
     */
    fun edgeWeight(from: Voxel, to: Voxel): Double {
        val axesDiffering =
            (if (from.col != to.col) 1 else 0) +
            (if (from.row != to.row) 1 else 0) +
            (if (from.layer != to.layer) 1 else 0)
        val stepLength = when (axesDiffering) {
            1 -> 1.0
            2 -> SQRT2
            3 -> SQRT3
            else -> 0.0   // self-edge (shouldn't be a neighbor)
        }
        return voxelCostOf(to) * stepLength
    }

    // ── Shortest path (Dijkstra / A*) ──────────────────────────────────────

    /**
     *  Shortest weighted path from [from] to [to]. Returns the path
     *  (including both endpoints) plus total cost, or null if [to]
     *  is unreachable. A self-path returns
     *  `WeightedPath(listOf(from), 0.0)`.
     *
     *  With the default zero heuristic this is Dijkstra. Pass a
     *  non-trivial heuristic for A*; see [VoxelHeuristics] for
     *  pre-built admissible heuristics — [VoxelHeuristics.OCTILE] is
     *  the tight choice for Moore-26 with the default step costs.
     *
     *  Voxel costs must be non-negative.
     */
    fun shortestPath(
        from: Voxel,
        to: Voxel,
        heuristic: (current: Voxel, target: Voxel) -> Double = VoxelHeuristics.ZERO,
    ): WeightedPath<Voxel>? {
        val start = normalize(from)
        val goal = normalize(to)
        if (start == goal) return WeightedPath(listOf(start), 0.0)
        if (!isPassable(start) || !isPassable(goal)) return null

        val gScore: MutableMap<Voxel, Double> = HashMap()
        val parents: MutableMap<Voxel, Voxel> = HashMap()
        val finalized: MutableSet<Voxel> = HashSet()
        gScore[start] = 0.0

        val pq = java.util.PriorityQueue<Pair<Voxel, Double>>(compareBy { it.second })
        pq.add(start to heuristic(start, goal))

        while (pq.isNotEmpty()) {
            val current = pq.poll().first
            if (current in finalized) continue
            finalized.add(current)

            if (current == goal) {
                val path = ArrayDeque<Voxel>()
                var node: Voxel? = current
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
        from: Voxel,
        to: Voxel,
        heuristic: (current: Voxel, target: Voxel) -> Double = VoxelHeuristics.ZERO,
    ): Double = shortestPath(from, to, heuristic)?.totalWeight ?: Double.POSITIVE_INFINITY

    // ── Distance fields ────────────────────────────────────────────────────

    /**
     *  Single-source distance field: for each passable voxel, the
     *  shortest-path cost from [source] to that voxel. Unreachable
     *  voxels are absent from the result map. Useful when many
     *  agents share the same goal; compute the field once via
     *  multi-source [distanceField], then look up distances per-voxel
     *  in O(1).
     */
    fun distanceField(source: Voxel): Map<Voxel, Double> = distanceField(setOf(source))

    /**
     *  Multi-source distance field: for each passable voxel, the
     *  shortest-path cost from the *nearest* source in [sources].
     *  The canonical use case is "distance from any charging-station
     *  voxel" for drone models — compute once at setup, then every
     *  drone follows the gradient toward zero in O(1) per step.
     *
     *  Sources themselves have distance 0. Unreachable voxels are
     *  absent from the result map.
     */
    fun distanceField(sources: Set<Voxel>): Map<Voxel, Double> {
        val dist: MutableMap<Voxel, Double> = HashMap()
        val finalized: MutableSet<Voxel> = HashSet()
        val pq = java.util.PriorityQueue<Pair<Voxel, Double>>(compareBy { it.second })

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

    // ── Reachability ───────────────────────────────────────────────────────

    /**
     *  All voxels reachable from [start] via passable neighbors
     *  (BFS). Includes [start] itself if it's passable; returns an
     *  empty set if [start] is blocked.
     */
    fun reachableFrom(start: Voxel): Set<Voxel> {
        val s = normalize(start)
        if (!isPassable(s)) return emptySet()
        val visited = LinkedHashSet<Voxel>()
        visited.add(s)
        val queue = ArrayDeque<Voxel>()
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
    fun isReachable(from: Voxel, to: Voxel): Boolean {
        val start = normalize(from)
        val goal = normalize(to)
        if (!isPassable(start) || !isPassable(goal)) return false
        if (start == goal) return true
        val visited = mutableSetOf(start)
        val queue = ArrayDeque<Voxel>()
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
        private val SQRT3 = sqrt(3.0)

        /** All 26 neighbor offsets for Moore-26 movement. */
        private val MOORE_26_OFFSETS: List<Triple<Int, Int, Int>> = buildList {
            for (dl in -1..1) for (dr in -1..1) for (dc in -1..1) {
                if (dc == 0 && dr == 0 && dl == 0) continue
                add(Triple(dc, dr, dl))
            }
        }

        /** The 6 face-adjacent neighbor offsets for Von Neumann-6 movement. */
        private val VON_NEUMANN_6_OFFSETS: List<Triple<Int, Int, Int>> = listOf(
            Triple(-1, 0, 0), Triple(1, 0, 0),
            Triple(0, -1, 0), Triple(0, 1, 0),
            Triple(0, 0, -1), Triple(0, 0, 1),
        )
    }
}
