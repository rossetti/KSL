package ksl.examples.general.bookbundle

import ksl.app.bundle.BundleLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the 16 book-example models assemble + load through the manifest mechanism
 * from their extracted named builders — the unblock for eventually retiring the
 * `BookExamplesBundle` ServiceLoader registration. Each model's descriptor is extracted
 * by building it once during assembly, so a clean load is end-to-end proof.
 */
class BookExamplesManifestAssemblyTest {

    @Test
    fun `the 16 book builders assemble and load as one manifest bundle`(@TempDir dir: Path) {
        val bundle = BookBundleFixture.assemble(dir)
        BundleLoader.loadJar(bundle).single().use { lb ->
            assertEquals("ksl.examples.book", lb.bundle.bundleId)
            assertEquals(16, lb.bundle.models.size, "all 16 book models should assemble")
            // Spot-check a few descriptors resolve (each model is built once to extract it).
            for (id in listOf("TandemQueue", "RQInventorySystem", "TwoEchelonInventory")) {
                assertTrue(lb.descriptorFor(id).responseNames.isNotEmpty(), "descriptor for $id should resolve")
            }
        }
    }
}
