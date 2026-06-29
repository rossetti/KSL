package ksl.app.swing.animation.app

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Response
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * 9C.2 frame + entry-point checks. The frame assembly requires a display, so the construction smoke
 * test is **skipped when headless** (e.g. this CI) and runs on a developer machine with a display; the
 * entry-point builder-registration checks are display-free.
 */
class AnimationAppFrameTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val m = Model("FrameTestModel", autoCSVReports = false)
            ResourceWithQ(m, "Worker")
            Response(m, "SystemTime")
            return m
        }
    }

    @Test
    fun `the frame assembles with the four tabs and a menu bar`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "frame construction needs a display")
        val controller = AnimationAppController("FrameApp", builder)
        try {
            val frame = AnimationAppFrame(controller)
            // Tab titles may carry a guided-workflow "✓" marker (9F.5) for completed stages; compare the base names.
            assertEquals(
                listOf("Capture", "Run", "Layout", "Replay"),
                frame.tabTitlesForTest().map { it.removeSuffix(" ✓") }
            )
            assertNotNull(frame.jMenuBar)
            assertEquals(4, frame.jMenuBar.menuCount, "File, Bundles, Layout, View")
            frame.dispose()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `the entry point records a single model builder`() {
        val app = KSLAnimationApp("EntryApp")
        app.modelBuilder(builder)
        assertSame(builder, app.registeredBuilderForTest())
        assertFailsWith<IllegalStateException> { app.modelBuilder(builder) }
    }
}
