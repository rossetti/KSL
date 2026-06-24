package ksl.bundle.tools

import ksl.app.bundle.BundleDescriptorCache
import ksl.app.bundle.BundleLayout
import ksl.app.bundle.BundleLoader
import ksl.bundle.tools.support.StubBundle
import ksl.bundle.tools.support.StubModelBuilder
import ksl.bundle.tools.support.TestBundleBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end checks that an assembled bundle's embedded descriptor interoperates
 * correctly with the runtime three-tier descriptor resolution in
 * `ksl.app.bundle.LoadedBundle.descriptorFor`.
 *
 * The resolution priority (per the substrate design):
 *   1. In-JAR resource at `BundleLayout.descriptorPath(modelId)`
 *   2. On-disk cache at `~/.ksl/bundle-cache/<jarSha256>/<modelId>.json`
 *   3. Lazy extraction: build the model and call `Model.modelDescriptor()`,
 *      then write the result back to the cache.
 *
 * These tests use a `BundleDescriptorCache` rooted at a per-test temp
 * directory so the global `~/.ksl/` cache is not perturbed, and so the
 * cache directory's emptiness or contents can be directly observed.
 */
class BundleLoaderIntegrationTest {

    @Test
    fun `an assembled bundle is resolved from the in-JAR descriptor and the cache is never written`(@TempDir dir: Path) {
        // A plain builders JAR assembled into a bundle JAR that carries
        // META-INF/ksl/models/Stub/descriptor.json.
        val builders = TestBundleBuilder.buildWithoutServicesFile(dir, "builders", listOf(StubModelBuilder::class.java))
        val assembled = dir.resolve("builders-bundle.jar")
        val assembleResult = AssembleCommand.run(
            listOf(builders.toString(), "--id", "edu.test.stub", "-o", assembled.toString()),
            out = System.out,
            err = System.err
        )
        assertEquals(CommandResult.Success, assembleResult, "assemble must succeed for this test")

        // Custom cache root that we can introspect.
        val cacheRoot = dir.resolve("bundle-cache")
        val cache = BundleDescriptorCache(rootDir = cacheRoot)

        BundleLoader.loadJar(assembled, cache = cache).single().use { loaded ->
            val descriptor = loaded.descriptorFor("Stub")
            assertEquals("Stub", descriptor.modelIdentifier)
        }

        // Tier 1 should have served the request, so tiers 2/3 never ran.
        // Tier 3 would have written into the cache; an empty (or absent)
        // cache root proves it did not.
        if (Files.exists(cacheRoot)) {
            val written = Files.walk(cacheRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) }.count()
            }
            assertEquals(
                0L, written,
                "cache must remain empty when the in-JAR path serves the descriptor; " +
                        "found files under $cacheRoot"
            )
        }
    }

    @Test
    fun `a bundle with no embedded descriptor triggers lazy extraction and writes the cache entry`(@TempDir dir: Path) {
        val plain = TestBundleBuilder.build(dir, "stub", listOf(StubBundle::class.java))

        val cacheRoot = dir.resolve("bundle-cache")
        val cache = BundleDescriptorCache(rootDir = cacheRoot)

        // Sanity: the source JAR carries no in-JAR descriptor for the stub model.
        val descriptorPath = BundleLayout.descriptorPath("stub")
        val hasInJar = java.util.jar.JarFile(plain.toFile()).use { jf ->
            jf.getJarEntry(descriptorPath) != null
        }
        assertEquals(false, hasInJar, "test precondition: this JAR must not embed the descriptor")

        BundleLoader.loadJar(plain, cache = cache).single().use { loaded ->
            val descriptor = loaded.descriptorFor("stub")
            assertEquals("stub", descriptor.modelIdentifier)
        }

        // Tier 3 fired, so the cache entry exists.
        assertTrue(Files.isDirectory(cacheRoot), "cache root must be created on first miss")
        val cachedDescriptors = Files.walk(cacheRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString() == "stub.json" }
                .toList()
        }
        assertEquals(
            1, cachedDescriptors.size,
            "expected exactly one cached descriptor file at <root>/<sha>/stub.json; found ${cachedDescriptors.size}"
        )
        val meta = Files.walk(cacheRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString() == "meta.json" }
                .toList()
        }
        assertEquals(1, meta.size, "expected exactly one meta.json alongside the descriptor")
    }
}
