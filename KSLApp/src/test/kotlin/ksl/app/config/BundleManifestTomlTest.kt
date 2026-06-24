package ksl.app.config

import ksl.app.bundle.KSLAppKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Round-trip and tidiness tests for the [BundleManifestToml] codec. */
class BundleManifestTomlTest {

    private val full = BundleManifest(
        bundleId = "edu.uark.examples.mm1",
        displayName = "M/M/1 Queue Example",
        description = "Single-server M/M/1 queue.",
        version = "1.0.0",
        kslApiVersion = "1.2",
        author = "M. Rossetti",
        homepage = "https://rossetti.github.io/KSLBook/",
        license = "MIT",
        tags = setOf("queueing", "textbook"),
        models = listOf(
            ModelManifestEntry(
                modelId = "MM1",
                builderClass = "ksl.examples.mm1.MM1Builder",
                displayName = "M/M/1 Queue",
                description = "Single-server queue.",
                supportedApps = setOf(KSLAppKind.SINGLE, KSLAppKind.EXPERIMENT),
            ),
        ),
    )

    @Test
    fun `round trips through TOML`() {
        assertEquals(full, BundleManifestToml.decode(BundleManifestToml.encode(full)))
    }

    @Test
    fun `minimal manifest omits unset optional fields and round trips`() {
        val minimal = BundleManifest(
            bundleId = "x.y.z",
            displayName = "Z",
            description = "",
            version = "0.1",
            kslApiVersion = "1.2",
            models = listOf(
                ModelManifestEntry(modelId = "m", builderClass = "p.B", displayName = "M"),
            ),
        )
        val text = BundleManifestToml.encode(minimal)
        // Assert on the TOML key form ("author = ..."), not the bare word.
        assertFalse(text.contains("author ="), "explicitNulls=false should omit unset author")
        assertFalse(text.contains("homepage ="), "explicitNulls=false should omit unset homepage")
        assertFalse(text.contains("license ="), "explicitNulls=false should omit unset license")
        assertEquals(minimal, BundleManifestToml.decode(text))
    }

    @Test
    fun `enums serialize by name`() {
        val text = BundleManifestToml.encode(full)
        assertTrue(text.contains("SINGLE"), "KSLAppKind should serialize as its name")
    }
}
