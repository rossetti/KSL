/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.config

import ksl.app.bundle.ConfigRecipeKind
import ksl.app.config.experiment.ControlBinding
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.ExperimentConfigurationToml
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.optimization.EvaluationSpec
import ksl.app.config.optimization.OptimizationOutputConfig
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationRunConfigurationToml
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Headless tests for [RecipeImport] — the reusable core of the Bundle
 * Workbench recipe import wizard (Phase 11.B).
 */
class RecipeImportTest {

    // ── summarize: Single/Scenario documents ────────────────────────────────

    @Test
    fun `summarize detects a single-scenario RUN document`() {
        val run = RunConfiguration(
            outputConfig = OutputConfig(analysisName = "OneRun"),
            scenarios = listOf(scenario("base", ModelReference.ByProviderId("MM1"))),
        )
        val s = RecipeImport.summarize(RunConfigurationToml.encode(run).toByteArray())
        assertTrue(s.detected)
        assertEquals(ConfigRecipeKind.RUN, s.kind)
        assertEquals(RecipeImport.Format.TOML, s.format)
        assertEquals("OneRun", s.analysisName)
        assertEquals(listOf("MM1"), s.referencedModelIds)
        assertEquals(1, s.scenarios.size)
        assertEquals("base", s.scenarios.single().name)
        assertEquals("byProviderId", s.scenarios.single().referenceType)
    }

    @Test
    fun `summarize detects a multi-scenario SCENARIO_BATCH and lists per-scenario model ids`() {
        val run = RunConfiguration(
            scenarios = listOf(
                scenario("a", ModelReference.ByBundleAndModelId("edu.x.bundle", "mm1")),
                scenario("b", ModelReference.ByBundleAndModelId("edu.x.bundle", "mm3")),
                scenario("c", ModelReference.ByJar("/tmp/foo.jar")),
            ),
        )
        val s = RecipeImport.summarize(RunConfigurationToml.encode(run).toByteArray())
        assertTrue(s.detected)
        assertEquals(ConfigRecipeKind.SCENARIO_BATCH, s.kind)
        assertEquals(listOf("mm1", "mm3", null), s.referencedModelIds)
        assertEquals(listOf(0, 1, 2), s.scenarios.map { it.index })
        assertEquals("edu.x.bundle", s.scenarios[0].bundleId)
        assertNull(s.scenarios[2].modelId)
        assertEquals("byJar", s.scenarios[2].referenceType)
    }

    @Test
    fun `summarize detects a RUN document encoded as JSON`() {
        val run = RunConfiguration(
            scenarios = listOf(scenario("base", ModelReference.ByProviderId("MM1"))),
        )
        val s = RecipeImport.summarize(RunConfigurationJson.encode(run).toByteArray())
        assertTrue(s.detected)
        assertEquals(ConfigRecipeKind.RUN, s.kind)
        assertEquals(RecipeImport.Format.JSON, s.format)
    }

    @Test
    fun `summarize reports undetected for garbage input`() {
        val s = RecipeImport.summarize("this is not a config file".toByteArray())
        assertFalse(s.detected)
        assertNull(s.kind)
        assertTrue(s.error!!.isNotBlank())
    }

    // ── summarize: Experiment and Optimization documents ────────────────────

    @Test
    fun `summarize detects an EXPERIMENT document`() {
        val s = RecipeImport.summarize(ExperimentConfigurationToml.encode(experiment()).toByteArray())
        assertTrue(s.detected)
        assertEquals(ConfigRecipeKind.EXPERIMENT, s.kind)
        assertEquals(listOf("MM1"), s.referencedModelIds) // Embedded -> modelName
        assertTrue(s.scenarios.isEmpty())
    }

    @Test
    fun `summarize detects an OPTIMIZATION document`() {
        val s = RecipeImport.summarize(OptimizationRunConfigurationToml.encode(optimization()).toByteArray())
        assertTrue(s.detected)
        assertEquals(ConfigRecipeKind.OPTIMIZATION, s.kind)
        assertEquals(listOf("MM1"), s.referencedModelIds)
        assertTrue(s.scenarios.isEmpty())
    }

    // ── transforms: filter + retarget on RunConfiguration ───────────────────

    @Test
    fun `filterToScenarios keeps only the selected scenarios in order`() {
        val run = RunConfiguration(
            scenarios = listOf(
                scenario("a", ModelReference.ByProviderId("m1")),
                scenario("b", ModelReference.ByProviderId("m2")),
                scenario("c", ModelReference.ByProviderId("m3")),
            ),
        )
        val filtered = RecipeImport.filterToScenarios(run, setOf(0, 2))
        assertEquals(listOf("a", "c"), filtered.scenarios.map { it.name })
    }

    @Test
    fun `retarget rewrites references and preserves runOverrides`() {
        val overrides = ExperimentRunOverrides(numberOfReplications = 42)
        val run = RunConfiguration(
            scenarios = listOf(
                scenario("a", ModelReference.ByProviderId("legacy"), runOverrides = overrides),
                scenario("b", ModelReference.ByJar("/tmp/x.jar")),
            ),
        )
        val out = RecipeImport.retarget(run, "edu.x.bundle", "mm1")
        out.scenarios.forEach {
            val ref = it.modelReference
            assertIs<ModelReference.ByBundleAndModelId>(ref)
            assertEquals("edu.x.bundle", ref.bundleId)
            assertEquals("mm1", ref.modelId)
        }
        // runOverrides survived the rewrite untouched.
        assertEquals(42, out.scenarios[0].runOverrides?.numberOfReplications)
    }

    @Test
    fun `retarget can target a subset of scenarios`() {
        val run = RunConfiguration(
            scenarios = listOf(
                scenario("a", ModelReference.ByProviderId("m1")),
                scenario("b", ModelReference.ByProviderId("m2")),
            ),
        )
        val out = RecipeImport.retarget(run, "edu.x.bundle", "mm1", scenarioIndices = setOf(0))
        assertIs<ModelReference.ByBundleAndModelId>(out.scenarios[0].modelReference)
        assertIs<ModelReference.ByProviderId>(out.scenarios[1].modelReference)
    }

    // ── importForModel: end-to-end ──────────────────────────────────────────

    @Test
    fun `importForModel filters and retargets a scenario document and re-decodes cleanly`() {
        val run = RunConfiguration(
            outputConfig = OutputConfig(analysisName = "MixedBatch"),
            scenarios = listOf(
                scenario("keep1", ModelReference.ByProviderId("mm1")),
                scenario("drop", ModelReference.ByProviderId("other")),
                scenario("keep2", ModelReference.ByProviderId("mm1")),
            ),
        )
        val result = RecipeImport.importForModel(
            bytes = RunConfigurationToml.encode(run).toByteArray(),
            bundleId = "edu.x.bundle",
            modelId = "mm1",
            keepScenarioIndices = setOf(0, 2),
            retarget = true,
        )
        // two scenarios kept -> SCENARIO_BATCH
        assertEquals(ConfigRecipeKind.SCENARIO_BATCH, result.kind)
        val decoded = RunConfigurationToml.decode(result.bytes.decodeToString())
        assertEquals(listOf("keep1", "keep2"), decoded.scenarios.map { it.name })
        decoded.scenarios.forEach {
            val ref = it.modelReference
            assertIs<ModelReference.ByBundleAndModelId>(ref)
            assertEquals("edu.x.bundle", ref.bundleId)
            assertEquals("mm1", ref.modelId)
        }
    }

    @Test
    fun `importForModel yields RUN when exactly one scenario is kept`() {
        val run = RunConfiguration(
            scenarios = listOf(
                scenario("a", ModelReference.ByProviderId("m1")),
                scenario("b", ModelReference.ByProviderId("m2")),
            ),
        )
        val result = RecipeImport.importForModel(
            bytes = RunConfigurationToml.encode(run).toByteArray(),
            bundleId = "edu.x.bundle",
            modelId = "mm1",
            keepScenarioIndices = setOf(1),
        )
        assertEquals(ConfigRecipeKind.RUN, result.kind)
        val decoded = RunConfigurationToml.decode(result.bytes.decodeToString())
        assertEquals(listOf("b"), decoded.scenarios.map { it.name })
    }

    @Test
    fun `importForModel with retarget off leaves references untouched`() {
        val run = RunConfiguration(
            scenarios = listOf(scenario("a", ModelReference.ByProviderId("legacy"))),
        )
        val result = RecipeImport.importForModel(
            bytes = RunConfigurationToml.encode(run).toByteArray(),
            bundleId = "edu.x.bundle",
            modelId = "mm1",
            retarget = false,
        )
        val decoded = RunConfigurationToml.decode(result.bytes.decodeToString())
        val ref = decoded.scenarios.single().modelReference
        assertIs<ModelReference.ByProviderId>(ref)
        assertEquals("legacy", ref.providerId)
    }

    @Test
    fun `importForModel prunes bundleRefs no longer referenced after retarget`() {
        val run = RunConfiguration(
            scenarios = listOf(scenario("a", ModelReference.ByBundleAndModelId("edu.other.bundle", "z"))),
            bundleRefs = listOf(BundleRef(bundleId = "edu.other.bundle", paths = listOf("/tmp/other.jar"))),
        )
        val result = RecipeImport.importForModel(
            bytes = RunConfigurationToml.encode(run).toByteArray(),
            bundleId = "edu.x.bundle",
            modelId = "mm1",
            retarget = true,
        )
        val decoded = RunConfigurationToml.decode(result.bytes.decodeToString())
        // The stale ref to edu.other.bundle is gone; the retargeted bundle has no
        // ref entry (it is the enclosing bundle, resolved in context).
        assertTrue(decoded.bundleRefs.isEmpty(), "stale bundleRefs should be pruned: ${decoded.bundleRefs}")
    }

    @Test
    fun `importForModel rejects a selection that keeps no scenarios`() {
        val run = RunConfiguration(
            scenarios = listOf(scenario("a", ModelReference.ByProviderId("m1"))),
        )
        assertFailsWith<IllegalArgumentException> {
            RecipeImport.importForModel(
                bytes = RunConfigurationToml.encode(run).toByteArray(),
                bundleId = "edu.x.bundle",
                modelId = "mm1",
                keepScenarioIndices = emptySet(),
            )
        }
    }

    @Test
    fun `importForModel retargets an EXPERIMENT document`() {
        val result = RecipeImport.importForModel(
            bytes = ExperimentConfigurationToml.encode(experiment()).toByteArray(),
            bundleId = "edu.x.bundle",
            modelId = "mm1",
            retarget = true,
        )
        assertEquals(ConfigRecipeKind.EXPERIMENT, result.kind)
        val decoded = ExperimentConfigurationToml.decode(result.bytes.decodeToString())
        val ref = decoded.modelReference
        assertIs<ModelReference.ByBundleAndModelId>(ref)
        assertEquals("edu.x.bundle", ref.bundleId)
        assertEquals("mm1", ref.modelId)
        // factors preserved
        assertEquals(2, decoded.factors.size)
    }

    @Test
    fun `importForModel retargets an OPTIMIZATION document`() {
        val result = RecipeImport.importForModel(
            bytes = OptimizationRunConfigurationToml.encode(optimization()).toByteArray(),
            bundleId = "edu.x.bundle",
            modelId = "mm1",
            retarget = true,
        )
        assertEquals(ConfigRecipeKind.OPTIMIZATION, result.kind)
        val decoded = OptimizationRunConfigurationToml.decode(result.bytes.decodeToString())
        val ref = decoded.model.modelReference
        assertIs<ModelReference.ByBundleAndModelId>(ref)
        assertEquals("edu.x.bundle", ref.bundleId)
        assertEquals("mm1", ref.modelId)
    }

    @Test
    fun `importForModel rejects undecodable bytes`() {
        assertFailsWith<IllegalArgumentException> {
            RecipeImport.importForModel("garbage".toByteArray(), "edu.x.bundle", "mm1")
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun scenario(
        name: String,
        ref: ModelReference,
        runOverrides: ExperimentRunOverrides? = null,
    ) = ScenarioSpec(name = name, modelReference = ref, runOverrides = runOverrides)

    private fun experiment() = ExperimentConfiguration(
        outputConfig = OutputConfig(analysisName = "Exp"),
        modelReference = ModelReference.Embedded("MM1"),
        factors = listOf(
            FactorSpec(name = "A", levels = listOf(0.0, 1.0), binding = ControlBinding.Control("A.value")),
            FactorSpec(name = "B", levels = listOf(0.0, 1.0), binding = ControlBinding.Control("B.value")),
        ),
        designSpec = DesignSpec.FullFactorial,
    )

    private fun optimization() = OptimizationRunConfiguration(
        output = OptimizationOutputConfig(analysisName = "Opt"),
        model = ModelRunTemplate(
            modelReference = ModelReference.ByProviderId("MM1"),
            runParameters = runParameters(),
        ),
        problem = null,
        solver = null,
        evaluation = EvaluationSpec(),
    )

    private fun runParameters() =
        Model("MM1", autoCSVReports = false).also { model ->
            GIGcQueue(model, numServers = 1, name = "MM1Queue")
            model.numberOfReplications = 3
            model.lengthOfReplication = 100.0
            model.lengthOfReplicationWarmUp = 10.0
        }.extractRunParameters()
}
