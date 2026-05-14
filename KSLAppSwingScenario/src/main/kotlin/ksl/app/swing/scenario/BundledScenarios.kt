package ksl.app.swing.scenario

import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RVParameterOverride
import ksl.app.config.ScenarioSpec
import ksl.examples.general.appsupport.LKInventoryBundle
import ksl.examples.general.appsupport.MM1Bundle
import ksl.simulation.ModelProviderIfc

/**
 * Pre-defined scenario sweeps for the bundled models.
 *
 * Hardcoded for the proof-of-concept rather than user-editable: the
 * intent here is to demonstrate that the GUI wiring of `RunSpec.Scenarios`
 * works end-to-end through `KSLAppSession`.  A scenario editor is a
 * Phase 6 follow-up.
 *
 * Each `ScenarioSpec` is self-contained: it carries its own
 * `(bundleId, modelId)`-flavoured [ModelReference], plus its own
 * sparse [ExperimentRunOverrides] / control / RV-parameter overrides.
 * The `provider` parameter is no longer needed to extract baseline run
 * parameters — the orchestrator pulls those from the model at submit
 * time and overlays the spec's overrides.
 *
 * - **MM1** varies the service-time mean across three load levels.
 * - **LK Inventory** varies the replication length and warm-up across
 *   two scenarios.
 */
internal object BundledScenarios {

    fun forModel(modelId: String, @Suppress("UNUSED_PARAMETER") provider: ModelProviderIfc): List<ScenarioSpec> =
        when (modelId) {
            MM1Bundle.MODEL_ID -> mm1Scenarios()
            LKInventoryBundle.MODEL_ID -> lkScenarios()
            else -> emptyList()
        }

    private fun mm1Scenarios(): List<ScenarioSpec> = listOf(
        ScenarioSpec(
            name = "LowLoad",
            modelReference = ModelReference.ByProviderId(MM1Bundle.MODEL_ID),
            rvOverrides = listOf(
                RVParameterOverride("MM1:ServiceTime", "mean", 0.3)
            )
        ),
        ScenarioSpec(
            name = "MedLoad",
            modelReference = ModelReference.ByProviderId(MM1Bundle.MODEL_ID),
            rvOverrides = listOf(
                RVParameterOverride("MM1:ServiceTime", "mean", 0.5)
            )
        ),
        ScenarioSpec(
            name = "HighLoad",
            modelReference = ModelReference.ByProviderId(MM1Bundle.MODEL_ID),
            rvOverrides = listOf(
                RVParameterOverride("MM1:ServiceTime", "mean", 0.7)
            )
        )
    )

    private fun lkScenarios(): List<ScenarioSpec> = listOf(
        ScenarioSpec(
            name = "ShortRun",
            modelReference = ModelReference.ByProviderId(LKInventoryBundle.MODEL_ID),
            runOverrides = ExperimentRunOverrides(
                lengthOfReplication = 120.0,
                lengthOfReplicationWarmUp = 20.0
            )
        ),
        ScenarioSpec(
            name = "LongRun",
            modelReference = ModelReference.ByProviderId(LKInventoryBundle.MODEL_ID),
            runOverrides = ExperimentRunOverrides(
                lengthOfReplication = 240.0,
                lengthOfReplicationWarmUp = 40.0
            )
        )
    )
}
