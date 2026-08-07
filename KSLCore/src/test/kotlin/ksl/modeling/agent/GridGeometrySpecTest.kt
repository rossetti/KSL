/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase C5 — the `GridGeometrySpec` round-trip.
 *
 *  `GridGraph.toSpec`, `GridGeometrySpec.toGridGraph`, `toFlattenedGridGraph` and
 *  `CellCost` had **no test and no example** — nothing anywhere constructed a
 *  `GridGeometrySpec`. It is a `@Serializable`, "TOML/JSON friendly" description
 *  documented as one half of a producer/consumer pair, and serialization
 *  round-trips are among the likeliest things to break silently under refactoring:
 *  a renamed field or a dropped default costs nothing at compile time and
 *  everything at load time.
 *
 *  The properties asserted are that a graph survives graph → spec → graph intact,
 *  and that the spec survives a JSON round-trip, since being loadable from a layout
 *  document is the whole point of it existing.
 */
class GridGeometrySpecTest {

    /** A graph with every structural feature set to a non-default value. */
    private fun richGraph(): GridGraph {
        val g = GridGraph(
            columns = 7,
            rows = 5,
            torus = true,
            movementRule = MovementRule.VON_NEUMANN,
            allowCornerCutting = true,
        )
        g.block(Cell(1, 1))
        g.block(Cell(4, 2))
        g.block(Cell(6, 0))
        g.setCellCost(Cell(0, 0), 3.5)
        g.setCellCost(Cell(2, 3), 1.25)
        return g
    }

    private fun assertSameStructure(expected: GridGraph, actual: GridGraph, note: String) {
        assertEquals(expected.columns, actual.columns, "$note: columns")
        assertEquals(expected.rows, actual.rows, "$note: rows")
        assertEquals(expected.torus, actual.torus, "$note: torus")
        assertEquals(expected.movementRule, actual.movementRule, "$note: movementRule")
        assertEquals(expected.allowCornerCutting, actual.allowCornerCutting, "$note: cornerCutting")
        assertEquals(expected.blockedCellSet, actual.blockedCellSet, "$note: blocked cells")
        assertEquals(expected.cellCostMap, actual.cellCostMap, "$note: cell costs")
    }

    // ── Round-trip ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("C5: graph -> spec -> graph preserves every structural feature")
    fun graphSurvivesRoundTrip() {
        val original = richGraph()
        val rebuilt = original.toSpec("floor").toGridGraph()
        assertSameStructure(original, rebuilt, "round-trip")
    }

    /**
     *  Structure must survive, but so must *behaviour*: a rebuilt graph has to
     *  produce the same paths. Comparing fields alone would miss a feature that
     *  round-trips as data yet is not applied when the graph is rebuilt.
     */
    @Test
    @DisplayName("C5: a rebuilt graph produces identical shortest paths")
    fun rebuiltGraphPathsIdentically() {
        val original = richGraph()
        val rebuilt = original.toSpec("floor").toGridGraph()
        var compared = 0
        for (c in 0 until original.columns) {
            for (r in 0 until original.rows) {
                val to = Cell(c, r)
                val a = original.shortestPath(Cell(0, 4), to, GridHeuristics.ZERO)
                val b = rebuilt.shortestPath(Cell(0, 4), to, GridHeuristics.ZERO)
                assertEquals(a == null, b == null, "reachability differs for $to")
                if (a != null && b != null) {
                    assertEquals(a.totalWeight, b.totalWeight, 1e-9, "path cost differs for $to")
                    compared++
                }
            }
        }
        assertTrue(compared > 10, "expected many comparable targets; got $compared")
    }

    /**
     *  Physical placement is a layout concern, not part of the graph. It rides on
     *  the spec and must survive, but rebuilding must not depend on it — a spec
     *  saved without placement has to rebuild just as well.
     */
    @Test
    @DisplayName("C5: placement fields ride on the spec but are not part of the graph")
    fun placementFieldsAreCarriedButNotStructural() {
        val g = richGraph()
        val placed = g.toSpec("floor", originX = 5.0, originY = -2.0, cellSize = 0.25)
        assertEquals(5.0, placed.originX)
        assertEquals(-2.0, placed.originY)
        assertEquals(0.25, placed.cellSize)

        val unplaced = g.toSpec("floor")
        assertEquals(null, unplaced.originX, "placement is optional")
        assertSameStructure(g, unplaced.toGridGraph(), "unplaced rebuild")
        assertSameStructure(g, placed.toGridGraph(), "placed rebuild")
    }

    // ── Serialization ────────────────────────────────────────────────────────

    /**
     *  The spec exists to be written into a layout document and read back, so a JSON
     *  round-trip is the contract that actually matters. A rebuilt-from-JSON graph
     *  must match the original.
     */
    @Test
    @DisplayName("C5: the spec survives a JSON round-trip and still rebuilds the graph")
    fun specSurvivesJsonRoundTrip() {
        val original = richGraph()
        val spec = original.toSpec("floor", originX = 1.0, originY = 2.0, cellSize = 0.5)

        val json = Json.encodeToString(GridGeometrySpec.serializer(), spec)
        val decoded = Json.decodeFromString(GridGeometrySpec.serializer(), json)

        assertEquals(spec, decoded, "the spec should survive JSON unchanged")
        assertSameStructure(original, decoded.toGridGraph(), "rebuild from JSON")
    }

    /**
     *  Blocked cells and costs are emitted in a deterministic order, which is what
     *  makes a committed layout document diffable rather than churning on every
     *  save.
     */
    @Test
    @DisplayName("C5: blocked cells and costs are emitted in a stable order")
    fun specOrderingIsDeterministic() {
        val a = GridGraph(4, 4).also {
            it.block(Cell(3, 3)); it.block(Cell(0, 1)); it.block(Cell(2, 0))
            it.setCellCost(Cell(3, 0), 2.0); it.setCellCost(Cell(1, 1), 4.0)
        }
        val first = a.toSpec("s")
        val second = a.toSpec("s")
        assertEquals(first.blockedCells, second.blockedCells, "blocked ordering must be stable")
        assertEquals(first.cellCosts, second.cellCosts, "cost ordering must be stable")
        assertEquals(
            listOf(Cell(0, 1), Cell(2, 0), Cell(3, 3)), first.blockedCells,
            "blocked cells should be sorted by column then row",
        )
    }

    // ── VoxelGraph.toFlattenedGridGraph ──────────────────────────────────────

    /**
     *  Not part of the spec round-trip — it belongs to `VoxelGraph` — but it shares
     *  the "geometry crossing a boundary" concern and had no coverage either. It
     *  projects a 3D obstacle field onto the 2D plane the renderer draws: cell
     *  (col,row) is blocked if **any** layer at (col,row) is blocked.
     */
    @Test
    @DisplayName("C5: a voxel graph flattens to its 2D no-fly footprint")
    fun voxelGraphFlattensToFootprint() {
        val v = VoxelGraph(columns = 4, rows = 3, layers = 5, torus = true)
        // A tower: one blocked voxel high up at (1,1) should block that whole cell.
        v.block(Voxel(1, 1, 4))
        // Two blocked voxels stacked at (2,0) should block it exactly once.
        v.block(Voxel(2, 0, 0))
        v.block(Voxel(2, 0, 1))

        val flat = v.toFlattenedGridGraph()
        assertEquals(4, flat.columns)
        assertEquals(3, flat.rows)
        assertEquals(true, flat.torus, "the torus setting carries over")
        assertEquals(
            setOf(Cell(1, 1), Cell(2, 0)), flat.blockedCellSet,
            "a cell is blocked if any layer above it is",
        )
    }

    /** A voxel graph with nothing blocked flattens to a fully passable grid. */
    @Test
    @DisplayName("C5: an unobstructed voxel graph flattens to an empty footprint")
    fun unobstructedVoxelGraphFlattensEmpty() {
        val flat = VoxelGraph(3, 3, 3).toFlattenedGridGraph()
        assertTrue(flat.blockedCellSet.isEmpty(), "nothing blocked means nothing blocked")
    }
}
