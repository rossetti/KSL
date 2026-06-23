package ksl.app.config

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the [ModelReference.ByBundleAndModelId] variant added in
 * the substrate-prep commit.
 */
class ByBundleAndModelIdTest {

    @Test
    fun `non-blank bundleId and modelId is OK`() {
        val ref = ModelReference.ByBundleAndModelId(
            bundleId = "edu.example.queueing",
            modelId = "mm1"
        )
        assertEquals("edu.example.queueing", ref.bundleId)
        assertEquals("mm1", ref.modelId)
    }

    @Test
    fun `blank bundleId is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ModelReference.ByBundleAndModelId(bundleId = "", modelId = "mm1")
        }
    }

    @Test
    fun `blank modelId is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ModelReference.ByBundleAndModelId(bundleId = "edu.example.queueing", modelId = "")
        }
    }

    @Test
    fun `JSON round-trip via sealed serializer carries the type discriminator`() {
        val original: ModelReference = ModelReference.ByBundleAndModelId(
            bundleId = "edu.example.queueing",
            modelId = "mm1"
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(ModelReference.serializer(), original)
        assertTrue(
            "\"type\":\"byBundleAndModelId\"" in encoded || "\"type\": \"byBundleAndModelId\"" in encoded,
            "encoded should carry the byBundleAndModelId discriminator: $encoded"
        )
        val decoded = json.decodeFromString(ModelReference.serializer(), encoded)
        assertIs<ModelReference.ByBundleAndModelId>(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `existing variants still round-trip after the new variant was added`() {
        // Sanity check that ByBundleAndModelId is purely additive — existing
        // sealed-class variants still serialize correctly.
        val json = Json { encodeDefaults = true }

        val providerRef: ModelReference = ModelReference.ByProviderId("MM1")
        val providerEncoded = json.encodeToString(ModelReference.serializer(), providerRef)
        val providerDecoded = json.decodeFromString(ModelReference.serializer(), providerEncoded)
        assertEquals(providerRef, providerDecoded)

        val jarRef: ModelReference = ModelReference.ByJar("/path/to/foo.jar", "com.example.Builder")
        val jarEncoded = json.encodeToString(ModelReference.serializer(), jarRef)
        val jarDecoded = json.decodeFromString(ModelReference.serializer(), jarEncoded)
        assertEquals(jarRef, jarDecoded)
    }
}
