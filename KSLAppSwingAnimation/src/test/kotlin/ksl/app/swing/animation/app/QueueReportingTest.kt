package ksl.app.swing.animation.app

import ksl.animation.animationInventory
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P5 (C1): the inventory surfaces each queue's reporting intent, and auto-layout (scaffold) honors it —
 * non-reporting queues (e.g. a movable resource's internal :HomeBaseQ) are captured but not auto-placed.
 */
class QueueReportingTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("MovableResourcesModel").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    @Test
    fun `inventory marks internal home-base queues as non-reporting`() {
        val inv = builder.build(null, null).animationInventory()
        val home = inv.queues.firstOrNull { it.endsWith(":HomeBaseQ") }
        assertNotNull(home, "model exposes movable-resource home-base queues: ${inv.queues}")
        assertFalse(inv.queueReports(home), "a :HomeBaseQ is flagged non-reporting")
        // At least one ordinary (reporting) queue exists for contrast.
        assertTrue(inv.queues.any { inv.queueReports(it) }, "there are reporting queues too")
    }

    @Test
    fun `auto-layout skips non-reporting queues but keeps reporting ones`() {
        val c = AnimationAppController("anim", builder)
        try {
            val layout = c.buildScaffoldLayout()
            assertNotNull(layout, "scaffold built")
            val placed = layout.queues.map { it.queueName }.toSet()
            val inv = c.inventory
            placed.forEach { assertTrue(inv.queueReports(it), "auto-placed queue '$it' should be a reporting queue") }
            assertTrue(inv.queues.any { it.endsWith(":HomeBaseQ") }, "model has home-base queues")
            assertTrue(placed.none { it.endsWith(":HomeBaseQ") }, "no :HomeBaseQ was auto-placed: $placed")
        } finally { c.close() }
    }
}
