package ksl.app.swing.animation.app

import ksl.animation.CaptureMode
import ksl.animation.ElementKind
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies 9D.2: the Capture tab is a faithful, headless-constructible view over the controller's capture
 * mutators. A `JPanel` (unlike the frame) needs no display, so the wiring is exercised directly: drive a
 * control, assert the controller's `captureSpec`/`captureValidation()` react. Interactions run on the EDT
 * (as in the real app) so they serialize with the panel's own captureSpec subscription.
 */
class CapturePanelTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model = Model("TRCap").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun controller() = AnimationAppController("TRCap", builder)

    /** Runs [block] on the EDT and returns its result, surfacing any assertion/exception unwrapped. */
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result.getOrThrow()
    }

    @Test
    fun `pick-lists carry the model inventory names`() {
        val controller = controller()
        try {
            val shown = onEdt {
                CapturePanel(controller).namesShownForTest(ElementKind.RESOURCE)
            }
            assertEquals(controller.inventory.namesOf(ElementKind.RESOURCE), shown)
            assertContains(shown, "Test1")
        } finally {
            controller.close()
        }
    }

    @Test
    fun `mode toggle routes to the controller`() {
        val controller = controller()
        try {
            val modes = onEdt {
                val panel = CapturePanel(controller)
                val initial = controller.captureSpec.value.mode
                panel.selectModeForTest(CaptureMode.SELECTED)
                val selected = controller.captureSpec.value.mode
                panel.selectModeForTest(CaptureMode.ALL)
                Triple(initial, selected, controller.captureSpec.value.mode)
            }
            assertEquals(CaptureMode.ALL, modes.first)
            assertEquals(CaptureMode.SELECTED, modes.second)
            assertEquals(CaptureMode.ALL, modes.third)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `include and exclude states mutate the capture spec and show in the cell`() {
        val controller = controller()
        try {
            val included = onEdt {
                val panel = CapturePanel(controller)
                panel.includeForTest(ElementKind.RESOURCE, "Test1")
                controller.captureSpec.value.include.any { it.kind == ElementKind.RESOURCE && it.name == "Test1" } to
                    panel.stateForTest(ElementKind.RESOURCE, "Test1")
            }
            assertTrue(included.first, "include routes to the controller")
            assertEquals("Include", included.second)

            val excluded = onEdt {
                val panel = CapturePanel(controller)
                panel.excludeForTest(ElementKind.RESOURCE, "Test1")
                controller.captureSpec.value.exclude.any { it.kind == ElementKind.RESOURCE && it.name == "Test1" } to
                    panel.stateForTest(ElementKind.RESOURCE, "Test1")
            }
            assertTrue(excluded.first, "exclude routes to the controller")
            assertEquals("Exclude", excluded.second, "exclude state shown")
            // Exclude replaced include (mutually exclusive), so the include set no longer holds Test1.
            assertTrue(controller.captureSpec.value.include.none { it.name == "Test1" }, "exclude clears include")
        } finally {
            controller.close()
        }
    }

    @Test
    fun `responses are split into tally and time-weighted tabs`() {
        val controller = controller()
        try {
            onEdt {
                val panel = CapturePanel(controller)
                // RESPONSE appears twice (tally + time-weighted) when the model has both kinds.
                val responseTabs = panel.tabNamesForTest().count { it.startsWith("${ElementKind.RESPONSE}:") }
                assertTrue(responseTabs >= 2, "expected separate Responses and Time-Weighted Responses tabs")
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun `capture window controls route to the controller`() {
        val controller = controller()
        try {
            val window = onEdt {
                val panel = CapturePanel(controller)
                panel.setWindowForTest(10.0, 50.0)
                controller.captureSpec.value.captureWindow
            }
            assertEquals(10.0, window?.startTime)
            assertEquals(50.0, window?.endTime)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `validation strip reflects an unmatched selector`() {
        val controller = controller()
        try {
            val texts = onEdt {
                val panel = CapturePanel(controller)
                val clean = panel.validationTextForTest()
                // An include for a name not in the inventory is an unmatched selector; drive it through a
                // wired control so the strip recomputes.
                controller.addInclude(ElementKind.RESOURCE, "DoesNotExist")
                panel.selectModeForTest(CaptureMode.SELECTED)
                clean to panel.validationTextForTest()
            }
            assertTrue(texts.first.startsWith("✓"), "clean spec validates")
            assertFalse(controller.captureValidation().isValid)
            assertContains(texts.second, "DoesNotExist")
        } finally {
            controller.close()
        }
    }
}
