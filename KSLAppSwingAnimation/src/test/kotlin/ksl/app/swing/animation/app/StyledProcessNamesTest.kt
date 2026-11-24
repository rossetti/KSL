package ksl.app.swing.animation.app

import ksl.animation.animationInventory
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  The Process Colors tab offers only the processes of the model's own animated entity types: a movable
 *  resource's return-home process, which is not animated, is not among them.
 */
class StyledProcessNamesTest {

    @Test
    fun `a process marked not to be animated is not offered for styling`() {
        val inv = Model("StyledProcessNames").also { TestAndRepairShopWithMovableResources(it, "TR") }.animationInventory()
        val excluded = inv.entityTypes.flatMap { t -> t.processes.filter { !t.include || !it.include } }.map { it.name }
        assertTrue(excluded.isNotEmpty(), "the movable resources' return-home process is in the inventory")
        val offered = styledProcessNames(inv)
        assertTrue(offered.isNotEmpty(), "the model's own processes are offered")
        assertFalse(offered.any { it in excluded }, "offered $offered, of which excluded: ${offered.filter { it in excluded }}")
    }
}
