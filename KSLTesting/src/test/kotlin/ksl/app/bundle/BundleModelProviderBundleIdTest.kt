package ksl.app.bundle

import ksl.examples.general.appsupport.LKInventoryBundle
import ksl.examples.general.appsupport.MM1Bundle
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the `(bundleId, modelId)`-qualified lookups on
 * [BundleModelProvider] added in the substrate-prep commit.  Uses the
 * real example bundles registered by `KSLExamples` on the test
 * classpath via `ServiceLoader`.
 */
class BundleModelProviderBundleIdTest {

    @Test
    fun `isModelProvided by bundleId-modelId finds models in classpath bundles`() {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            assertTrue(bundles.isNotEmpty(), "expected at least one classpath bundle")
            val provider = BundleModelProvider(bundles)

            assertTrue(provider.isModelProvided("ksl.examples.mm1", MM1Bundle.MODEL_ID))
            assertTrue(provider.isModelProvided("ksl.examples.lk-inventory", LKInventoryBundle.MODEL_ID))
            assertEquals(false, provider.isModelProvided("ksl.examples.mm1", "no-such-model"))
            assertEquals(false, provider.isModelProvided("no.such.bundle", MM1Bundle.MODEL_ID))
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `provideModel by bundleId-modelId builds a Model from the right bundle`() {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            val provider = BundleModelProvider(bundles)
            val mm1Model = provider.provideModel("ksl.examples.mm1", MM1Bundle.MODEL_ID)
            assertEquals(MM1Bundle.MODEL_ID, mm1Model.name)
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `builderFor by bundleId-modelId returns a usable ModelBuilderIfc`() {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            val provider = BundleModelProvider(bundles)
            val builder = provider.builderFor("ksl.examples.mm1", MM1Bundle.MODEL_ID)
            assertNotNull(builder)
            val model = builder.build(null, null)
            assertEquals(MM1Bundle.MODEL_ID, model.name)
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `unknown bundleId throws IllegalArgumentException`() {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            val provider = BundleModelProvider(bundles)
            assertFailsWith<IllegalArgumentException> {
                provider.provideModel("no.such.bundle", MM1Bundle.MODEL_ID)
            }
            assertFailsWith<IllegalArgumentException> {
                provider.builderFor("no.such.bundle", MM1Bundle.MODEL_ID)
            }
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `unknown modelId within a known bundle throws IllegalArgumentException`() {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            val provider = BundleModelProvider(bundles)
            assertFailsWith<IllegalArgumentException> {
                provider.provideModel("ksl.examples.mm1", "no-such-model")
            }
            assertFailsWith<IllegalArgumentException> {
                provider.builderFor("ksl.examples.mm1", "no-such-model")
            }
        } finally {
            bundles.forEach { it.close() }
        }
    }

    @Test
    fun `single-string lookups still work after the new variant was added`() {
        // Sanity check that the new (bundleId, modelId) maps are purely
        // additive — the flat first-wins shadowing path is intact.
        val bundles = BundleLoader.loadFromClasspath()
        try {
            val provider = BundleModelProvider(bundles)
            assertTrue(provider.isModelProvided(MM1Bundle.MODEL_ID))
            val model = provider.provideModel(MM1Bundle.MODEL_ID)
            assertEquals(MM1Bundle.MODEL_ID, model.name)
        } finally {
            bundles.forEach { it.close() }
        }
    }
}
