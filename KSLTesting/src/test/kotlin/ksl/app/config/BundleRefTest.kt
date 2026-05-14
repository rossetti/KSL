package ksl.app.config

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BundleRefTest {

    @Test
    fun `construct with non-blank bundleId and paths is OK`() {
        val ref = BundleRef(
            paths = listOf("./bundles/foo.jar", "~/.ksl/bundles/foo.jar"),
            bundleId = "edu.example.foo"
        )
        assertEquals("edu.example.foo", ref.bundleId)
        assertEquals(listOf("./bundles/foo.jar", "~/.ksl/bundles/foo.jar"), ref.paths)
    }

    @Test
    fun `empty paths list is allowed (bundleId is authoritative)`() {
        val ref = BundleRef(paths = emptyList(), bundleId = "edu.example.foo")
        assertEquals(0, ref.paths.size)
    }

    @Test
    fun `blank bundleId is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BundleRef(paths = listOf("/abs/foo.jar"), bundleId = "")
        }
        assertFailsWith<IllegalArgumentException> {
            BundleRef(paths = listOf("/abs/foo.jar"), bundleId = "   ")
        }
    }

    @Test
    fun `blank path entry is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BundleRef(paths = listOf("/ok/foo.jar", ""), bundleId = "edu.example.foo")
        }
    }

    @Test
    fun `JSON round-trip preserves shape`() {
        val original = BundleRef(
            paths = listOf("./foo.jar", "/abs/foo.jar"),
            bundleId = "edu.example.foo"
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(BundleRef.serializer(), original)
        val decoded = json.decodeFromString(BundleRef.serializer(), encoded)
        assertEquals(original, decoded)
    }
}
