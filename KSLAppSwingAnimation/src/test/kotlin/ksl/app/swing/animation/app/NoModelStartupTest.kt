package ksl.app.swing.animation.app

import ksl.app.editor.BundleLibraryController
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The no-model startup state (mirrors the Experiment app): bundle mode discovers bundles so they are
 * *pickable*, but selects no model until the user opens one. The controller must therefore open clean —
 * empty inventory, no probe failure — with run/scaffold inert, while still carrying the bundle library so
 * the frame can offer Open Model / Load JAR.
 */
class NoModelStartupTest {

    private lateinit var controller: AnimationAppController

    @AfterTest
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    @Test
    fun `forNoModel opens an empty, inert controller that still carries the bundle library`() {
        // KSL discovers bundles from ~/.ksl/bundles/ (no classpath SPI); a fresh library has none, which is
        // exactly the no-model startup case — the controller must still open clean and carry the library.
        val lib = BundleLibraryController()
        controller = AnimationAppController.forNoModel("TestAnimationApp", lib)

        assertFalse(controller.hasModel, "no model is selected at startup")
        assertNull(controller.probeFailure, "the empty no-model probe must not fail")
        assertSame(lib, controller.bundleLibrary, "the bundle library is retained so the user can pick a model")

        // Nothing animatable, and no source identity to switch from.
        val inv = controller.inventory
        assertTrue(
            inv.queues.isEmpty() && inv.resources.isEmpty() && inv.responses.isEmpty() && inv.counters.isEmpty(),
            "no-model inventory is empty"
        )
        assertNull(controller.bundleId)
        assertNull(controller.modelId)

        // Run and scaffold are inert (the frame also disables them, this is the controller-side guard).
        controller.submit()
        assertFalse(controller.runningFlow.value, "submit is a no-op with no model")
        controller.scaffoldLayout()
        assertNull(controller.layout.value, "scaffold is a no-op with no model")
    }
}
