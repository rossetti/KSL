package ksl.app.swing.animation.app

import ksl.animation.animationInventory
import ksl.examples.general.animationbundle.Example15DroneDelivery
import ksl.examples.general.agent.DroneDeliveryExample
import ksl.modeling.agent.Cell
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Voxel-obstacle extraction: a 3D VoxelGraph's no-fly zones are flattened to a 2D footprint and surface as a
 * grid-obstacle overlay (reusing the P5 pipeline), so the DroneDelivery model's no-fly buildings can be drawn
 * in the 2D animation.
 */
class VoxelObstacleTest {

    @Test
    fun `drone no-fly voxels flatten into the inventory and the layout (voxel obstacles)`() {
        val m = Model("Drone")
        DroneDeliveryExample(m, "drones")
        val geom = m.animationInventory().spaces.firstOrNull { it.geometry != null }?.geometry
        assertNotNull(geom, "the flattened no-fly footprint is extracted: ${m.animationInventory().spaces}")
        // Model no-fly zone #1 is cols 8..12 × rows 8..12 (×8 layers); its footprint is the 5×5 = 25 cells.
        assertEquals(30, geom.cols); assertEquals(30, geom.rows)
        assertTrue(Cell(10, 10) in geom.blockedCells, "a no-fly cell is blocked: ${geom.blockedCells.size} cells")
        // Layers collapse: a (col,row) appears once regardless of how many layers were blocked there.
        assertEquals(geom.blockedCells.size, geom.blockedCells.toSet().size, "footprint cells are unique")

        // The example layout authors the flattened obstacles as a grid-geometry overlay.
        val layout = Example15DroneDelivery.buildLayout(Example15DroneDelivery.buildModel())
        assertTrue(layout.spaceGeometry.isNotEmpty(), "the example draws the no-fly footprint")
        assertTrue(layout.spaceGeometry.first().blockedCells.isNotEmpty())
    }

    @Test
    fun `drone layout marks the depot and the delivery points (item 9)`() {
        val layout = Example15DroneDelivery.buildLayout(Example15DroneDelivery.buildModel())
        assertTrue(layout.stations.any { it.label?.contains("Depot") == true }, "the depot (pickup) is marked")
        assertEquals(4, layout.stations.count { it.stationName.startsWith("drop-") },
            "the four delivery points are marked: ${layout.stations.map { it.stationName }}")
    }
}
