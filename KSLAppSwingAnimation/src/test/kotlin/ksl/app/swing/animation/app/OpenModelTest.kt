package ksl.app.swing.animation.app

import ksl.app.config.ModelReference
import ksl.app.editor.BundleLibraryController
import ksl.examples.general.appsupport.ManifestBundleFixtures
import ksl.examples.general.appsupport.MM1ModelBuilder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies 10.2: AnimationAppController.fromBundle re-probes a chosen bundle model (the model-switch core). */
class OpenModelTest {

    @Test
    fun `fromBundle builds a controller that probes the chosen model`(@TempDir dir: Path) {
        // KSL discovers bundles from a directory (the workspace bundles dir in production), not the
        // classpath; assemble a manifest bundle for the MM1 fixture into a temp dir and discover it
        // the same way an app would.
        ManifestBundleFixtures.assembleManifestBundle(dir, "mm1", "ksl.examples.mm1", MM1ModelBuilder::class.java)
        val lib = BundleLibraryController()
        lib.discoverFromDirectories(dir)
        try {
            assertNotNull(lib.bundleProvider.value, "expected a bundle provider from the discovered bundle")
            AnimationAppController.fromBundle("AnimTest", lib, "ksl.examples.mm1", "MM1").use { c ->
                assertNull(c.probeFailure, "probe should succeed for MM1: ${c.probeFailure}")
                assertTrue(c.modelName.isNotBlank(), "the chosen model should be probed and named")
                assertEquals(
                    ModelReference.ByBundleAndModelId("ksl.examples.mm1", "MM1"),
                    c.sourceRef,
                    "the source reference should point at the chosen bundle model"
                )
            }
        } finally {
            lib.close()
        }
    }

    @Test
    fun `fromBundle on an empty library fails fast`() {
        val lib = BundleLibraryController()  // no discovery → no provider
        try {
            assertFailsWith<IllegalStateException> {
                AnimationAppController.fromBundle("AnimTest", lib, "x", "y")
            }
        } finally {
            lib.close()
        }
    }
}
