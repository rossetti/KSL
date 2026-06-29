package ksl.app.swing.animation.app

import ksl.animation.ElementKind
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V5d: background images and station-routed paths can be added and surface in the editor. */
class BackgroundPathsTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRbg").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `background image and a path are added and listed`() {
        val c = AnimationAppController("Anim", builder)
        try {
            val stations = c.inventory.namesOf(ElementKind.STATION)
            val result = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                c.addBackgroundImage("/tmp/floor.png", 0.0, 0.0, 800.0, 600.0)
                // Place two stations, then route a path through them.
                if (stations.size >= 2) {
                    c.addLayoutElement(ElementKind.STATION, stations[0])
                    c.addLayoutElement(ElementKind.STATION, stations[1])
                    c.addPathThroughStations("route", listOf(stations[0], stations[1]))
                }
                panel.refreshForTest()
                panel.backgroundListForTest() to panel.pathListForTest()
            }
            assertTrue(result.first.any { it.contains("floor.png") }, "background image listed: ${result.first}")
            assertEquals(1, c.layout.value!!.background.count { it.imageRef != null }, "image stored in layout")
            if (stations.size >= 2) {
                assertTrue(result.second.contains("route"), "path listed")
                assertEquals(2, c.layout.value!!.paths.first { it.name == "route" }.points.size, "path has the 2 station points")
            }
        } finally { c.close() }
    }
}
