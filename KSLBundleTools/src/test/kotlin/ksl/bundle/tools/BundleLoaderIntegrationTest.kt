package ksl.bundle.tools

import ksl.app.bundle.BundleLayout
import ksl.app.bundle.BundleLoader
import ksl.app.bundle.IncompleteBundleException
import ksl.bundle.tools.support.StubModelBuilder
import ksl.bundle.tools.support.TestBundleBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end checks for the runtime descriptor contract in
 * `ksl.app.bundle.LoadedBundle.descriptorFor`: a loadable bundle must embed its
 * descriptors (be fully assembled). A complete bundle resolves from its in-JAR
 * descriptor; an incomplete one is rejected for consumption and throws when a
 * descriptor is requested.
 */
class BundleLoaderIntegrationTest {

    private fun assemble(dir: Path): Path {
        val builders = TestBundleBuilder.buildWithoutServicesFile(dir, "builders", listOf(StubModelBuilder::class.java))
        val assembled = dir.resolve("builders-bundle.jar")
        val result = AssembleCommand.run(
            listOf(builders.toString(), "--id", "edu.test.stub", "-o", assembled.toString()),
            out = System.out, err = System.err,
        )
        assertEquals(CommandResult.Success, result, "assemble must succeed for this test")
        return assembled
    }

    @Test
    fun `a complete assembled bundle resolves from its in-JAR descriptor and loads for consumption`(@TempDir dir: Path) {
        val assembled = assemble(dir)

        BundleLoader.loadJar(assembled).single().use { loaded ->
            assertTrue(loaded.missingDescriptors().isEmpty(), "a complete bundle has no missing descriptors")
            assertEquals("Stub", loaded.descriptorFor("Stub").modelIdentifier)
        }

        val outcome = BundleLoader.loadForConsumption(assembled)
        try {
            assertEquals(1, outcome.loaded.size, "a complete bundle loads for consumption")
            assertTrue(outcome.rejected.isEmpty(), "a complete bundle is not rejected")
        } finally {
            outcome.loaded.forEach { it.close() }
        }
    }

    @Test
    fun `an incomplete bundle (no embedded descriptor) is rejected for consumption and throws on descriptorFor`(@TempDir dir: Path) {
        val assembled = assemble(dir)
        val plain = TestBundleBuilder.stripDescriptors(assembled)

        // Precondition: the stripped JAR embeds no descriptor for the model.
        val hasInJar = java.util.jar.JarFile(plain.toFile()).use { jf ->
            jf.getJarEntry(BundleLayout.descriptorPath("Stub")) != null
        }
        assertEquals(false, hasInJar, "test precondition: this JAR must not embed the descriptor")

        // The lenient primitive still loads it (so inspect/tooling can report on it),
        // but it reports the gap and refuses to produce a descriptor.
        BundleLoader.loadJar(plain).single().use { loaded ->
            assertEquals(listOf("Stub"), loaded.missingDescriptors())
            assertFailsWith<IncompleteBundleException> { loaded.descriptorFor("Stub") }
        }

        // The consumption gate rejects it outright (and closes its classloader).
        val outcome = BundleLoader.loadForConsumption(plain)
        assertTrue(outcome.loaded.isEmpty(), "an incomplete bundle must not load for consumption")
        assertEquals(1, outcome.rejected.size, "the incomplete bundle is rejected")
        assertTrue(
            outcome.rejected.single().reason.contains("incomplete", ignoreCase = true),
            "rejection reason should explain incompleteness: ${outcome.rejected.single().reason}",
        )
    }
}
