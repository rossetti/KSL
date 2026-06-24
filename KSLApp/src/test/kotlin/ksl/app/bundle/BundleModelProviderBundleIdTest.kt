package ksl.app.bundle

import ksl.examples.general.appsupport.LKInventoryModelBuilder
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the `(bundleId, modelId)`-qualified lookups on [BundleModelProvider].
 * The example models are loaded as **assembled manifest bundle JARs** (the same way an
 * app loads a bundle), not via classpath ServiceLoader discovery.
 */
class BundleModelProviderBundleIdTest {

    private val mm1ModelId = "MM1"
    private val lkModelId = "LKInventory"

    /** Assembles the MM1 and LK models as manifest bundles and loads them. */
    private fun loadBundles(dir: Path): List<LoadedBundle> {
        val mm1 = ManifestBundleFixtures.assembleManifestBundle(
            dir, "mm1", "ksl.examples.mm1", MM1ModelBuilder::class.java
        )
        val lk = ManifestBundleFixtures.assembleManifestBundle(
            dir, "lk", "ksl.examples.lk-inventory", LKInventoryModelBuilder::class.java
        )
        return BundleLoader.loadJar(mm1) + BundleLoader.loadJar(lk)
    }

    @Test
    fun `isModelProvided by bundleId-modelId finds models`(@TempDir dir: Path) {
        val bundles = loadBundles(dir)
        try {
            val provider = BundleModelProvider(bundles)
            assertTrue(provider.isModelProvided("ksl.examples.mm1", mm1ModelId))
            assertTrue(provider.isModelProvided("ksl.examples.lk-inventory", lkModelId))
            assertEquals(false, provider.isModelProvided("ksl.examples.mm1", "no-such-model"))
            assertEquals(false, provider.isModelProvided("no.such.bundle", mm1ModelId))
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `provideModel by bundleId-modelId builds a Model from the right bundle`(@TempDir dir: Path) {
        val bundles = loadBundles(dir)
        try {
            val provider = BundleModelProvider(bundles)
            val mm1Model = provider.provideModel("ksl.examples.mm1", mm1ModelId)
            assertEquals(mm1ModelId, mm1Model.name)
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `builderFor by bundleId-modelId returns a usable ModelBuilderIfc`(@TempDir dir: Path) {
        val bundles = loadBundles(dir)
        try {
            val provider = BundleModelProvider(bundles)
            val builder = provider.builderFor("ksl.examples.mm1", mm1ModelId)
            assertNotNull(builder)
            val model = builder.build(null, null)
            assertEquals(mm1ModelId, model.name)
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `unknown bundleId throws IllegalArgumentException`(@TempDir dir: Path) {
        val bundles = loadBundles(dir)
        try {
            val provider = BundleModelProvider(bundles)
            assertFailsWith<IllegalArgumentException> { provider.provideModel("no.such.bundle", mm1ModelId) }
            assertFailsWith<IllegalArgumentException> { provider.builderFor("no.such.bundle", mm1ModelId) }
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `unknown modelId within a known bundle throws IllegalArgumentException`(@TempDir dir: Path) {
        val bundles = loadBundles(dir)
        try {
            val provider = BundleModelProvider(bundles)
            assertFailsWith<IllegalArgumentException> { provider.provideModel("ksl.examples.mm1", "no-such-model") }
            assertFailsWith<IllegalArgumentException> { provider.builderFor("ksl.examples.mm1", "no-such-model") }
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `single-string lookups still work`(@TempDir dir: Path) {
        // The single-string (modelId-only) lookups are purely additive over the
        // (bundleId, modelId) variant — the flat first-wins shadowing path is intact.
        val bundles = loadBundles(dir)
        try {
            val provider = BundleModelProvider(bundles)
            assertTrue(provider.isModelProvided(mm1ModelId))
            val model = provider.provideModel(mm1ModelId)
            assertEquals(mm1ModelId, model.name)
        } finally {
            bundles.forEach { it.close() }
        }
    }
}
