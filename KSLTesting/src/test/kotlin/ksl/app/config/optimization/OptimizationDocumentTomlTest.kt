package ksl.app.config.optimization

import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.RVParameterOverride
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase O0 acceptance tests for the persistable additions to
 * [OptimizationRunConfiguration]:
 *
 * - the new top-level [OptimizationOutputConfig] field on the document,
 * - the new top-level [SolverTrackingSpec] field on the document,
 * - the `explicitNulls = false` policy on the TOML codec,
 * - the document-header banner prepended by
 *   [OptimizationRunConfigurationToml.encode],
 * - the `init`-block invariants on [SolverTrackingSpec].
 *
 * Existing tests in `OptimizationRunConfigurationTest` already pin the
 * round-trip semantics of every other spec type; this file focuses on
 * the O0 deltas.
 */
class OptimizationDocumentTomlTest {

    // ── 1. Default document round-trips through TOML ─────────────────────────

    @Test
    fun `default document round-trips through TOML`() {
        val config = defaultConfig()
        val decoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(config)
        )
        assertEquals(config, decoded)
        assertEquals(OptimizationOutputConfig(), decoded.output)
        assertEquals(SolverTrackingSpec(), decoded.tracking)
    }

    // ── 2. Populated document round-trips through TOML ───────────────────────

    @Test
    fun `populated document round-trips through TOML`() {
        val config = defaultConfig(
            output = OptimizationOutputConfig(
                analysisName = "RSM_inventory",
                outputDirectory = "/tmp/ksl-workspace/output/RSM_inventory"
            ),
            tracking = SolverTrackingSpec(
                enableCsvTrace = true,
                csvFileName = "CE_trace",
                enableConsoleTrace = true,
                experimentLabel = "Run1"
            )
        )
        val decoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(config)
        )
        assertEquals(config, decoded)
    }

    // ── 3. Document-header banner present in encoded output ──────────────────

    @Test
    fun `document header banner is present in encoded output`() {
        val encoded = OptimizationRunConfigurationToml.encode(defaultConfig())
        assertTrue(
            encoded.startsWith("# ───"),
            "encoded TOML must begin with the document-header banner; was:\n${encoded.take(60)}"
        )
        assertTrue(
            encoded.contains("KSL Simulation-Optimization Configuration"),
            "banner must include the document title"
        )
        // The banner must precede the [output] table.
        val headerEnd = encoded.indexOf("[output]")
        val firstHash = encoded.indexOf("# ")
        assertTrue(headerEnd > firstHash && firstHash == 0)
    }

    // ── 4. Legacy TOML (no [output], no [tracking]) decodes with defaults ────

    @Test
    fun `decode tolerates legacy document missing output and tracking fields`() {
        // Build a TOML by encoding the full doc, then strip the [output] and
        // [tracking] tables to simulate a hand-written document predating
        // those fields.
        val baseline = defaultConfig()
        val encoded = OptimizationRunConfigurationToml.encode(baseline)
        val withoutOutputAndTracking = encoded
            .lineSequence()
            .filterNot { it.startsWith("[output]") || it.startsWith("[tracking]") }
            .filterNot {
                // drop the field lines that belong to [output] or [tracking]
                // (analysisName / outputDirectory / enableCsvTrace / ...).
                it.startsWith("analysisName") ||
                    it.startsWith("outputDirectory") ||
                    it.startsWith("enableCsvTrace") ||
                    it.startsWith("csvFileName") ||
                    it.startsWith("enableConsoleTrace") ||
                    it.startsWith("experimentLabel")
            }
            .joinToString("\n")

        val decoded = OptimizationRunConfigurationToml.decode(withoutOutputAndTracking)
        assertEquals(OptimizationOutputConfig(), decoded.output)
        assertEquals(SolverTrackingSpec(), decoded.tracking)
    }

    // ── 5. explicitNulls = false suppresses null fields ──────────────────────

    @Test
    fun `null outputDirectory is omitted from encoded TOML when explicitNulls is false`() {
        val config = defaultConfig(
            output = OptimizationOutputConfig(
                analysisName = "Demo",
                outputDirectory = null
            )
        )
        val encoded = OptimizationRunConfigurationToml.encode(config)
        // Should NOT contain a line like `outputDirectory = null`.
        assertTrue(
            !encoded.lineSequence().any { it.trim() == "outputDirectory = null" },
            "null outputDirectory should be omitted, not rendered as 'outputDirectory = null'"
        )
    }

    // ── 6. SolverTrackingSpec init rejects blank experimentLabel ─────────────

    @Test
    fun `SolverTrackingSpec init rejects blank experimentLabel`() {
        assertFailsWith<IllegalArgumentException> {
            SolverTrackingSpec(experimentLabel = "")
        }
        assertFailsWith<IllegalArgumentException> {
            SolverTrackingSpec(experimentLabel = "   ")
        }
    }

    // ── 7. SolverTrackingSpec init rejects blank csvFileName when non-null ───

    @Test
    fun `SolverTrackingSpec init rejects blank csvFileName when non-null`() {
        assertFailsWith<IllegalArgumentException> {
            SolverTrackingSpec(csvFileName = "")
        }
        assertFailsWith<IllegalArgumentException> {
            SolverTrackingSpec(csvFileName = "   ")
        }
    }

    // ── 8. SolverTrackingSpec accepts null csvFileName ───────────────────────

    @Test
    fun `SolverTrackingSpec accepts null csvFileName`() {
        // Should not throw — null means 'host picks a default file stem'.
        val spec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = null)
        assertEquals(null, spec.csvFileName)
    }

    // ── shared fixture builders ──────────────────────────────────────────────

    private fun defaultConfig(
        output: OptimizationOutputConfig = OptimizationOutputConfig(),
        tracking: SolverTrackingSpec = SolverTrackingSpec()
    ): OptimizationRunConfiguration =
        OptimizationRunConfiguration(
            output = output,
            model = defaultModel(),
            problem = defaultProblem(),
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 10,
                replicationsPerEvaluation = 3
            ),
            evaluation = EvaluationSpec(),
            tracking = tracking
        )

    private fun defaultProblem(): OptimizationProblemSpec =
        OptimizationProblemSpec(
            problemName = "Demo",
            objectiveResponseName = "TotalCost",
            inputs = listOf(
                OptimizationInputSpec(
                    name = "Inventory.orderQuantity",
                    lowerBound = 1.0,
                    upperBound = 100.0,
                    granularity = 1.0
                )
            )
        )

    private fun defaultModel(): ModelRunTemplate =
        ModelRunTemplate(
            modelReference = ModelReference.ByProviderId("MM1"),
            runParameters = runParameters(),
            rvOverrides = listOf(
                RVParameterOverride(
                    rvName = "MM1:ServiceTime",
                    paramName = "mean",
                    value = 2.0
                )
            )
        )

    private fun runParameters() =
        Model("MM1", autoCSVReports = false).also { model ->
            GIGcQueue(model, numServers = 1, name = "MM1Queue")
            model.numberOfReplications = 3
            model.lengthOfReplication = 100.0
            model.lengthOfReplicationWarmUp = 10.0
        }.extractRunParameters()
}
