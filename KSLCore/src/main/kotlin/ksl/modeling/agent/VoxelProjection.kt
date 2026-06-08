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
import kotlin.math.max
import kotlin.math.sqrt

/**
 *  A 3D lattice projection: each agent in the [context] occupies an
 *  integer-coordinate [Voxel]. The grid has fixed [columns], [rows],
 *  and [layers]; voxels are addressed by `(col, row, layer)` with
 *  `col ∈ [0, columns)`, `row ∈ [0, rows)`, `layer ∈ [0, layers)`.
 *  The 3D analog of [GridProjection].
 *
 *  Occupancy semantics:
 *   - [VoxelOccupancy.MULTIPLE] (default) — multiple agents may
 *     share a voxel. Typical for swarm / flock / drone models with
 *     low altitude separation.
 *   - [VoxelOccupancy.SINGLE] — at most one agent per voxel.
 *     [placeAt] throws if the target voxel is already occupied by a
 *     different agent; use [tryPlaceAt] for the conditional case.
 *     Useful for warehouse-style storage models where each slot
 *     holds one item.
 *
 *  Boundary semantics:
 *   - Bounded (default) — coordinates outside the bounds throw.
 *   - Torus (`torus = true`) — coordinates wrap on all three axes.
 *     (A "ground+ceiling" model — wrap horizontally only, hard
 *     boundary vertically — should keep `torus = false` and clamp
 *     the vertical axis in the user's motion code.)
 *
 *  Spatial queries — [neighborsOf], [agentsAt], [voxelsWithin] — do
 *  linear scans over the occupancy map. Performance is fine for
 *  typical agent counts; for sphere-shaped continuous neighbor
 *  queries against many agents, use [ContinuousVolume] which has a
 *  spatial-hash index.
 */
class VoxelProjection<A : AgentLike> @JvmOverloads constructor(
    val context: AgentModel.Context<A>,
    val columns: Int,
    val rows: Int,
    val layers: Int,
    val occupancy: VoxelOccupancy = VoxelOccupancy.MULTIPLE,
    val torus: Boolean = false,
    override val name: String = "voxelGrid",
) : Projection<A> {

    /** Mutable global defaults for [VoxelProjection] queries. */
    companion object Defaults {
        /** Default radius for [neighborsOf] when none is specified. Must be non-negative. */
        var neighborhoodRadius: Int by nonNegative(1)
    }

    init {
        require(columns > 0) { "columns must be positive; was $columns" }
        require(rows > 0) { "rows must be positive; was $rows" }
        require(layers > 0) { "layers must be positive; was $layers" }
    }

    private val agentToVoxel: MutableMap<A, Voxel> = mutableMapOf()
    private val voxelToAgents: MutableMap<Voxel, MutableList<A>> = mutableMapOf()

    init {
        context.addProjection(this)
    }

    /** Number of agents currently placed on the grid. */
    val size: Int
        get() = agentToVoxel.size

    /**
     *  Normalize coordinates into the grid's domain. For a bounded
     *  grid, out-of-range coordinates throw. For a torus, they wrap
     *  on all three axes.
     */
    private fun normalize(col: Int, row: Int, layer: Int): Voxel {
        if (torus) {
            val c = ((col % columns) + columns) % columns
            val r = ((row % rows) + rows) % rows
            val l = ((layer % layers) + layers) % layers
            return Voxel(c, r, l)
        }
        require(col in 0 until columns) { "col $col out of range [0, $columns) for non-torus grid" }
        require(row in 0 until rows) { "row $row out of range [0, $rows) for non-torus grid" }
        require(layer in 0 until layers) { "layer $layer out of range [0, $layers) for non-torus grid" }
        return Voxel(col, row, layer)
    }

    private fun normalize(voxel: Voxel): Voxel = normalize(voxel.col, voxel.row, voxel.layer)

    /**
     *  Place [agent] at the given voxel. For [VoxelOccupancy.SINGLE]
     *  grids, throws if the target voxel is already occupied by a
     *  different agent.
     */
    fun placeAt(agent: A, voxel: Voxel) {
        val v = normalize(voxel)
        if (occupancy == VoxelOccupancy.SINGLE) {
            val occupants = voxelToAgents[v]
            if (occupants != null && occupants.isNotEmpty()) {
                val other = occupants.first()
                check(other === agent) {
                    "voxel $v already occupied by '${other.name}' in single-occupancy grid '$name'"
                }
                return
            }
        }
        agentToVoxel[agent]?.let { prev ->
            voxelToAgents[prev]?.remove(agent)
            if (voxelToAgents[prev]?.isEmpty() == true) voxelToAgents.remove(prev)
        }
        agentToVoxel[agent] = v
        voxelToAgents.getOrPut(v) { mutableListOf() }.add(agent)
    }

    fun placeAt(agent: A, col: Int, row: Int, layer: Int) =
        placeAt(agent, Voxel(col, row, layer))

    /** Equivalent to [placeAt]; provided for symmetry with movement-style code. */
    fun moveTo(agent: A, voxel: Voxel) = placeAt(agent, voxel)
    fun moveTo(agent: A, col: Int, row: Int, layer: Int) =
        placeAt(agent, Voxel(col, row, layer))

    /**
     *  Conditional placement for single-occupancy grids: returns
     *  true if the voxel was free (or already held this agent) and
     *  the placement succeeded; false if the voxel was occupied by
     *  another agent. Always succeeds for multi-occupancy grids.
     */
    fun tryPlaceAt(agent: A, voxel: Voxel): Boolean {
        val v = normalize(voxel)
        if (occupancy == VoxelOccupancy.SINGLE) {
            val occupants = voxelToAgents[v]
            if (occupants != null && occupants.isNotEmpty()) {
                val other = occupants.first()
                if (other !== agent) return false
            }
        }
        placeAt(agent, v)
        return true
    }

    /** Voxel currently occupied by [agent], or null if not placed. */
    fun voxelOf(agent: A): Voxel? = agentToVoxel[agent]

    /** All agents currently on [voxel]. Empty list if the voxel is unoccupied. */
    fun agentsAt(voxel: Voxel): List<A> {
        val v = normalize(voxel)
        return voxelToAgents[v]?.toList() ?: emptyList()
    }

    fun agentsAt(col: Int, row: Int, layer: Int): List<A> =
        agentsAt(Voxel(col, row, layer))

    /** True if [voxel] has no occupants. */
    fun isEmpty(voxel: Voxel): Boolean = agentsAt(voxel).isEmpty()

    // ── Voxel-only neighborhood queries ────────────────────────────────────

    /**
     *  The 26 voxels in the Moore-26 neighborhood (Chebyshev3D
     *  distance 1) around [voxel]. If [includeSelf] is true, [voxel]
     *  is included (27 total).
     */
    fun moore26Neighborhood(voxel: Voxel, includeSelf: Boolean = false): List<Voxel> =
        voxelsWithin(voxel, radius = 1, metric = VoxelMetric.CHEBYSHEV, includeSelf = includeSelf)

    /**
     *  The 6 voxels in the Von-Neumann-6 neighborhood (Manhattan3D
     *  distance 1) around [voxel]. If [includeSelf] is true, [voxel]
     *  is included (7 total).
     */
    fun vonNeumann6Neighborhood(voxel: Voxel, includeSelf: Boolean = false): List<Voxel> =
        voxelsWithin(voxel, radius = 1, metric = VoxelMetric.MANHATTAN, includeSelf = includeSelf)

    /**
     *  All voxels within [radius] of [voxel] under the given [metric].
     *  Excludes [voxel] itself unless [includeSelf] is true. Voxels
     *  outside the bounds are dropped for non-torus grids.
     */
    fun voxelsWithin(
        voxel: Voxel,
        radius: Int,
        metric: VoxelMetric = VoxelMetric.CHEBYSHEV,
        includeSelf: Boolean = false,
    ): List<Voxel> {
        require(radius >= 0) { "radius must be non-negative; was $radius" }
        val center = normalize(voxel)
        // LinkedHashSet de-dupes the same voxel appearing twice on a
        // small torus (e.g., radius >= layers / 2) while preserving
        // iteration order.
        val out = LinkedHashSet<Voxel>()
        for (dl in -radius..radius) {
            for (dr in -radius..radius) {
                for (dc in -radius..radius) {
                    if (!includeSelf && dl == 0 && dr == 0 && dc == 0) continue
                    val d = when (metric) {
                        VoxelMetric.CHEBYSHEV -> max(max(abs(dl), abs(dr)), abs(dc)).toDouble()
                        VoxelMetric.MANHATTAN -> (abs(dl) + abs(dr) + abs(dc)).toDouble()
                        VoxelMetric.EUCLIDEAN -> sqrt((dl * dl + dr * dr + dc * dc).toDouble())
                    }
                    if (d > radius) continue
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
                    out.add(candidate)
                }
            }
        }
        return out.toList()
    }

    // ── Agent neighborhood queries ─────────────────────────────────────────

    /**
     *  All agents currently in voxels within [radius] of [voxel]
     *  under [metric]. Excludes occupants of [voxel] itself unless
     *  [includeCenter] is true.
     */
    fun agentsWithin(
        voxel: Voxel,
        radius: Int,
        metric: VoxelMetric = VoxelMetric.CHEBYSHEV,
        includeCenter: Boolean = false,
    ): List<A> {
        val vs = voxelsWithin(voxel, radius, metric, includeSelf = includeCenter)
        return vs.flatMap { agentsAt(it) }
    }

    /**
     *  All other agents within [radius] of [agent]'s voxel. Excludes
     *  [agent] itself but includes any co-occupants of its voxel.
     */
    fun neighborsOf(
        agent: A,
        radius: Int = Defaults.neighborhoodRadius,
        metric: VoxelMetric = VoxelMetric.CHEBYSHEV,
    ): List<A> {
        val voxel = agentToVoxel[agent] ?: return emptyList()
        return agentsWithin(voxel, radius, metric, includeCenter = true).filter { it !== agent }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onAgentLeft(agent: A) {
        val voxel = agentToVoxel.remove(agent) ?: return
        voxelToAgents[voxel]?.remove(agent)
        if (voxelToAgents[voxel]?.isEmpty() == true) voxelToAgents.remove(voxel)
    }
}

/** Occupancy rule for a [VoxelProjection]; see that class for details. */
enum class VoxelOccupancy {
    /** Multiple agents may share a voxel. */
    MULTIPLE,

    /** At most one agent per voxel; [VoxelProjection.placeAt] throws on conflict. */
    SINGLE,
}
