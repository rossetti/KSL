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

package ksl.service.config

import net.peanuuutz.tomlkt.Toml
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.RunConfigurationToml
import ksl.app.config.ScenarioSpec
import ksl.app.config.experiment.ControlBinding
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.ExperimentConfigurationToml
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.ReplicationSpec
import ksl.app.config.optimization.OptimizationRunConfigurationJson
import ksl.app.config.optimization.OptimizationRunConfigurationToml
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.service.capability.fit.FitDocuments
import ksl.service.capability.run.ExperimentDocuments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves the server transports' format-tolerant decoder accepts a configuration
 * document as either JSON or TOML and lands on the same configuration — so a
 * `.toml` file authored by a KSL desktop app runs without a JSON conversion.
 *
 * Equality is asserted on the canonical re-encoded JSON rather than the config
 * object, sidestepping `DoubleArray` identity-equality in an inline fit source.
 */
class ConfigDocumentsTest {

    private val fitToml = Toml { explicitNulls = false }

    @Test
    fun `run configuration decodes identically from JSON and TOML`() {
        val original = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "doc",
                    modelReference = ModelReference.ByProviderId("MM1"),
                    runOverrides = ExperimentRunOverrides(numberOfReplications = 7),
                ),
            ),
            outputConfig = OutputConfig(reports = emptySet()),
        )
        val canonical = RunConfigurationJson.encode(original)
        assertEquals(canonical, RunConfigurationJson.encode(ConfigDocuments.decodeRun(RunConfigurationJson.encode(original))))
        assertEquals(canonical, RunConfigurationJson.encode(ConfigDocuments.decodeRun(RunConfigurationToml.encode(original))))
    }

    @Test
    fun `experiment configuration decodes identically from JSON and TOML`() {
        val original = ExperimentConfiguration(
            modelReference = ModelReference.ByProviderId("MM1"),
            factors = listOf(
                FactorSpec("A", listOf(1.0, 2.0), ControlBinding.Control("A")),
                FactorSpec("B", listOf(1.0, 2.0), ControlBinding.Control("B")),
            ),
            designSpec = DesignSpec.TwoLevelFactorial(),
            replications = ReplicationSpec.Uniform(10),
        )
        val canonical = ExperimentDocuments.encode(original)
        assertEquals(canonical, ExperimentDocuments.encode(ConfigDocuments.decodeExperiment(ExperimentDocuments.encode(original))))
        assertEquals(canonical, ExperimentDocuments.encode(ConfigDocuments.decodeExperiment(ExperimentConfigurationToml.encode(original))))
    }

    @Test
    fun `fit configuration decodes identically from JSON and TOML`() {
        val original = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("svc" to doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0))),
            kind = DistributionKind.CONTINUOUS,
            estimatorIds = FittingCatalog.defaultEstimatorIds(DistributionKind.CONTINUOUS),
            scoringModelIds = FittingCatalog.defaultScoringModelIds(),
        )
        val canonical = FitDocuments.encode(original)
        assertEquals(canonical, FitDocuments.encode(ConfigDocuments.decodeFit(FitDocuments.encode(original))))
        val tomlText = fitToml.encodeToString(FitConfiguration.serializer(), original)
        assertEquals(canonical, FitDocuments.encode(ConfigDocuments.decodeFit(tomlText)))
    }

    @Test
    fun `optimization configuration decodes from both formats`() {
        // A minimal, model-only document (null problem/solver) — enough to prove
        // both codecs route through the decoder.
        val tomlText = """
            [output]
            analysisName = "HandAuthored"

            [model.modelReference]
            type = "byProviderId"
            providerId = "MM1"

            [model.runParameters]
            experimentName = "MM1"
            experimentId = 1
            numberOfReplications = 5
            numChunks = 1
            runName = "MM1"
            startingRepId = 1
            lengthOfReplication = 100.0
            lengthOfReplicationWarmUp = 0.0
            replicationInitializationOption = true
            maximumAllowedExecutionTimePerReplication = "PT0S"
            resetStartStreamOption = false
            advanceNextSubStreamOption = true
            antitheticOption = false
            numberOfStreamAdvancesPriorToRunning = 0
            garbageCollectAfterReplicationFlag = false
        """.trimIndent()
        val fromToml = ConfigDocuments.decodeOptimization(tomlText)
        assertEquals("HandAuthored", fromToml.output.analysisName)
        // Re-encode to TOML and JSON and confirm both round-trip through the decoder.
        assertEquals(
            OptimizationRunConfigurationJson.encode(fromToml),
            OptimizationRunConfigurationJson.encode(ConfigDocuments.decodeOptimization(OptimizationRunConfigurationToml.encode(fromToml))),
        )
        assertEquals(
            OptimizationRunConfigurationJson.encode(fromToml),
            OptimizationRunConfigurationJson.encode(ConfigDocuments.decodeOptimization(OptimizationRunConfigurationJson.encode(fromToml))),
        )
    }

    @Test
    fun `a malformed document is rejected with an error`() {
        assertFailsWith<IllegalArgumentException> { ConfigDocuments.decodeRun("this is neither json nor valid toml = = =") }
    }

    @Test
    fun `a mis-sniffed but valid document still decodes via fallback`() {
        // TOML content is decoded even though the helper would try JSON first only
        // for {/[ leads; here a TOML doc leads with a comment then a section header.
        val original = RunConfiguration(
            scenarios = listOf(ScenarioSpec("s", ModelReference.ByProviderId("MM1"))),
            outputConfig = OutputConfig(reports = emptySet()),
        )
        val tomlText = RunConfigurationToml.encode(original) // begins with a # banner
        assertTrue(tomlText.trimStart().startsWith("#"), "the TOML doc leads with a comment banner")
        assertEquals(
            RunConfigurationJson.encode(original),
            RunConfigurationJson.encode(ConfigDocuments.decodeRun(tomlText)),
        )
    }
}
