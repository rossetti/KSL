package ksl.app.swing.animation.app

import ksl.animation.ElementKind
import ksl.animation.animationInventory
import ksl.animation.scaffoldLayout
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

    @Test
    fun `WarehouseAGV agent-resources are not auto-placed as static resources or queues, but stay editor-placeable`() {
        val m = Model("agv").also { WarehouseAGVExample(it, "w") }
        // Opt-in preserved (Batch C): the AGVs are still classified as resources/queues in the inventory, so a
        // modeler can add a busy/idle glyph or their request queue from the editor's Resource/Queue tools.
        val inv = m.animationInventory()
        assertTrue(inv.namesOf(ElementKind.RESOURCE).any { it.startsWith("agv") }, "AGVs remain editor-placeable resources: ${inv.namesOf(ElementKind.RESOURCE)}")
        assertTrue(inv.namesOf(ElementKind.QUEUE).any { it.startsWith("agv") }, "AGV request queues remain editor-placeable: ${inv.namesOf(ElementKind.QUEUE)}")
        // But the scaffold does NOT auto-place them (they animate as moving agents, not static boxes/queues).
        val scaffold = m.scaffoldLayout()
        assertTrue(scaffold.resources.none { it.resourceName.startsWith("agv") }, "no AGV auto-placed as a static resource: ${scaffold.resources.map { it.resourceName }}")
        assertTrue(scaffold.queues.none { it.queueName.startsWith("agv") }, "no AGV request queue auto-placed: ${scaffold.queues.map { it.queueName }}")
    }
}
