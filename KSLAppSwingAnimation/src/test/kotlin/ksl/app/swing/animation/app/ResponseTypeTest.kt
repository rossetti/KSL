package ksl.app.swing.animation.app

import ksl.animation.ElementKind
import ksl.animation.animationInventory
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** U6: Response/TWResponse delineation in the inventory and the editor's response table. */
class ResponseTypeTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRU6").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `inventory delineates time-weighted from tally responses`() {
        val m = Model("TRInv").also { TestAndRepairShopWithMovableResources(it, "TR") }
        val inv = m.animationInventory()
        assertTrue(inv.responses.isNotEmpty(), "the model has responses")
        assertTrue(inv.timeWeightedResponses.isNotEmpty(), "some responses are time-weighted (e.g. utilizations)")
        assertTrue(inv.timeWeightedResponses.all { it in inv.responses }, "TW responses are a subset of responses")
        val tally = inv.responses.firstOrNull { it !in inv.timeWeightedResponses }
        assertTrue(tally != null, "some responses are tally (e.g. time-in-queue)")
        assertTrue(inv.isTimeWeighted(inv.timeWeightedResponses.first()))
        assertFalse(inv.isTimeWeighted(tally!!))
    }

    @Test
    fun `the response table tags each response as time-weighted or tally`() {
        val c = AnimationAppController("Anim", builder)
        try {
            val inv = c.inventory
            val tw = inv.timeWeightedResponses.first()
            val tally = inv.responses.first { it !in inv.timeWeightedResponses }
            val tags = onEdt {
                val panel = LayoutPanel(c)
                panel.nameCellForTest(ElementKind.RESPONSE, tw) to panel.nameCellForTest(ElementKind.RESPONSE, tally)
            }
            assertContains(tags.first, "time-weighted")
            assertContains(tags.second, "tally")
        } finally { c.close() }
    }
}
