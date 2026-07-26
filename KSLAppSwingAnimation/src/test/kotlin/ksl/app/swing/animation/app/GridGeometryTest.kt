package ksl.app.swing.animation.app

import ksl.animation.AnimationLayout
import ksl.animation.BackgroundKind
import ksl.animation.SpatialSpaceDescriptor
import ksl.animation.animationInventory
import ksl.examples.general.animationbundle.Example04BuildingEvacuation
import ksl.examples.general.agent.BuildingEvacuationExample
import ksl.examples.general.agent.PedestrianCrowdExample
import ksl.examples.general.agent.WarehouseAGVExample
import ksl.modeling.agent.Cell
import ksl.modeling.agent.GridGeometrySpec
import ksl.modeling.agent.GridGraph
import ksl.modeling.agent.MovementRule
import ksl.modeling.agent.toGridGraph
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ksl.animation.toToml
import ksl.animation.fromToml

/**
 * P5a/G2: obstacles a modeler declares in a `GridGraph` (and links via `Context.attachGeometry`) are extracted
 * into the animation inventory as a `GridGeometrySpec`, and that spec round-trips through the layout format.
 */
class GridGeometryTest {

    @Test
    fun `building-evacuation walls are extracted from the model into the inventory`() {
        val m = Model("Evac")
        BuildingEvacuationExample(m, "evac")
        val grid = m.animationInventory().spaces.firstOrNull { it.name == "grid" }
        assertNotNull(grid, "the grid projection is in the inventory")
        val geom = grid.geometry
        assertNotNull(geom, "the linked wall graph's geometry was extracted (P5a)")
        // Walls: cols 4 & 5, rows 5..11 except the row-7 doorway = 2 × 6 = 12 blocked cells.
        assertEquals(12, geom.blockedCells.size, "12 wall cells: ${geom.blockedCells}")
        assertTrue(Cell(4, 5) in geom.blockedCells && Cell(5, 11) in geom.blockedCells)
        assertTrue(Cell(4, 7) !in geom.blockedCells, "row-7 doorway stays open")
        assertEquals(15, geom.cols); assertEquals(15, geom.rows)
    }

    @Test
    fun `pedestrian-crowd wall is extracted with an open doorway (continuous space, Batch A)`() {
        val m = Model("Crowd")
        PedestrianCrowdExample(m, "crowd")
        val space = m.animationInventory().spaces.firstOrNull { it.geometry != null }
        assertNotNull(space, "the space with the linked wall geometry is in the inventory (attachGeometry)")
        val geom = space.geometry!!
        assertTrue(geom.blockedCells.isNotEmpty(), "the wall's blocked cells are extracted: ${geom.blockedCells.size}")
        assertTrue(Cell(15, 0) in geom.blockedCells, "the wall column is blocked")
        assertTrue(Cell(15, 12) !in geom.blockedCells, "the doorway (rows 11..13) stays open")
    }

    @Test
    fun `warehouse-AGV racks are extracted into the inventory (Batch A)`() {
        val m = Model("AGV")
        WarehouseAGVExample(m, "agv")
        val space = m.animationInventory().spaces.firstOrNull { it.geometry != null }
        assertNotNull(space, "the space with the linked rack geometry is in the inventory (attachGeometry)")
        assertTrue(space.geometry!!.blockedCells.isNotEmpty(), "the rack blocked cells are extracted")
    }

    @Test
    fun `grid geometry round-trips through TOML and JSON`() {
        val spec = GridGeometrySpec(
            spaceName = "floor", cols = 8, rows = 6, torus = true,
            blockedCells = listOf(Cell(1, 1), Cell(2, 2)),
            originX = 5.0, originY = 3.0, cellSize = 2.0
        )
        val layout = AnimationLayout(
            spaces = listOf(SpatialSpaceDescriptor.Grid("floor", 8, 6, 2.0)),
            spaceGeometry = listOf(spec)
        )
        val fromToml = AnimationLayout.fromToml(layout.toToml()).spaceGeometry.single()
        val fromJson = AnimationLayout.fromJson(layout.toJson()).spaceGeometry.single()
        for (back in listOf(fromToml, fromJson)) {
            assertEquals("floor", back.spaceName)
            assertEquals(listOf(Cell(1, 1), Cell(2, 2)), back.blockedCells)
            assertEquals(true, back.torus); assertEquals(2.0, back.cellSize); assertEquals(5.0, back.originX)
        }
    }

    @Test
    fun `a layout without spaceGeometry defaults to empty (back-compat)`() {
        // A pre-P5 layout JSON (no spaceGeometry field) still deserializes.
        val json = """{"title":"old","spaces":[]}"""
        assertTrue(AnimationLayout.fromJson(json).spaceGeometry.isEmpty())
    }

    @Test
    fun `consume rebuilds an equivalent graph from a spec (P5b)`() {
        val graph = GridGraph(12, 9, torus = true, movementRule = MovementRule.VON_NEUMANN, allowCornerCutting = true).also {
            it.block(Cell(2, 3)); it.block(Cell(7, 7))
            it.setCellCost(Cell(1, 1), 4.5); it.setCellCost(Cell(8, 2), 2.0)
        }
        val rebuilt = graph.toSpec("space").toGridGraph()
        assertEquals(graph.columns, rebuilt.columns); assertEquals(graph.rows, rebuilt.rows)
        assertEquals(graph.torus, rebuilt.torus); assertEquals(graph.movementRule, rebuilt.movementRule)
        assertEquals(graph.allowCornerCutting, rebuilt.allowCornerCutting)
        assertEquals(graph.blockedCellSet, rebuilt.blockedCellSet)
        assertEquals(graph.cellCostMap, rebuilt.cellCostMap)
    }

    @Test
    fun `graph survives a full graph - layout - TOML - graph round-trip and gridGeometry lookup`() {
        val graph = GridGraph(6, 6).also { it.block(Cell(1, 1)); it.block(Cell(4, 2)) }
        val layout = AnimationLayout(
            spaces = listOf(SpatialSpaceDescriptor.Grid("floor", 6, 6, 1.0)),
            spaceGeometry = listOf(graph.toSpec("floor"))
        )
        val reloaded = AnimationLayout.fromToml(layout.toToml())
        val spec = reloaded.gridGeometry("floor")
        assertNotNull(spec, "gridGeometry lookup finds the overlay")
        assertEquals(graph.blockedCellSet, spec.toGridGraph().blockedCellSet, "obstacles survive the full round-trip")
        assertEquals(null, reloaded.gridGeometry("missing"), "lookup of an unknown space is null")
    }

    @Test
    fun `obstacle overlays import (replacing same-named) and remove (P5c)`() {
        val m = Model("Evac"); BuildingEvacuationExample(m, "evac")
        val specs = m.animationInventory().spaces.mapNotNull { it.geometry }
        assertTrue(specs.isNotEmpty(), "the model exposes importable obstacles")
        var layout = AnimationLayout().withSpaceGeometryImported(specs)
        assertEquals(specs.size, layout.spaceGeometry.size)
        layout = layout.withSpaceGeometryImported(specs) // re-import
        assertEquals(specs.size, layout.spaceGeometry.size, "re-import replaces same-named overlays (no dupes)")
        layout = layout.withSpaceGeometryRemoved(specs.first().spaceName)
        assertTrue(layout.spaceGeometry.none { it.spaceName == specs.first().spaceName }, "overlay removed")
    }

    @Test
    fun `Example04 draws model-driven obstacles instead of hand-drawn walls (P5c)`() {
        val m = Example04BuildingEvacuation.buildModel()
        val layout = Example04BuildingEvacuation.buildLayout(m)
        val geom = layout.spaceGeometry.singleOrNull()
        assertNotNull(geom, "obstacles now come from the model's GridGraph")
        assertEquals(12, geom.blockedCells.size)
        assertTrue(layout.background.none { it.kind == BackgroundKind.RECT }, "the hand-drawn rect walls are gone")
    }
}
