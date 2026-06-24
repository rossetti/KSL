package ksl.app.bundle

import ksl.app.config.BundleManifest
import ksl.app.config.BundleManifestToml
import ksl.app.config.ModelManifestEntry
import ksl.app.config.RecipeEntry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 loader tests: a JAR that contains only builder classes plus a
 * `bundle.toml` (no `META-INF/services` registration, no compiled
 * `KSLModelBundle`) is fully loadable and usable through [BundleLoader].
 */
class ManifestBackedBundleTest {

    @TempDir
    lateinit var tmp: Path

    private val recipePath = "META-INF/ksl/models/p1-model/run/light.toml"

    private fun manifest() = BundleManifest(
        bundleId = "test.bundle.p1",
        displayName = "Phase 1 Test Bundle",
        description = "A builders-only JAR with a manifest.",
        version = "1.0.0",
        kslApiVersion = "1.2",
        models = listOf(
            ModelManifestEntry(
                modelId = "p1-model",
                builderClass = Phase1TestBuilder::class.java.name,
                displayName = "Phase 1 Model",
                description = "Minimal model.",
                supportedApps = setOf(KSLAppKind.SINGLE, KSLAppKind.EXPERIMENT),
                recipes = listOf(RecipeEntry("light", ConfigRecipeKind.RUN, recipePath)),
            ),
        ),
    )

    /** Writes a builders JAR: the fixture class file(s), the manifest, and a recipe. */
    private fun buildBuildersJar(name: String, manifest: BundleManifest): Path {
        val target = tmp.resolve(name)
        JarOutputStream(Files.newOutputStream(target), Manifest()).use { jar ->
            for (cls in listOf(Phase1TestBuilder::class.java, Phase1ObjectBuilder::class.java)) {
                val entryName = cls.name.replace('.', '/') + ".class"
                jar.putNextEntry(JarEntry(entryName).apply { time = 0L })
                cls.classLoader.getResourceAsStream(entryName)!!.use { it.copyTo(jar) }
                jar.closeEntry()
            }
            jar.putNextEntry(JarEntry(BundleLayout.BUNDLE_TOML).apply { time = 0L })
            jar.write(BundleManifestToml.encode(manifest).toByteArray(Charsets.UTF_8))
            jar.closeEntry()
            jar.putNextEntry(JarEntry(recipePath).apply { time = 0L })
            jar.write("# light-load recipe\n".toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }
        return target
    }

    @Test
    fun `manifest-only JAR loads as one bundle with full identity`() {
        val jar = buildBuildersJar("p1.jar", manifest())
        BundleLoader.loadJar(jar).also { assertEquals(1, it.size) }.first().use { lb ->
            assertEquals("test.bundle.p1", lb.bundle.bundleId)
            assertEquals("Phase 1 Test Bundle", lb.bundle.displayName)
            assertEquals("1.0.0", lb.bundle.version)
            assertEquals(listOf("p1-model"), lb.bundle.models.map { it.modelId })
            val model = lb.bundle.models.single()
            assertEquals(setOf(KSLAppKind.SINGLE, KSLAppKind.EXPERIMENT), model.supportedApps)
        }
    }

    @Test
    fun `loaded bundle exposes contentHash builtAt and sourceJar (elegant-registry parity)`() {
        val jar = buildBuildersJar("p1.jar", manifest())
        BundleLoader.loadJar(jar).first().use { lb ->
            assertNotNull(lb.contentHash, "JAR-backed manifest bundle must carry a content hash")
            assertNotNull(lb.builtAt, "JAR-backed manifest bundle must carry a build timestamp")
            assertEquals(jar, lb.sourceJar)
        }
    }

    @Test
    fun `descriptor resolves via the reflective builder`() {
        val jar = buildBuildersJar("p1.jar", manifest())
        BundleLoader.loadJar(jar).first().use { lb ->
            val descriptor = lb.descriptorFor("p1-model")
            assertEquals("p1-model", descriptor.modelName)
            assertTrue("throughput" in descriptor.responseNames, "expected the model's response")
        }
    }

    @Test
    fun `recipesFor returns the manifest-listed recipe and its bytes`() {
        val jar = buildBuildersJar("p1.jar", manifest())
        BundleLoader.loadJar(jar).first().use { lb ->
            val recipes = lb.bundle.recipesFor("p1-model")
            assertEquals(1, recipes.size)
            val recipe = recipes.single()
            assertEquals("light", recipe.name)
            assertEquals(ConfigRecipeKind.RUN, recipe.kind)
            val text = recipe.openStream().use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(text.contains("light-load recipe"))
            assertTrue(lb.bundle.recipesFor("absent-model").isEmpty())
        }
    }

    @Test
    fun `Kotlin object builder is supported`() {
        val m = manifest().let {
            it.copy(
                models = listOf(
                    it.models.single().copy(
                        modelId = "p1-object-model",
                        builderClass = Phase1ObjectBuilder::class.java.name,
                        recipes = emptyList(),
                    )
                )
            )
        }
        val jar = buildBuildersJar("p1obj.jar", m)
        BundleLoader.loadJar(jar).first().use { lb ->
            val descriptor = lb.descriptorFor("p1-object-model")
            assertTrue("utilization" in descriptor.responseNames)
        }
    }

}
