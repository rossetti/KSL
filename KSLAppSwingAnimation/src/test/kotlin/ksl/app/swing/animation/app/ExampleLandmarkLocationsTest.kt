package ksl.app.swing.animation.app

import ksl.animation.animationInventory
import ksl.examples.general.agent.BuildingEvacuationExample
import ksl.examples.general.agent.DroneDeliveryExample
import ksl.examples.general.agent.PedestrianCrowdExample
import ksl.examples.general.agent.WarehouseAGVExample
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The agent gallery models name their fixed landmarks via `Context.location`, so the app surfaces + auto-places
 * them as locations with no hand-authored layout — G1 demonstrated across the gallery, not just Ex15.
 */
class ExampleLandmarkLocationsTest {

    @Test
    fun `WarehouseAGV surfaces its chargers as positioned locations`() {
        val inv = Model("agv").also { WarehouseAGVExample(it, "w") }.animationInventory()
        assertTrue(inv.locations.containsAll(listOf("Charger 1", "Charger 2", "Charger 3")), "locations: ${inv.locations}")
        val c1 = inv.locationInfos.first { it.name == "Charger 1" }
        assertTrue(c1.x != null && c1.y != null, "Charger 1 has a position: $c1")
    }

    @Test
    fun `PedestrianCrowd surfaces its doorways as positioned locations`() {
        val inv = Model("crowd").also { PedestrianCrowdExample(it, "c") }.animationInventory()
        assertTrue(inv.locations.any { it.startsWith("Door ") }, "locations: ${inv.locations}")
        assertTrue(inv.locationInfos.any { it.name.startsWith("Door ") && it.x != null }, "a door has a position")
    }

    @Test
    fun `BuildingEvacuation surfaces its exits as positioned locations`() {
        val inv = Model("evac").also { BuildingEvacuationExample(it, "e") }.animationInventory()
        assertTrue(inv.locations.containsAll(listOf("Exit 1", "Exit 2")), "locations: ${inv.locations}")
        assertTrue(inv.locationInfos.any { it.name == "Exit 1" && it.x != null }, "Exit 1 has a position")
    }

    @Test
    fun `DroneDelivery surfaces its depot and drop-offs as positioned locations`() {
        val inv = Model("drone").also { DroneDeliveryExample(it, "d") }.animationInventory()
        assertTrue(inv.locations.containsAll(listOf("Depot", "Drop 1", "Drop 4")), "locations: ${inv.locations}")
        assertTrue(inv.locationInfos.any { it.name == "Depot" && it.x != null }, "Depot has a position")
    }
}
