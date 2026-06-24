package ksl.examples.general.appsupport

import ksl.app.bundle.BundleLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the KSLTestModels bundles assemble + load through the manifest mechanism
 * from their extracted named builders — the unblock for retiring the in-process
 * ServiceLoader bundles. Each model is built once during descriptor resolution, so a
 * successful `descriptorFor(...)` proves the manifest path is end-to-end usable.
 */
class ManifestBundleAssemblyTest {

    @Test
    fun `MM1 assembles and loads as a manifest bundle`(@TempDir dir: Path) {
        val jar = ManifestBundleFixtures.assembleManifestBundle(
            dir, "mm1", "ksl.examples.mm1", MM1ModelBuilder::class.java
        )
        BundleLoader.loadJar(jar).single().use { lb ->
            assertEquals("ksl.examples.mm1", lb.bundle.bundleId)
            assertEquals(listOf("MM1"), lb.bundle.models.map { it.modelId })
            assertTrue(lb.descriptorFor("MM1").responseNames.isNotEmpty(), "descriptor should resolve with responses")
        }
    }

    @Test
    fun `LKInventory assembles and loads as a manifest bundle`(@TempDir dir: Path) {
        val jar = ManifestBundleFixtures.assembleManifestBundle(
            dir, "lk", "ksl.examples.lk-inventory", LKInventoryModelBuilder::class.java
        )
        BundleLoader.loadJar(jar).single().use { lb ->
            assertEquals("ksl.examples.lk-inventory", lb.bundle.bundleId)
            assertEquals(listOf("LKInventory"), lb.bundle.models.map { it.modelId })
            assertTrue(lb.descriptorFor("LKInventory").responseNames.isNotEmpty())
        }
    }

    @Test
    fun `SimOpt test models assemble as a two-model manifest bundle`(@TempDir dir: Path) {
        val jar = ManifestBundleFixtures.assembleManifestBundle(
            dir, "simopt", "ksl.examples.simopt-test-models",
            LKInventoryOptModelBuilder::class.java, RQInventoryOptModelBuilder::class.java,
        )
        BundleLoader.loadJar(jar).single().use { lb ->
            assertEquals("ksl.examples.simopt-test-models", lb.bundle.bundleId)
            assertEquals(
                setOf("LKInventoryOpt", "RQInventoryOpt"),
                lb.bundle.models.map { it.modelId }.toSet()
            )
            assertTrue(lb.descriptorFor("LKInventoryOpt").responseNames.isNotEmpty())
            assertTrue(lb.descriptorFor("RQInventoryOpt").responseNames.isNotEmpty())
        }
    }
}
