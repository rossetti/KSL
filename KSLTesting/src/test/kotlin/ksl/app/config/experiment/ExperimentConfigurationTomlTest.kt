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

package ksl.app.config.experiment

import ksl.app.config.DatabasePolicy
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Round-trip + encoded-shape tests for [ExperimentConfigurationToml].
 *
 *  - One round-trip per [DesignSpec] variant.
 *  - One round-trip per [ReplicationSpec] variant.
 *  - One round-trip per [StreamPolicy] variant.
 *  - Document header is present in encoded output.
 *  - First TOML section header in encoded output is `[outputConfig]`
 *    (matches the declared property order).
 *  - Legacy decode safety: an experiment TOML missing the newer
 *    `replications`, `streamPolicy`, `bundleRefs`, `tracingConfig`
 *    fields decodes to declared defaults.
 */
class ExperimentConfigurationTomlTest {

    @Test
    fun `full factorial config round-trips`() {
        val cfg = baseConfig(designSpec = DesignSpec.FullFactorial(centerPoints = 2))
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
        val ds = decoded.designSpec
        assertIs<DesignSpec.FullFactorial>(ds)
        assertEquals(2, ds.centerPoints)
    }

    @Test
    fun `two-level fractional config round-trips`() {
        val cfg = ExperimentConfiguration(
            modelReference = ModelReference.Embedded("MM1"),
            factors = listOf(
                factor("A", listOf(0.0, 1.0)),
                factor("B", listOf(0.0, 1.0)),
                factor("C", listOf(0.0, 1.0)),
                factor("D", listOf(0.0, 1.0))
            ),
            designSpec = DesignSpec.TwoLevelFractional(
                numFactors = 4,
                fractionExponent = 1,
                definingRelations = listOf("ABCD")
            )
        )
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
        val ds = decoded.designSpec
        assertIs<DesignSpec.TwoLevelFractional>(ds)
        assertEquals(listOf("ABCD"), ds.definingRelations)
    }

    @Test
    fun `central composite config round-trips`() {
        val cfg = baseConfig(
            designSpec = DesignSpec.CentralComposite(
                axialSpacing = 1.414,
                centerPoints = 4
            )
        )
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
        val ds = decoded.designSpec
        assertIs<DesignSpec.CentralComposite>(ds)
        assertEquals(1.414, ds.axialSpacing)
        assertEquals(4, ds.centerPoints)
    }

    @Test
    fun `manual design config round-trips`() {
        val cfg = ExperimentConfiguration(
            modelReference = ModelReference.Embedded("MM1"),
            factors = listOf(
                factor("A", listOf(0.0, 1.0)),
                factor("B", listOf(0.0, 1.0))
            ),
            designSpec = DesignSpec.Manual(
                points = listOf(
                    ManualPointSpec(
                        factorValues = mapOf("A" to 0.0, "B" to 0.5),
                        replications = 20
                    ),
                    ManualPointSpec(
                        factorValues = mapOf("A" to 1.0, "B" to 0.5)
                        // replications omitted → inherits document default
                    )
                )
            )
        )
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
        val ds = decoded.designSpec
        assertIs<DesignSpec.Manual>(ds)
        assertEquals(2, ds.points.size)
        assertEquals(20, ds.points[0].replications)
        assertEquals(null, ds.points[1].replications)
    }

    @Test
    fun `uniform replications round-trips`() {
        val cfg = baseConfig(replications = ReplicationSpec.Uniform(50))
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
        assertIs<ReplicationSpec.Uniform>(decoded.replications)
        assertEquals(50, (decoded.replications as ReplicationSpec.Uniform).replications)
    }

    @Test
    fun `per-point replications round-trips`() {
        val cfg = baseConfig(
            replications = ReplicationSpec.PerPoint(
                default = 10,
                overrides = mapOf(0 to 30, 3 to 50)
            )
        )
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
        val rep = decoded.replications
        assertIs<ReplicationSpec.PerPoint>(rep)
        assertEquals(10, rep.default)
        assertEquals(mapOf(0 to 30, 3 to 50), rep.overrides)
    }

    @Test
    fun `independent stream policy with default knobs round-trips`() {
        val cfg = baseConfig(streamPolicy = StreamPolicy.Independent())
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
        val sp = decoded.streamPolicy
        assertIs<StreamPolicy.Independent>(sp)
        assertEquals(0, sp.startingStreamAdvance)
        assertEquals(null, sp.streamAdvanceSpacing)
    }

    @Test
    fun `independent stream policy with non-default knobs round-trips`() {
        val cfg = baseConfig(
            streamPolicy = StreamPolicy.Independent(
                startingStreamAdvance = 7,
                streamAdvanceSpacing = 100
            )
        )
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
    }

    @Test
    fun `common random numbers stream policy round-trips`() {
        val cfg = baseConfig(streamPolicy = StreamPolicy.CommonRandomNumbers)
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
        assertEquals(StreamPolicy.CommonRandomNumbers, decoded.streamPolicy)
    }

    @Test
    fun `rv-parameter binding round-trips`() {
        val cfg = ExperimentConfiguration(
            modelReference = ModelReference.Embedded("MM1"),
            factors = listOf(
                FactorSpec(
                    name = "ServiceRate",
                    levels = listOf(0.5, 1.0, 1.5),
                    binding = ControlBinding.RVParameter(
                        rvName = "MM1:ServiceTime",
                        paramName = "mean"
                    )
                )
            ),
            designSpec = DesignSpec.FullFactorial()
        )
        val decoded = ExperimentConfigurationToml.decode(ExperimentConfigurationToml.encode(cfg))
        assertEquals(cfg, decoded)
        val binding = decoded.factors.single().binding
        assertIs<ControlBinding.RVParameter>(binding)
        assertEquals("MM1:ServiceTime", binding.rvName)
        assertEquals("mean", binding.paramName)
    }

    @Test
    fun `encoded TOML carries the document header banner`() {
        val text = ExperimentConfigurationToml.encode(baseConfig())
        assertContains(text, "KSL Experiment Configuration")
        assertContains(text, "Document layout")
        assertContains(text, "Editing guidelines")
        // First non-comment section header should be [outputConfig] —
        // the declared property order.
        val firstSection = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("[") && !it.startsWith("[[") }
        assertEquals(
            "[outputConfig]", firstSection,
            "Encoded order should start with [outputConfig]; got: $firstSection"
        )
    }

    @Test
    fun `encoded TOML attaches per-field comments to a sampling of fields`() {
        val text = ExperimentConfigurationToml.encode(baseConfig())
        // Stable phrases pulled from a few different @TomlComment
        // blocks across the type tree.
        assertContains(text, "User-given factor name")           // FactorSpec.name
        assertContains(text, "Raw values of this factor's levels")  // FactorSpec.levels
        assertContains(text, "How the engine enumerates")        // DesignSpec
        assertContains(text, "Random-stream policy")             // streamPolicy
    }

    @Test
    fun `legacy TOML without newer fields decodes to defaults`() {
        // Hand-rolled minimal TOML carrying only the required fields
        // (modelReference, factors, designSpec).  The newer
        // replications / streamPolicy / bundleRefs / tracingConfig
        // defaults must kick in cleanly so files saved before those
        // fields existed continue to load.
        val legacy = """
            [outputConfig]
            enableKSLDatabase = true
            enableReplicationCSV = false
            enableExperimentCSV = false
            reports = ["HTML"]

            [modelReference]
            type = "embedded"
            modelName = "MM1"

            [[factors]]
            name = "A"
            levels = [0.0, 1.0]

            [factors.binding]
            type = "control"
            controlKey = "A.value"

            [[factors]]
            name = "B"
            levels = [0.0, 1.0]

            [factors.binding]
            type = "control"
            controlKey = "B.value"

            [designSpec]
            type = "fullFactorial"
            centerPoints = 0
        """.trimIndent()
        val decoded = ExperimentConfigurationToml.decode(legacy)
        assertEquals(2, decoded.factors.size)
        assertIs<DesignSpec.FullFactorial>(decoded.designSpec)
        assertIs<ReplicationSpec.Uniform>(decoded.replications)
        assertEquals(10, (decoded.replications as ReplicationSpec.Uniform).replications)
        assertIs<StreamPolicy.Independent>(decoded.streamPolicy)
        assertTrue(decoded.bundleRefs.isEmpty())
        // Analysis name defaults to "Untitled" via OutputConfig's default.
        assertEquals("Untitled", decoded.outputConfig.analysisName)
        assertEquals(DatabasePolicy.OVERWRITE, decoded.outputConfig.databasePolicy)
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private fun baseConfig(
        designSpec: DesignSpec = DesignSpec.FullFactorial(),
        replications: ReplicationSpec = ReplicationSpec.Uniform(10),
        streamPolicy: StreamPolicy = StreamPolicy.Independent()
    ): ExperimentConfiguration = ExperimentConfiguration(
        outputConfig = OutputConfig(analysisName = "TestExperiment"),
        modelReference = ModelReference.Embedded("MM1"),
        factors = listOf(
            factor("A", listOf(0.0, 1.0)),
            factor("B", listOf(0.0, 1.0))
        ),
        designSpec = designSpec,
        replications = replications,
        streamPolicy = streamPolicy
    )

    private fun factor(name: String, levels: List<Double>): FactorSpec =
        FactorSpec(
            name = name,
            levels = levels,
            binding = ControlBinding.Control("$name.value")
        )
}
