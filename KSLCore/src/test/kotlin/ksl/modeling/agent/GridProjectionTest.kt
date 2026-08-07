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

import ksl.examples.general.agent.CorridorPedestrianExample
import ksl.examples.general.agent.GridEpidemicExample
import ksl.examples.general.agent.NetworkRumorExample
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Tests for `GridProjection` — cell placement, occupancy rules, neighbourhood
 *  queries and torus wrapping.
 *
 *  **Phase D.** This was one section of `ContextTest`, a 1274-line umbrella covering
 *  `Context` and all three projections together. Coverage was never the problem;
 *  finding it was. A reader asking what is tested about a given type had no file to
 *  open, and a gap in a 60-test umbrella is invisible in a way a gap in a per-type
 *  file is not — the most likely reason the missing `runDynamics*` and
 *  `GridGeometrySpec` coverage went unnoticed until a deliberate sweep.
 *
 *  Randomised pathfinding over `GridGraph` lives in `GridPathPropertyTest`, and the
 *  serialisable geometry round-trip in `GridGeometrySpecTest`.
 */
class GridProjectionTest {

    private class GridTestModel(parent: ModelElement, occupancy: GridOccupancy = GridOccupancy.MULTIPLE, torus: Boolean = false) :
        AgentModel(parent, "gridmodel") {
        val context: Context<Agent> = Context("grid-agents")
        val grid: GridProjection<Agent> = GridProjection(
            context = context, columns = 5, rows = 5, occupancy = occupancy, torus = torus,
        )
        inner class Walker(aName: String) : Agent(aName)
    }

    @Test
    fun gridProjectionPlacesAgentsAtCells() {
        val model = Model("GridPlaceTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a"); val b = tm.Walker("b")
        tm.context.add(a); tm.context.add(b)
        tm.grid.placeAt(a, Cell(1, 2))
        tm.grid.placeAt(b, 3, 4)

        assertEquals(Cell(1, 2), tm.grid.cellOf(a))
        assertEquals(Cell(3, 4), tm.grid.cellOf(b))
        assertEquals(listOf(a), tm.grid.agentsAt(Cell(1, 2)))
        assertEquals(listOf(b), tm.grid.agentsAt(3, 4))
        assertTrue(tm.grid.isEmpty(Cell(0, 0)))
    }

    @Test
    fun gridProjectionMoveUpdatesCell() {
        val model = Model("GridMoveTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a")
        tm.context.add(a)
        tm.grid.placeAt(a, 1, 1)
        tm.grid.moveTo(a, 2, 2)

        assertEquals(Cell(2, 2), tm.grid.cellOf(a))
        assertTrue(tm.grid.agentsAt(Cell(1, 1)).isEmpty(), "agent should have left the previous cell")
        assertEquals(listOf(a), tm.grid.agentsAt(Cell(2, 2)))
    }

    @Test
    fun gridProjectionMultiOccupancyAllowsCoLocation() {
        val model = Model("GridMultiTest")
        val tm = GridTestModel(model, occupancy = GridOccupancy.MULTIPLE)
        val a = tm.Walker("a"); val b = tm.Walker("b"); val c = tm.Walker("c")
        for (w in listOf(a, b, c)) tm.context.add(w)
        for (w in listOf(a, b, c)) tm.grid.placeAt(w, 2, 2)

        val occupants = tm.grid.agentsAt(2, 2)
        assertEquals(3, occupants.size)
        assertContains(occupants, a); assertContains(occupants, b); assertContains(occupants, c)
    }

    @Test
    fun gridProjectionSingleOccupancyRejectsConflict() {
        val model = Model("GridSingleTest")
        val tm = GridTestModel(model, occupancy = GridOccupancy.SINGLE)
        val a = tm.Walker("a"); val b = tm.Walker("b")
        tm.context.add(a); tm.context.add(b)
        tm.grid.placeAt(a, 2, 2)

        // tryPlaceAt returns false on conflict; placeAt throws.
        assertTrue(!tm.grid.tryPlaceAt(b, Cell(2, 2)), "tryPlaceAt should reject conflicting placement")
        try {
            tm.grid.placeAt(b, 2, 2)
            error("placeAt should have thrown on single-occupancy conflict")
        } catch (e: IllegalStateException) {
            assertContains(e.message ?: "", "already occupied")
        }
    }

    @Test
    fun gridProjectionOutOfBoundsThrowsWhenNotTorus() {
        val model = Model("GridOOBTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a")
        tm.context.add(a)
        try {
            tm.grid.placeAt(a, -1, 0)
            error("placeAt should have thrown for col -1")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            tm.grid.placeAt(a, 0, 5)
            error("placeAt should have thrown for row 5 (rows=5)")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun gridProjectionTorusWrapsCoordinates() {
        val model = Model("GridTorusTest")
        val tm = GridTestModel(model, torus = true)
        val a = tm.Walker("a")
        tm.context.add(a)
        // col = -1 wraps to columns - 1 = 4; row = 7 wraps to 7 % 5 = 2.
        tm.grid.placeAt(a, -1, 7)
        assertEquals(Cell(4, 2), tm.grid.cellOf(a))
    }

    @Test
    fun gridMooreNeighborhoodReturnsEightCells() {
        val model = Model("GridMooreTest")
        val tm = GridTestModel(model)
        val center = Cell(2, 2)
        val nbrs = tm.grid.mooreNeighborhood(center)
        assertEquals(8, nbrs.size, "Moore neighborhood of an interior cell should be 8 cells")
        // All 8 surrounding cells should be present.
        for (dc in -1..1) {
            for (dr in -1..1) {
                if (dc == 0 && dr == 0) continue
                assertContains(nbrs, Cell(2 + dc, 2 + dr))
            }
        }
    }

    @Test
    fun gridMooreNeighborhoodAtCornerHasFewerCellsWhenBounded() {
        val model = Model("GridCornerTest")
        val tm = GridTestModel(model)  // not torus
        val corner = Cell(0, 0)
        val nbrs = tm.grid.mooreNeighborhood(corner)
        // (0,0)'s neighbors in bounds: (1,0), (0,1), (1,1) — 3 cells.
        assertEquals(3, nbrs.size)
        assertContains(nbrs, Cell(1, 0))
        assertContains(nbrs, Cell(0, 1))
        assertContains(nbrs, Cell(1, 1))
    }

    @Test
    fun gridMooreNeighborhoodOnTorusAlwaysReturnsEightCells() {
        val model = Model("GridTorusCornerTest")
        val tm = GridTestModel(model, torus = true)
        val corner = Cell(0, 0)
        val nbrs = tm.grid.mooreNeighborhood(corner)
        assertEquals(8, nbrs.size, "torus Moore neighborhood of any cell is always 8 cells")
        // Wrap-around neighbors expected: (4,4), (4,0), (4,1), (0,4), (0,1), (1,4), (1,0), (1,1).
        assertContains(nbrs, Cell(4, 4))
        assertContains(nbrs, Cell(4, 0))
        assertContains(nbrs, Cell(0, 4))
    }

    @Test
    fun gridVonNeumannNeighborhoodReturnsFourCells() {
        val model = Model("GridVNTest")
        val tm = GridTestModel(model)
        val nbrs = tm.grid.vonNeumannNeighborhood(Cell(2, 2))
        assertEquals(4, nbrs.size)
        assertContains(nbrs, Cell(1, 2))
        assertContains(nbrs, Cell(3, 2))
        assertContains(nbrs, Cell(2, 1))
        assertContains(nbrs, Cell(2, 3))
    }

    @Test
    fun gridNeighborsOfAgentReturnsCoLocatedAndAdjacentAgents() {
        val model = Model("GridNeighborsTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a"); val b = tm.Walker("b"); val c = tm.Walker("c"); val d = tm.Walker("d")
        for (w in listOf(a, b, c, d)) tm.context.add(w)

        tm.grid.placeAt(a, 2, 2)       // center
        tm.grid.placeAt(b, 2, 2)       // co-located with a
        tm.grid.placeAt(c, 3, 3)       // Moore neighbor (diagonal)
        tm.grid.placeAt(d, 4, 4)       // outside Moore radius

        val nbrs = tm.grid.neighborsOf(a, radius = 1, metric = GridMetric.CHEBYSHEV)
        assertEquals(2, nbrs.size, "expected b (co-located) and c (diagonal) but not d")
        assertContains(nbrs, b)
        assertContains(nbrs, c)
    }

    @Test
    fun gridDropsAgentCellWhenContextRemoves() {
        val model = Model("GridRemoveTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a")
        tm.context.add(a)
        tm.grid.placeAt(a, 2, 2)
        assertEquals(Cell(2, 2), tm.grid.cellOf(a))

        tm.context.remove(a)
        assertNull(tm.grid.cellOf(a))
        assertTrue(tm.grid.agentsAt(Cell(2, 2)).isEmpty())
    }
}
