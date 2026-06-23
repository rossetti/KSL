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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Phase 6.3 tests for [VoxelProjection] — the 3D analog of
 *  [GridProjection]. Covers placement, occupancy, neighborhood
 *  queries, and torus wrapping.
 */
class VoxelProjectionTest {

    private class VPModel(
        parent: ModelElement,
        occupancy: VoxelOccupancy = VoxelOccupancy.MULTIPLE,
        torus: Boolean = false,
    ) : AgentModel(parent, "vp") {
        val ctx: Context<Agent> = Context("agents")
        val grid: VoxelProjection<Agent> = VoxelProjection(
            context = ctx,
            columns = 10,
            rows = 10,
            layers = 5,
            occupancy = occupancy,
            torus = torus,
        )
    }

    // ── Construction ───────────────────────────────────────────────────────

    @Test
    fun constructorRejectsNonPositiveDimensions() {
        val m = Model("ctor")
        val tm = object : AgentModel(m, "vp") {}
        val ctx = tm.Context<AgentModel.Agent>("agents")
        assertThrows<IllegalArgumentException> { VoxelProjection(ctx, 0, 5, 5) }
        assertThrows<IllegalArgumentException> { VoxelProjection(ctx, 5, 0, 5) }
        assertThrows<IllegalArgumentException> { VoxelProjection(ctx, 5, 5, 0) }
        assertThrows<IllegalArgumentException> { VoxelProjection(ctx, 5, 5, -1) }
    }

    // ── Placement / lookup ─────────────────────────────────────────────────

    @Test
    fun placeAtAndVoxelOf() {
        val tm = VPModel(Model("place"))
        val a = tm.Agent("a"); tm.ctx.add(a)
        assertEquals(null, tm.grid.voxelOf(a))
        tm.grid.placeAt(a, Voxel(1, 2, 3))
        assertEquals(Voxel(1, 2, 3), tm.grid.voxelOf(a))
        // Re-placing updates.
        tm.grid.placeAt(a, 5, 6, 4)
        assertEquals(Voxel(5, 6, 4), tm.grid.voxelOf(a))
    }

    @Test
    fun placeAtOutOfRangeThrowsForBoundedGrid() {
        val tm = VPModel(Model("oor"))
        val a = tm.Agent("a"); tm.ctx.add(a)
        assertThrows<IllegalArgumentException> { tm.grid.placeAt(a, -1, 0, 0) }
        assertThrows<IllegalArgumentException> { tm.grid.placeAt(a, 0, 10, 0) }
        assertThrows<IllegalArgumentException> { tm.grid.placeAt(a, 0, 0, 5) }
    }

    @Test
    fun placeAtWrapsOnTorus() {
        val tm = VPModel(Model("torus-place"), torus = true)
        val a = tm.Agent("a"); tm.ctx.add(a)
        tm.grid.placeAt(a, -1, 0, 0)   // wraps to (9, 0, 0)
        assertEquals(Voxel(9, 0, 0), tm.grid.voxelOf(a))
        tm.grid.placeAt(a, 0, 11, 7)   // (0, 1, 2)
        assertEquals(Voxel(0, 1, 2), tm.grid.voxelOf(a))
    }

    // ── Occupancy modes ────────────────────────────────────────────────────

    @Test
    fun multipleOccupancyAllowsCoHabitation() {
        val tm = VPModel(Model("multi"), occupancy = VoxelOccupancy.MULTIPLE)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.grid.placeAt(a, 5, 5, 2)
        tm.grid.placeAt(b, 5, 5, 2)
        assertEquals(2, tm.grid.agentsAt(Voxel(5, 5, 2)).size)
    }

    @Test
    fun singleOccupancyThrowsOnConflict() {
        val tm = VPModel(Model("single"), occupancy = VoxelOccupancy.SINGLE)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.grid.placeAt(a, 5, 5, 2)
        assertThrows<IllegalStateException> { tm.grid.placeAt(b, 5, 5, 2) }
    }

    @Test
    fun tryPlaceAtReturnsFalseOnConflict() {
        val tm = VPModel(Model("try"), occupancy = VoxelOccupancy.SINGLE)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.grid.placeAt(a, 5, 5, 2)
        assertFalse(tm.grid.tryPlaceAt(b, Voxel(5, 5, 2)))
        // b is unplaced; an open voxel succeeds.
        assertTrue(tm.grid.tryPlaceAt(b, Voxel(6, 5, 2)))
        assertEquals(Voxel(6, 5, 2), tm.grid.voxelOf(b))
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Test
    fun onAgentLeftRemovesFromGrid() {
        val tm = VPModel(Model("left"))
        val a = tm.Agent("a"); tm.ctx.add(a)
        tm.grid.placeAt(a, 5, 5, 2)
        assertEquals(1, tm.grid.size)
        tm.ctx.remove(a)
        assertEquals(null, tm.grid.voxelOf(a))
        assertEquals(0, tm.grid.size)
        assertTrue(tm.grid.isEmpty(Voxel(5, 5, 2)))
    }

    // ── Neighborhood queries ───────────────────────────────────────────────

    @Test
    fun moore26NeighborhoodReturns26Cells() {
        val tm = VPModel(Model("moore26"))
        val center = Voxel(5, 5, 2)
        val ns = tm.grid.moore26Neighborhood(center)
        // Should be exactly 26 (3^3 - 1).
        assertEquals(26, ns.size)
        // None equals center.
        assertFalse(center in ns)
        // All within Chebyshev3D distance 1.
        for (n in ns) assertTrue(n.chebyshevDistanceTo(center) == 1)
    }

    @Test
    fun moore26NeighborhoodWithSelfReturns27Cells() {
        val tm = VPModel(Model("moore26-self"))
        val center = Voxel(5, 5, 2)
        val ns = tm.grid.moore26Neighborhood(center, includeSelf = true)
        assertEquals(27, ns.size)
        assertTrue(center in ns)
    }

    @Test
    fun vonNeumann6NeighborhoodReturns6Cells() {
        val tm = VPModel(Model("vn6"))
        val center = Voxel(5, 5, 2)
        val ns = tm.grid.vonNeumann6Neighborhood(center)
        assertEquals(6, ns.size)
        for (n in ns) assertTrue(n.manhattanDistanceTo(center) == 1)
    }

    @Test
    fun mooreNeighborhoodAtBoundaryHasFewerCells() {
        val tm = VPModel(Model("boundary"))
        // Corner: only 7 neighbors fit inside the grid.
        val corner = Voxel(0, 0, 0)
        val ns = tm.grid.moore26Neighborhood(corner)
        assertEquals(7, ns.size)
        for (n in ns) {
            assertTrue(n.col in 0..1 && n.row in 0..1 && n.layer in 0..1)
        }
    }

    @Test
    fun mooreNeighborhoodOnTorusWrapsAtBoundary() {
        val tm = VPModel(Model("torus-nbr"), torus = true)
        val corner = Voxel(0, 0, 0)
        val ns = tm.grid.moore26Neighborhood(corner)
        // On a torus, even a corner has 26 neighbors.
        assertEquals(26, ns.size)
    }

    @Test
    fun voxelsWithinRespectsRadiusAndMetric() {
        val tm = VPModel(Model("within"))
        val center = Voxel(5, 5, 2)
        // Manhattan radius 1 = Von-Neumann-6.
        assertEquals(6, tm.grid.voxelsWithin(center, 1, VoxelMetric.MANHATTAN).size)
        // Chebyshev radius 1 = Moore-26.
        assertEquals(26, tm.grid.voxelsWithin(center, 1, VoxelMetric.CHEBYSHEV).size)
        // Negative radius rejected.
        assertThrows<IllegalArgumentException> {
            tm.grid.voxelsWithin(center, -1, VoxelMetric.CHEBYSHEV)
        }
    }

    // ── agentsWithin / neighborsOf ─────────────────────────────────────────

    @Test
    fun agentsWithinFindsAgentsInRadius() {
        val tm = VPModel(Model("aw"))
        val center = tm.Agent("c"); val near = tm.Agent("n"); val far = tm.Agent("f")
        for (a in listOf(center, near, far)) tm.ctx.add(a)
        tm.grid.placeAt(center, 5, 5, 2)
        tm.grid.placeAt(near, 5, 5, 3)    // chebyshev dist 1
        tm.grid.placeAt(far, 8, 8, 4)     // chebyshev dist 3
        val r1 = tm.grid.agentsWithin(Voxel(5, 5, 2), radius = 1)
        assertTrue(near in r1)
        assertFalse(far in r1)
    }

    @Test
    fun neighborsOfExcludesSelfButIncludesCoOccupants() {
        val tm = VPModel(Model("nb"), occupancy = VoxelOccupancy.MULTIPLE)
        val a = tm.Agent("a"); val b = tm.Agent("b"); val c = tm.Agent("c")
        for (ag in listOf(a, b, c)) tm.ctx.add(ag)
        tm.grid.placeAt(a, 5, 5, 2)
        tm.grid.placeAt(b, 5, 5, 2)   // co-occupant
        tm.grid.placeAt(c, 5, 5, 3)   // chebyshev-1 neighbor
        val ns = tm.grid.neighborsOf(a, radius = 1)
        assertTrue(b in ns)
        assertTrue(c in ns)
        assertFalse(a in ns)
    }

    @Test
    fun neighborsOfUnplacedReturnsEmpty() {
        val tm = VPModel(Model("nb-empty"))
        val a = tm.Agent("a"); tm.ctx.add(a)
        assertTrue(tm.grid.neighborsOf(a).isEmpty())
    }
}
