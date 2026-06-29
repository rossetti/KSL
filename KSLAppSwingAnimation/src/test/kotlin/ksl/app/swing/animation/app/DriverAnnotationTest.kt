package ksl.app.swing.animation.app

import ksl.animation.animationInventory
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * P6c (C6): a movable resource's internal HomeBaseDriver/Driver is annotated include=false, so the
 * animation inventory discovers it but flags it out of capture/styling instead of treating it as a
 * first-class domain entity/process.
 */
class DriverAnnotationTest {

    @Test
    fun `the internal Driver entity and its return-home process are flagged not-included`() {
        val inv = Model("MovableResourcesModel").also { TestAndRepairShopWithMovableResources(it, "TR") }.animationInventory()
        val driver = inv.entityTypes.firstOrNull { it.typeName == "Driver" }
        assertNotNull(driver, "the internal Driver entity type is discovered: ${inv.entityTypes.map { it.typeName }}")
        assertFalse(driver.include, "Driver is flagged include=false (internal plumbing)")
        // Its return-home process, if surfaced, is also opted out.
        driver.processes.forEach { assertFalse(it.include, "the Driver's process '${it.name}' is opted out") }
    }
}
