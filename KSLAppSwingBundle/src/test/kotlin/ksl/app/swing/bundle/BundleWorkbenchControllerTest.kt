package ksl.app.swing.bundle

import ksl.app.bundle.BundleLoader
import ksl.app.bundle.ConfigRecipeKind
import ksl.app.bundle.KSLAppKind
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationToml
import ksl.app.config.ScenarioSpec
import ksl.app.swing.bundle.support.TestJarBuilder
import ksl.app.swing.bundle.support.WorkbenchTestBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            assertEquals("wb", c.identity.value!!.bundleId, "bundleId defaults to the JAR stem")
            assertEquals(1, c.models.value.size)
            assertNotNull(c.currentDescriptor.value)
            assertNotNull(c.catalogDraft.value)
            assertFalse(c.dirty.value)
        } finally {
            c.dispose()
        }
    }

    @Test
    fun `editing identity, metadata, catalog, and a recipe then assembling round-trips`(@TempDir dir: Path) {
        val c = controller()
        try {
            c.openBuildersJar(buildersJar(dir))
            val modelId = c.selected!!

            c.updateIdentity { it.copy(bundleId = "edu.test.wb", displayName = "WB") }
            c.updateModel(modelId) { it.copy(supportedApps = setOf(KSLAppKind.SINGLE)) }
            c.updateDraft { it.nominateAll() }
            assertTrue(c.dirty.value)

            val scenario = RecipeDraft(name = "light", numberOfReplications = 10)
                .toScenarioSpec("edu.test.wb", modelId)
            c.addRunRecipe("light", scenario)
            assertEquals(listOf("light"), c.recipes.value.map { it.name })

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
                val recipes = lb.bundle.recipesFor(modelId)
                assertEquals(1, recipes.size)
                assertEquals(ConfigRecipeKind.RUN, recipes.single().kind)
            }
        } finally {
            c.dispose()
        }
    }

    @Test
    fun `a recipe referencing a different model is flagged on validate`(@TempDir dir: Path) {
        val c = controller()
        try {
            c.openBuildersJar(buildersJar(dir))
            val toml = RunConfigurationToml.encode(
                RunConfiguration(scenarios = listOf(
                    ScenarioSpec(
                        name = "s",
                        modelReference = ModelReference.ByBundleAndModelId(c.identity.value!!.bundleId, "some-other-model"),
                    ),
                ))
            ).toByteArray()
            c.addRecipe(ConfigRecipeKind.RUN, "x", toml) // addRecipe triggers validate()
            val report = c.validation.value
            assertNotNull(report)
            assertTrue(
                report.findings.any { it.message.contains("references model 'some-other-model'") },
                "expected a recipe/model mismatch warning: ${report.findings}"
            )
        } finally {
            c.dispose()
        }
    }

    @Test
    fun `importRecipe summarizes, filters, and retargets an external scenario document`(@TempDir dir: Path) {
        val c = controller()
        try {
            c.openBuildersJar(buildersJar(dir))
            c.updateIdentity { it.copy(bundleId = "edu.test.wb") }
            val modelId = c.selected!!

            // An external scenario document: two scenarios pointing at legacy provider ids.
            val external = RunConfigurationToml.encode(
                RunConfiguration(scenarios = listOf(
                    ScenarioSpec(name = "keep", modelReference = ModelReference.ByProviderId("legacyA")),
                    ScenarioSpec(name = "drop", modelReference = ModelReference.ByProviderId("legacyB")),
                ))
            ).toByteArray()

            // Summarize sees both scenarios and their (foreign) model ids.
            val summary = c.summarizeRecipe(external)
            assertTrue(summary.detected)
            assertEquals(listOf("legacyA", "legacyB"), summary.referencedModelIds)

            // Keep one scenario and retarget it to this bundled model.
            val name = c.importRecipe(external, keepScenarioIndices = setOf(0), retarget = true)
            assertEquals(listOf(name), c.recipes.value.map { it.name })

            // The stored recipe is a single-scenario RUN retargeted to (bundleId, modelId);
            // validate finds no model-mismatch warning.
            val bytes = c.recipeBytes(name, ConfigRecipeKind.RUN)!!
            val decoded = RunConfigurationToml.decode(bytes.decodeToString())
            assertEquals(
                ModelReference.ByBundleAndModelId("edu.test.wb", modelId),
                decoded.scenarios.single().modelReference,
            )
            val report = c.validation.value!!
            assertFalse(
                report.findings.any { it.message.contains("references model") },
                "retargeted recipe should not trigger a model-mismatch warning: ${report.findings}",
            )
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
    fun `recipes of any kind can be added, parsed, and removed`(@TempDir dir: Path) {
        val c = controller()
        try {
            c.openBuildersJar(buildersJar(dir))
            val modelId = c.selected!!

            // A valid RUN document parses; garbage does not.
            val runToml = RunConfigurationToml.encode(
                RunConfiguration(scenarios = listOf(
                    ScenarioSpec(name = "s", modelReference = ModelReference.ByBundleAndModelId(c.identity.value!!.bundleId, modelId)),
                ))
            ).toByteArray()
            assertNull(c.parseRecipe(ConfigRecipeKind.RUN, runToml), "valid RUN doc should parse")
            assertNotNull(c.parseRecipe(ConfigRecipeKind.OPTIMIZATION, "not a config".toByteArray()), "garbage should not parse")

            c.addRecipe(ConfigRecipeKind.RUN, "batch", runToml)
            c.addRecipe(ConfigRecipeKind.SCENARIO_BATCH, "batch", runToml)
            assertEquals(
                setOf("batch" to ConfigRecipeKind.RUN, "batch" to ConfigRecipeKind.SCENARIO_BATCH),
                c.recipes.value.map { it.name to it.kind }.toSet(),
                "same name under different kinds are distinct recipes",
            )

            c.removeRecipe("batch", ConfigRecipeKind.RUN)
            assertEquals(listOf(ConfigRecipeKind.SCENARIO_BATCH), c.recipes.value.map { it.kind })
        } finally {
            c.dispose()
        }
    }
}
