package ksl.app.config

import kotlinx.coroutines.runBlocking
import ksl.app.session.RunRequest
import ksl.app.session.RunResult
import ksl.app.session.Runner
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Acceptance tests for Phase 2: ksl.app.config persistable run configurations.
 *
 * Model: GIGcQueue M/M/1
 * - Model name: "MM1" → service-time RV registered as "MM1:ServiceTime" (mean = 0.5)
 * - GIGcQueue name: "MM1Queue" → numServers control key = "MM1Queue.numServers"
 */
class RunConfigurationTest {

    // ── Shared fixtures ───────────────────────────────────────────────────────

    /**
     * Provider that builds a fresh M/M/1 model with numServers=1.
     * The builder ignores [experimentRunParameters] — [RunConfiguration.buildModel]
     * applies them via [Model.changeRunParameters] after construction.
     */
    private val mm1Provider: ModelProviderIfc = MapModelProvider(
        "MM1",
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val model = Model("MM1", autoCSVReports = false)
                GIGcQueue(model, numServers = 1, name = "MM1Queue")
                return model
            }
        }
    )

    /**
     * Canonical [RunConfiguration]: 10 reps, 500 time-unit horizon, no overrides.
     * Run parameters are extracted from a reference model so all default values are
     * consistent with what KSL assigns.
     */
    private fun mm1Config(): RunConfiguration {
        val ref = Model("MM1", autoCSVReports = false)
        GIGcQueue(ref, numServers = 1, name = "MM1Queue")
        ref.numberOfReplications = 10
        ref.lengthOfReplication = 500.0
        return RunConfiguration(
            modelReference = ModelReference.ByProviderId("MM1"),
            experimentRunParameters = ref.extractRunParameters()
        )
    }

    // ── Test 1: JSON round-trip ───────────────────────────────────────────────

    /**
     * A [RunConfiguration] must survive JSON encode → decode with value equality.
     * Covers all default-valued optional fields (controls, rvOverrides, scenarios,
     * tracingConfig) because [RunConfigurationJson] sets `encodeDefaults = true`.
     */
    @Test
    fun `RunConfiguration round-trips through JSON`() {
        val config  = mm1Config()
        val decoded = RunConfigurationJson.decode(RunConfigurationJson.encode(config))
        assertEquals(config, decoded)
    }

    // ── Test 2: TOML round-trip ───────────────────────────────────────────────

    /**
     * A [RunConfiguration] must survive TOML encode → decode with value equality.
     * This exercises the `tomlkt` integration and the [ModelReference] sealed-class
     * discriminator (`type = "byProviderId"`).
     */
    @Test
    fun `RunConfiguration round-trips through TOML`() {
        val config  = mm1Config()
        val decoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        assertEquals(config, decoded)
    }

    // ── Test 3: buildModel applies ExperimentRunParameters ────────────────────

    /**
     * [RunConfiguration.buildModel] must call `model.changeRunParameters()` so that
     * the built model reflects the [ksl.controls.experiments.ExperimentRunParameters]
     * in the config, not the builder's internal defaults.
     */
    @Test
    fun `buildModel applies experimentRunParameters to the constructed model`() {
        val config = mm1Config().copy(
            experimentRunParameters = mm1Config().experimentRunParameters
                .copy(numberOfReplications = 7)
        )
        val model = config.buildModel(mm1Provider)
        assertEquals(7, model.numberOfReplications)
    }

    // ── Test 4: buildModel applies control overrides ──────────────────────────

    /**
     * When [RunConfiguration.controls] contains a numeric control override, [buildModel]
     * must apply it via `model.controls().importAll(...)` so the built model reflects
     * the override value.
     *
     * Control: `GIGcQueue.numServers` → key name `"MM1Queue.numServers"` (integer control)
     *
     * Strategy: build a reference model configured with `numServers = 2`, export its
     * controls via [ksl.controls.Controls.exportAll], and embed that export in the
     * [RunConfiguration].  [mm1Provider] always builds with `numServers = 1`, so after
     * [buildModel] applies the control export the model must have `numServers == 2`.
     */
    @Test
    fun `buildModel applies numeric control override (numServers)`() {
        // Reference model with numServers=2 → produces a ModelControlsExport where
        // the "MM1Queue.numServers" control data carries value=2.0.
        val refWith2 = Model("MM1", autoCSVReports = false)
        GIGcQueue(refWith2, numServers = 2, name = "MM1Queue")
        val controlsWithTwoServers = refWith2.controls().exportAll()

        val config = mm1Config().copy(controls = controlsWithTwoServers)
        val model  = config.buildModel(mm1Provider)

        // Read back via the controls map — avoids coupling the test to GIGcQueue's type.
        val numServersValue = model.controls().asMap()["MM1Queue.numServers"]
        assertEquals(2.0, numServersValue,
            "Expected numServers control to be 2.0 after override; got $numServersValue")
    }

    // ── Test 5: buildModel applies RV parameter overrides ────────────────────

    /**
     * When [RunConfiguration.rvOverrides] is non-empty, [buildModel] must apply each
     * override via [RVParameterSetter.changeParameters] so that the built model's
     * random variables reflect the new parameter values.
     *
     * RV: `GIGcQueue`'s service-time [ksl.modeling.variable.RandomVariable], registered
     * as `"MM1:ServiceTime"` (the model is named `"MM1"` and the RV name is
     * `"${parent.name}:ServiceTime"`).  The default mean is 0.5; the override sets it to 2.0.
     */
    @Test
    fun `buildModel applies RV parameter override (service-time mean)`() {
        val config = mm1Config().copy(
            rvOverrides = listOf(
                RVParameterOverride(rvName = "MM1:ServiceTime", paramName = "mean", value = 2.0)
            )
        )
        val model = config.buildModel(mm1Provider)

        val setter   = RVParameterSetter(model)
        val rvParams = setter.rvParameters("MM1:ServiceTime")
        assertEquals(2.0, rvParams.doubleParameter("mean"),
            "Expected service-time mean to be 2.0 after override")
    }

    // ── Test 6: full acceptance — TOML file → Runner → Completed ─────────────

    /**
     * Full end-to-end acceptance test for Phase 2:
     *
     * 1. Encode a [RunConfiguration] to TOML text.
     * 2. Decode back to a [RunConfiguration].
     * 3. Call [RunConfiguration.buildModel] to produce a configured [Model].
     * 4. Submit to [Runner] and await the [RunResult].
     *
     * Expected outcome: [RunResult.Completed] with
     * `completedReplications == requestedReplications`.
     */
    @Test
    fun `TOML round-trip followed by Runner submit produces RunResult Completed`() = runBlocking {
        val config  = mm1Config()
        val decoded = RunConfigurationToml.decode(RunConfigurationToml.encode(config))
        val model   = decoded.buildModel(mm1Provider)

        val runner = Runner()
        val handle = runner.submit(RunRequest.SingleRun(model), scope = this)
        val result = handle.result.await()

        assertIs<RunResult.Completed>(result)
        assertEquals(
            config.experimentRunParameters.numberOfReplications,
            result.summary.completedReplications,
            "All requested replications must complete"
        )
    }
}
