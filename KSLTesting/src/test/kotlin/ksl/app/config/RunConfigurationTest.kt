package ksl.app.config

import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Round-trip tests for the reshaped [RunConfiguration] (Phase 6B
 * substrate-prep).  Document-orchestration behavior is covered by
 * `ScenarioOrchestratorTest` and `SingleRunOrchestratorTest`; this file
 * focuses on the data type and its codecs.
 *
 * Notes on the reshape:
 *  - `RunConfiguration` no longer carries `modelReference` or
 *    `experimentRunParameters` at the document level.  Every scenario
 *    is self-contained.
 *  - The `buildModel(provider)` method is gone — the orchestrator
 *    builds each scenario's model independently from
 *    `spec.modelReference`.
 *  - Override semantics for run parameters now mirror controls /
 *    RV-overrides: partial, per-key, applied on top of the model's
 *    intrinsic defaults at submit time.
 */
class RunConfigurationTest {

    private fun mm1Scenario(scenarioName: String = "MM1_default"): ScenarioSpec {
        // Build a reference model just to capture its defaults verbatim.
        // Folding those defaults into runOverrides exercises every nullable
        // override field through the codecs.
        val ref = Model("MM1", autoCSVReports = false)
        GIGcQueue(ref, numServers = 1, name = "MM1Queue")
        ref.numberOfReplications = 10
        ref.lengthOfReplication = 500.0
        return ScenarioSpec(
            name = scenarioName,
            modelReference = ModelReference.ByProviderId("MM1"),
            runOverrides = ref.extractRunParameters().toOverrides()
        )
    }

    private fun mm1Config(): RunConfiguration =
        RunConfiguration(scenarios = listOf(mm1Scenario()))

    @Test
    fun `RunConfiguration round-trips through JSON`() {
        val config  = mm1Config()
        val decoded = RunConfigurationJson.decode(RunConfigurationJson.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun `RunConfiguration round-trips through TOML`() {
        val config  = mm1Config()
        val decoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun `RunConfiguration with multiple scenarios round-trips through JSON`() {
        val config = RunConfiguration(
            scenarios = listOf(
                mm1Scenario("LowLoad"),
                mm1Scenario("HighLoad").copy(
                    rvOverrides = listOf(
                        RVParameterOverride("MM1:ServiceTime", "mean", 0.7)
                    )
                )
            )
        )
        val decoded = RunConfigurationJson.decode(RunConfigurationJson.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun `RunConfiguration with bundleRefs round-trips through TOML`() {
        val config = RunConfiguration(
            bundleRefs = listOf(
                BundleRef(paths = listOf("./bundles/mm1.jar"), bundleId = "edu.example.mm1"),
                BundleRef(paths = emptyList(), bundleId = "edu.example.lk-inventory")
            )
        )
        val decoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun `empty RunConfiguration round-trips through both codecs`() {
        val config = RunConfiguration()
        val jsonDecoded = RunConfigurationJson.decode(RunConfigurationJson.encode(config))
        val tomlDecoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
    }

    @Test
    fun `duplicate scenario names are rejected at construction`() {
        // Substrate invariant carried over from the Phase-2 shape.
        try {
            RunConfiguration(
                scenarios = listOf(
                    mm1Scenario("same"),
                    mm1Scenario("same")
                )
            )
            kotlin.test.fail("Expected IllegalArgumentException for duplicate scenario names")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `duplicate bundleRef bundleIds are rejected at construction`() {
        try {
            RunConfiguration(
                bundleRefs = listOf(
                    BundleRef(paths = listOf("a.jar"), bundleId = "edu.example.dup"),
                    BundleRef(paths = listOf("b.jar"), bundleId = "edu.example.dup")
                )
            )
            kotlin.test.fail("Expected IllegalArgumentException for duplicate bundleIds")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── OutputConfig field on RunConfiguration ───────────────────────────────

    @Test
    fun `RunConfiguration carries default OutputConfig when not specified`() {
        val config = RunConfiguration()
        assertEquals(OutputConfig(), config.outputConfig)
    }

    @Test
    fun `RunConfiguration with default OutputConfig round-trips through both codecs`() {
        val config = mm1Config()
        val jsonDecoded = RunConfigurationJson.decode(RunConfigurationJson.encode(config))
        val tomlDecoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
        assertEquals(OutputConfig(), jsonDecoded.outputConfig)
        assertEquals(OutputConfig(), tomlDecoded.outputConfig)
    }

    @Test
    fun `RunConfiguration with non-default OutputConfig round-trips through JSON`() {
        val config = mm1Config().copy(
            outputConfig = OutputConfig(
                enableKSLDatabase = true,
                enableCSVExport = true,
                reports = setOf(ReportFormat.HTML, ReportFormat.MARKDOWN)
            )
        )
        val decoded = RunConfigurationJson.decode(RunConfigurationJson.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun `RunConfiguration with non-default OutputConfig round-trips through TOML`() {
        val config = mm1Config().copy(
            outputConfig = OutputConfig(
                enableKSLDatabase = true,
                enableCSVExport = true,
                reports = setOf(ReportFormat.HTML, ReportFormat.MARKDOWN)
            )
        )
        val decoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun `RunConfiguration with empty reports set round-trips through both codecs`() {
        val config = mm1Config().copy(outputConfig = OutputConfig(reports = emptySet()))
        val jsonDecoded = RunConfigurationJson.decode(RunConfigurationJson.encode(config))
        val tomlDecoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
        kotlin.test.assertTrue(jsonDecoded.outputConfig.reports.isEmpty())
        kotlin.test.assertTrue(tomlDecoded.outputConfig.reports.isEmpty())
    }

    // ── ExecutionMode field on RunConfiguration ──────────────────────────────

    @Test
    fun `RunConfiguration defaults to SEQUENTIAL execution mode`() {
        val config = RunConfiguration()
        assertEquals(ExecutionMode.SEQUENTIAL, config.executionMode)
    }

    @Test
    fun `RunConfiguration with default ExecutionMode round-trips through both codecs`() {
        val config = mm1Config()
        val jsonDecoded = RunConfigurationJson.decode(RunConfigurationJson.encode(config))
        val tomlDecoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
        assertEquals(ExecutionMode.SEQUENTIAL, jsonDecoded.executionMode)
        assertEquals(ExecutionMode.SEQUENTIAL, tomlDecoded.executionMode)
    }

    @Test
    fun `RunConfiguration with CONCURRENT execution mode round-trips through JSON`() {
        val config = mm1Config().copy(executionMode = ExecutionMode.CONCURRENT)
        val decoded = RunConfigurationJson.decode(RunConfigurationJson.encode(config))
        assertEquals(config, decoded)
        assertEquals(ExecutionMode.CONCURRENT, decoded.executionMode)
    }

    @Test
    fun `RunConfiguration with CONCURRENT execution mode round-trips through TOML`() {
        val config = mm1Config().copy(executionMode = ExecutionMode.CONCURRENT)
        val decoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        assertEquals(config, decoded)
        assertEquals(ExecutionMode.CONCURRENT, decoded.executionMode)
    }
}
