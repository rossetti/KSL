package ksl.app.bundle

import ksl.simulation.ModelCatalog
import ksl.simulation.NominatedOutput
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 pipeline test: discover builders in a plain builders JAR, assemble a
 * bundle JAR from authored content, and load it back through [BundleLoader].
 */
class BundleAssemblerTest {

    @TempDir
    lateinit var tmp: Path

    /** A builders JAR: the fixture builder classes only — no manifest, no services. */
    private fun buildersJar(name: String): Path {
        val target = tmp.resolve(name)
        JarOutputStream(Files.newOutputStream(target), Manifest()).use { jar ->
            for (cls in listOf(Phase1TestBuilder::class.java, Phase1ObjectBuilder::class.java)) {
                val entry = cls.name.replace('.', '/') + ".class"
                jar.putNextEntry(JarEntry(entry).apply { time = 0L })
                cls.classLoader.getResourceAsStream(entry)!!.use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return target
    }

    private fun jarEntryText(jar: Path, entry: String): String? =
        JarFile(jar.toFile()).use { jf ->
            jf.getJarEntry(entry)?.let { jf.getInputStream(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } }
        }

    @Test
    fun `discovers builders, assembles a bundle JAR, and loads it back`() {
        val input = buildersJar("builders.jar")

        val discovered = BuilderDiscovery.discover(input)
        assertEquals(2, discovered.size, "expected both fixture builders")
        assertTrue(discovered.all { it.isOk }, "all builders should build: ${discovered.map { it.builderClass to it.error }}")

        val testBuilder = discovered.first { it.builderClass == Phase1TestBuilder::class.java.name }
        val spec = BundleAssembler.BundleSpec(
            bundleId = "test.assembled",
            displayName = "Assembled",
            description = "",
            version = "1.0.0",
            kslApiVersion = "1.2",
            models = listOf(
                BundleAssembler.ModelSpec(
                    modelId = "mm1",
                    builderClass = testBuilder.builderClass,
                    displayName = "MM1",
                    supportedApps = setOf(KSLAppKind.SINGLE),
                    descriptor = testBuilder.descriptor!!,
                    catalog = ModelCatalog(
                        nominatedOutputs = listOf(NominatedOutput(name = "throughput", displayName = "Throughput")),
                    ),
                    recipes = listOf(
                        BundleAssembler.RecipeContent("light", ConfigRecipeKind.RUN, "# light recipe\n".toByteArray()),
                    ),
                ),
            ),
        )
        val output = tmp.resolve("out-bundle.jar")
        BundleAssembler.assemble(input, output, spec)

        // The input builders JAR is never modified (still has no manifest entry).
        assertNull(jarEntryText(input, BundleLayout.BUNDLE_TOML), "input JAR must remain untouched")

        BundleLoader.loadJar(output).also { assertEquals(1, it.size) }.first().use { lb ->
            assertEquals("test.assembled", lb.bundle.bundleId)
            assertEquals(listOf("mm1"), lb.bundle.models.map { it.modelId })

            val descriptor = lb.descriptorFor("mm1")
            assertTrue("throughput" in descriptor.responseNames)
            // catalog.toml overlay applied at load:
            assertNotNull(descriptor.catalog)
            assertTrue(descriptor.catalog!!.nominatedOutputs.any { it.name == "throughput" })

            val recipes = lb.bundle.recipesFor("mm1")
            assertEquals(1, recipes.size)
            assertEquals("light", recipes.single().name)
            assertEquals(ConfigRecipeKind.RUN, recipes.single().kind)
            assertTrue(recipes.single().openStream().use { it.readBytes().toString(Charsets.UTF_8) }.contains("light recipe"))
        }
    }

    @Test
    fun `refuses to write to the input path`() {
        val input = buildersJar("b.jar")
        val spec = BundleAssembler.BundleSpec("a.b", "A", "", "1.0", "1.2", models = emptyList())
        assertFailsWith<IllegalArgumentException> { BundleAssembler.assemble(input, input, spec) }
    }

    @Test
    fun `refuses to overwrite an existing output unless forced`() {
        val input = buildersJar("b2.jar")
        val out = tmp.resolve("exists-bundle.jar")
        Files.writeString(out, "placeholder")
        val spec = BundleAssembler.BundleSpec("a.b", "A", "", "1.0", "1.2", models = emptyList())

        assertFailsWith<java.nio.file.FileAlreadyExistsException> { BundleAssembler.assemble(input, out, spec) }
        BundleAssembler.assemble(input, out, spec, force = true)
        assertNotNull(jarEntryText(out, BundleLayout.BUNDLE_TOML), "forced assemble should write the manifest")
    }
}
