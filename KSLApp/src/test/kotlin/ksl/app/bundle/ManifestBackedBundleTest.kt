package ksl.app.bundle

import ksl.app.config.BundleManifest
import ksl.app.config.BundleManifestToml
import ksl.app.config.ModelManifestEntry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Loader contract tests: a JAR with a `bundle.toml` and builder classes but no
 * embedded descriptors is loadable through the lenient [BundleLoader.loadJar]
 * primitive (so tooling can read it) yet **incomplete** — [LoadedBundle.missingDescriptors]
 * reports the gap and [LoadedBundle.descriptorFor] rejects it. Only a fully
 * assembled bundle (descriptors embedded) is usable for descriptors.
 */
class ManifestBackedBundleTest {

    @TempDir
    lateinit var tmp: Path

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
            ),
        ),
    )

    /** Writes a builders JAR: the fixture class file(s) and the manifest. */
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
    fun `a manifest-only bundle is incomplete and descriptorFor rejects it`() {
        val jar = buildBuildersJar("p1.jar", manifest())
        BundleLoader.loadJar(jar).first().use { lb ->
            assertEquals(listOf("p1-model"), lb.missingDescriptors())
            assertFailsWith<IncompleteBundleException> { lb.descriptorFor("p1-model") }
        }
    }

    @Test
    fun `a manifest-only bundle with a Kotlin object builder is also incomplete`() {
        val m = manifest().let {
            it.copy(
                models = listOf(
                    it.models.single().copy(
                        modelId = "p1-object-model",
                        builderClass = Phase1ObjectBuilder::class.java.name,
                    )
                )
            )
        }
        val jar = buildBuildersJar("p1obj.jar", m)
        BundleLoader.loadJar(jar).first().use { lb ->
            assertEquals(listOf("p1-object-model"), lb.missingDescriptors())
            assertFailsWith<IncompleteBundleException> { lb.descriptorFor("p1-object-model") }
        }
    }

}
