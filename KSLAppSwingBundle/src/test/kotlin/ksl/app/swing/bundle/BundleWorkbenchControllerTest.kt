package ksl.app.swing.bundle

import ksl.app.bundle.BundleLoader
import ksl.app.bundle.KSLAppKind
import ksl.app.swing.bundle.support.TestJarBuilder
import ksl.app.swing.bundle.support.WorkbenchSecondBuilder
import ksl.app.swing.bundle.support.WorkbenchTestBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Headless tests for the reframed [BundleWorkbenchController] — a thin adapter over
 * `BundleAuthoringSession` driving the builders-JAR → bundle-JAR flow.
 */
class BundleWorkbenchControllerTest {

    private fun controller() = BundleWorkbenchController("Test Workbench")

    private fun buildersJar(dir: Path) =
        TestJarBuilder.buildBuildersJar(dir, "wb", WorkbenchTestBuilder::class.java)

    @Test
    fun `opening a builders JAR discovers models and seeds identity and selection`(@TempDir dir: Path) {
        val c = controller()
        try {
            c.openBuildersJar(buildersJar(dir))
            assertEquals("WorkbenchTest", c.selected, "modelId derived from the builder FQN")
            assertNotNull(c.identity.value)
            assertEquals("", c.identity.value!!.bundleId, "bundleId is NOT auto-filled; the user must enter it")
            assertEquals(1, c.models.value.size)
            assertNotNull(c.currentDescriptor.value)
            assertNotNull(c.catalogDraft.value)
            assertFalse(c.dirty.value)
        } finally {
            c.dispose()
        }
    }

    @Test
    fun `editing identity, metadata, and catalog then assembling round-trips`(@TempDir dir: Path) {
        val c = controller()
        try {
            c.openBuildersJar(buildersJar(dir))
            val modelId = c.selected!!

            c.updateIdentity { it.copy(bundleId = "edu.test.wb", displayName = "WB") }
            c.updateModel(modelId) { it.copy(supportedApps = setOf(KSLAppKind.SINGLE)) }
            c.updateDraft { it.nominateAll() }
            assertTrue(c.dirty.value)

            val report = c.validate()
            assertNotNull(report)
            assertTrue(report.isClean, "expected no ERROR findings: ${report.findings}")
            // validate publishes to the health bus for the inline banner
            assertTrue(c.healthBus.result.value.isValid, "clean report should leave the banner error-free")

            val output = dir.resolve("wb-bundle.jar")
            c.assemble(output)
            assertFalse(c.dirty.value, "assembling clears dirty")
            assertEquals(output, c.lastAssembled.value, "assemble records the output path for the status line")

            BundleLoader.loadJar(output).also { assertEquals(1, it.size) }.first().use { lb ->
                assertEquals("edu.test.wb", lb.bundle.bundleId)
                val model = lb.bundle.models.single()
                assertEquals(modelId, model.modelId)
                assertEquals(setOf(KSLAppKind.SINGLE), model.supportedApps)
                assertNotNull(lb.descriptorFor(modelId).catalog)
            }
        } finally {
            c.dispose()
        }
    }

    @Test
    fun `catalog problems are clean for a valid draft`(@TempDir dir: Path) {
        val c = controller()
        try {
            c.openBuildersJar(buildersJar(dir))
            c.updateDraft { it.nominateAll() }
            assertTrue(
                c.catalogProblems.value.isEmpty(),
                "nominating real candidates should not produce problems: ${c.catalogProblems.value}"
            )
        } finally {
            c.dispose()
        }
    }

    @Test
    fun `excluding a discovered model drops it from the assembled bundle`(@TempDir dir: Path) {
        val c = controller()
        try {
            val jar = TestJarBuilder.buildBuildersJar(
                dir, "wb2", WorkbenchTestBuilder::class.java, WorkbenchSecondBuilder::class.java
            )
            c.openBuildersJar(jar)
            assertEquals(2, c.models.value.size, "two builders discovered")
            assertTrue(c.models.value.all { it.included }, "models start included")
            c.models.value.forEach { mv ->
                c.updateModel(mv.modelId) { it.copy(supportedApps = setOf(KSLAppKind.SINGLE)) }
            }

            val dropped = c.models.value.map { it.modelId }.sorted().first()
            c.setIncluded(dropped, false)
            assertFalse(
                c.models.value.first { it.modelId == dropped }.included,
                "the toggled model reads as excluded"
            )

            c.updateIdentity { it.copy(bundleId = "edu.test.wb2", displayName = "WB2") }
            val output = dir.resolve("wb2-bundle.jar")
            c.assemble(output)

            BundleLoader.loadJar(output).first().use { lb ->
                assertEquals(1, lb.bundle.models.size, "the excluded model is not declared in the bundle")
                assertFalse(lb.bundle.models.any { it.modelId == dropped }, "the excluded id is gone")
            }
        } finally {
            c.dispose()
        }
    }

    @Test
    fun `include-all and exclude-all toggle every model`(@TempDir dir: Path) {
        val c = controller()
        try {
            val jar = TestJarBuilder.buildBuildersJar(
                dir, "wb3", WorkbenchTestBuilder::class.java, WorkbenchSecondBuilder::class.java
            )
            c.openBuildersJar(jar)
            assertEquals(2, c.models.value.size)

            c.setAllIncluded(false)
            assertTrue(c.models.value.none { it.included }, "exclude-all clears every In bundle box")

            c.setAllIncluded(true)
            assertTrue(c.models.value.all { it.included }, "include-all re-checks every model")
        } finally {
            c.dispose()
        }
    }

}
