package ksl.app.bundle

import ksl.simulation.ModelCatalog
import ksl.simulation.NominatedOutput
import org.junit.jupiter.api.DisplayName
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

/** Phase 4 (4.4): the headless [BundleAuthoringSession] orchestration. */
class BundleAuthoringSessionTest {

    @TempDir
    lateinit var tmp: Path

    private fun buildersJar(
        name: String,
        classes: List<Class<*>> = listOf(Phase1TestBuilder::class.java, Phase1ObjectBuilder::class.java),
    ): Path {
        val target = tmp.resolve(name)
        JarOutputStream(Files.newOutputStream(target), Manifest()).use { jar ->
            for (cls in classes) {
                val entry = cls.name.replace('.', '/') + ".class"
                jar.putNextEntry(JarEntry(entry).apply { time = 0L })
                cls.classLoader.getResourceAsStream(entry)!!.use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return target
    }

    @Test
    fun `open discovers builders and seeds drafts with derived modelIds`() {
        val session = BundleAuthoringSession.open(buildersJar("b.jar"))
        assertEquals(2, session.models.size)
        assertTrue(session.discoveryErrors.isEmpty())
        // "Phase1TestBuilder" -> "Phase1Test" (trailing "Builder" stripped)
        assertTrue(session.models.any { it.modelId == "Phase1Test" }, "got ${session.models.map { it.modelId }}")
    }

    @Test
    @DisplayName("EXPERIMENT is defaulted only when the model exposes at least two numeric factors")
    fun experimentDefaultsOnlyWithTwoOrMoreNumericFactors() {
        val session = BundleAuthoringSession.open(
            buildersJar("rv.jar", listOf(Phase1RvFactorBuilder::class.java, Phase1TestBuilder::class.java))
        )

        // RV-only model (M/M/1): its two factors are RV means, no @KSLControl controls — so the
        // count comes entirely from the RV-parameter path. It must still default to EXPERIMENT.
        val rvModel = session.models.first { it.builderClass == Phase1RvFactorBuilder::class.java.name }
        assertEquals(
            2, rvModel.descriptor.inputNames.size,
            "the RV-only fixture should expose 2 numeric factors via the RV-parameter path"
        )
        assertTrue(
            KSLAppKind.EXPERIMENT in rvModel.supportedApps,
            "a model with >= 2 numeric factors (here, RV means) must default to include EXPERIMENT"
        )

        // Response-only model: 0 numeric factors -> EXPERIMENT must NOT be defaulted.
        val plainModel = session.models.first { it.builderClass == Phase1TestBuilder::class.java.name }
        assertEquals(0, plainModel.descriptor.inputNames.size)
        assertEquals(
            setOf(KSLAppKind.SINGLE, KSLAppKind.SCENARIO), plainModel.supportedApps,
            "a model with < 2 numeric factors must default to SINGLE + SCENARIO only (no EXPERIMENT)"
        )
    }

    @Test
    fun `default modelId strips builder suffixes`() {
        assertEquals("MM1", BundleAuthoringSession.defaultModelId("ksl.examples.mm1.MM1Builder"))
        assertEquals("Queue", BundleAuthoringSession.defaultModelId("p.QueueModelBuilder"))
        assertEquals("Foo", BundleAuthoringSession.defaultModelId("Foo"))
    }

    @Test
    fun `edit then validate then assemble round-trips and validates clean`() {
        val session = BundleAuthoringSession.open(buildersJar("b.jar"))
        session.bundleId = "edu.test.demo"
        session.displayName = "Demo"
        session.models.first { it.builderClass == Phase1TestBuilder::class.java.name }.apply {
            modelId = "mm1"; displayName = "MM1"; supportedApps.add(KSLAppKind.SINGLE)
        }
        session.models.first { it.builderClass == Phase1ObjectBuilder::class.java.name }.apply {
            modelId = "obj"; supportedApps.add(KSLAppKind.SINGLE)
        }

        val report = session.validate()
        assertTrue(report.isClean, "expected no ERROR findings: ${report.findings}")

        val output = tmp.resolve("demo-bundle.jar")
        session.assemble(output)
        BundleLoader.loadJar(output).also { assertEquals(1, it.size) }.first().use { lb ->
            assertEquals("edu.test.demo", lb.bundle.bundleId)
            assertEquals(setOf("mm1", "obj"), lb.bundle.models.map { it.modelId }.toSet())
        }
    }

    @Test
    fun `openExisting reopens an assembled bundle and restores the draft`(@TempDir tmp: Path) {
        val input = buildersJar("b.jar")
        val s = BundleAuthoringSession.open(input)
        s.bundleId = "edu.test.resume"
        s.displayName = "Resume"
        val m = s.models.first { it.builderClass == Phase1TestBuilder::class.java.name }
        m.modelId = "mm1"
        m.supportedApps.clear(); m.supportedApps.add(KSLAppKind.SINGLE)
        m.catalog = ModelCatalog(nominatedOutputs = listOf(NominatedOutput("throughput", displayName = "Throughput")))
        val out = tmp.resolve("resume-bundle.jar")
        s.assemble(out)

        val reopened = BundleAuthoringSession.openExisting(out)
        assertEquals("edu.test.resume", reopened.bundleId)
        assertEquals("Resume", reopened.displayName)
        val rm = reopened.models.first { it.builderClass == Phase1TestBuilder::class.java.name }
        assertEquals("mm1", rm.modelId)
        assertEquals(setOf(KSLAppKind.SINGLE), rm.supportedApps)
        assertNotNull(rm.catalog)
        assertTrue(rm.catalog!!.nominatedOutputs.any { it.name == "throughput" })
    }
}
