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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Regression tests for the diagonal corner-cutting fix (audit
 *  blocker B2). By default a diagonal move is forbidden when either
 *  orthogonal "shoulder" cell it passes between is blocked, so agents
 *  cannot slip diagonally between two walls. Setting
 *  `allowCornerCutting = true` recovers the looser destination-only
 *  rule.
 */
class CornerCuttingTest {

    // ── GridGraph (2D) ──────────────────────────────────────────────────────

    @Test
    fun diagonalForbiddenWhenBothShouldersBlocked() {
        val g = GridGraph(3, 3) // MOORE, allowCornerCutting = false (default)
        g.block(Cell(1, 0))
        g.block(Cell(0, 1))
        // (0,0)'s only Moore neighbors are (1,0), (0,1), (1,1). The two
        // orthogonals are blocked and the diagonal would cut between
        // them, so the cell is fully boxed in.
        val nbrs = g.passableNeighbors(Cell(0, 0))
        assertFalse(Cell(1, 1) in nbrs, "diagonal must not cut between two blocked cells")
        assertTrue(nbrs.isEmpty(), "the corner cell should have no passable neighbors; got $nbrs")
    }

    @Test
    fun diagonalForbiddenWhenEitherShoulderBlocked() {
        val g = GridGraph(3, 3)
        g.block(Cell(1, 0)) // only one shoulder of the (0,0)->(1,1) diagonal
        val nbrs = g.passableNeighbors(Cell(0, 0))
        assertFalse(Cell(1, 1) in nbrs, "a single blocked shoulder still forbids the diagonal")
        assertContains(nbrs, Cell(0, 1))
        assertEquals(setOf(Cell(0, 1)), nbrs.toSet())
    }

    @Test
    fun diagonalAllowedWhenShouldersClear() {
        val g = GridGraph(3, 3)
        val nbrs = g.passableNeighbors(Cell(0, 0))
        assertContains(nbrs, Cell(1, 1)) // no obstacles: diagonal is fine
    }

    @Test
    fun allowCornerCuttingRecoversDiagonalThroughBlockedShoulders() {
        val g = GridGraph(3, 3, allowCornerCutting = true)
        g.block(Cell(1, 0))
        g.block(Cell(0, 1))
        val nbrs = g.passableNeighbors(Cell(0, 0))
        assertContains(nbrs, Cell(1, 1)) // destination open → permitted
        assertEquals(setOf(Cell(1, 1)), nbrs.toSet())
    }

    @Test
    fun shortestPathRoutesAroundCornerByDefault() {
        // A diagonal barrier between (1,1) and (2,2): the two shoulder
        // cells of that diagonal are blocked.
        fun grid(cut: Boolean) = GridGraph(5, 5, allowCornerCutting = cut).apply {
            block(Cell(2, 1))
            block(Cell(1, 2))
        }

        val direct = grid(cut = true).shortestPath(Cell(1, 1), Cell(2, 2))
        assertNotNull(direct)
        assertEquals(listOf(Cell(1, 1), Cell(2, 2)), direct!!.nodes, "cutting allows the single diagonal step")

        val around = grid(cut = false).shortestPath(Cell(1, 1), Cell(2, 2))
        assertNotNull(around, "a detour must still exist")
        assertFalse(
            around!!.nodes == listOf(Cell(1, 1), Cell(2, 2)),
            "default rule must not take the corner-cutting diagonal",
        )
        assertTrue(
            around.nodes.size > direct.nodes.size,
            "the detour should be longer than the (forbidden) direct diagonal",
        )
    }

    // ── VoxelGraph (3D) ──────────────────────────────────────────────────────

    @Test
    fun voxelBodyDiagonalForbiddenWhenAxisNeighborBlocked() {
        val g = VoxelGraph(2, 2, 2) // MOORE_26, default no cutting
        // Block one of the three axis-neighbors the (0,0,0)->(1,1,1)
        // body-diagonal passes between.
        g.block(Voxel(1, 0, 0))
        val nbrs = g.passableNeighbors(Voxel(0, 0, 0))
        assertFalse(Voxel(1, 1, 1) in nbrs, "body-diagonal must not clip a blocked axis-neighbor")
    }

    @Test
    fun voxelFaceDiagonalForbiddenWhenShoulderBlocked() {
        val g = VoxelGraph(2, 2, 2)
        g.block(Voxel(1, 0, 0)) // shoulder of the in-plane (0,0,0)->(1,1,0) face-diagonal
        val nbrs = g.passableNeighbors(Voxel(0, 0, 0))
        assertFalse(Voxel(1, 1, 0) in nbrs, "face-diagonal must not cut between blocked cells")
    }

    @Test
    fun voxelDiagonalsAllowedWhenClear() {
        val g = VoxelGraph(2, 2, 2)
        val nbrs = g.passableNeighbors(Voxel(0, 0, 0))
        assertContains(nbrs, Voxel(1, 1, 1)) // body-diagonal
        assertContains(nbrs, Voxel(1, 1, 0)) // face-diagonal
    }

    @Test
    fun voxelAllowCornerCuttingRecoversDiagonal() {
        val g = VoxelGraph(2, 2, 2, allowCornerCutting = true)
        g.block(Voxel(1, 0, 0))
        val nbrs = g.passableNeighbors(Voxel(0, 0, 0))
        assertContains(nbrs, Voxel(1, 1, 1))
        assertContains(nbrs, Voxel(1, 1, 0))
    }
}
