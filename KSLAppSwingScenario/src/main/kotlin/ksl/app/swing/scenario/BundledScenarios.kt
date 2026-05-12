package ksl.app.swing.scenario

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
 * - **MM1** varies the service-time mean across three load levels.  The
 *   `MM1:ServiceTime` RV name follows the `${parent.name}:ServiceTime`
 *   convention inside `GIGcQueue`, where `parent` is the surrounding
 *   `Model` named `MM1`.
 * - **LK Inventory** varies the replication length and warm-up across
 *   two scenarios.  Domain-level variation (e.g. order-quantity sweep)
 *   would require constructing `ModelControlsExport` instances and is
 *   left for a Phase 6 follow-up — the run-parameter sweep is enough to
 *   exercise the orchestrator wiring on this model.
 */
internal object BundledScenarios {

    fun forModel(modelId: String, provider: ModelProviderIfc): List<ScenarioSpec> = when (modelId) {
        MM1Bundle.MODEL_ID -> mm1Scenarios(provider)
        LKInventoryBundle.MODEL_ID -> lkScenarios(provider)
        else -> emptyList()
    }

    private fun mm1Scenarios(provider: ModelProviderIfc): List<ScenarioSpec> {
        val baseParams = provider.provideModel(MM1Bundle.MODEL_ID).extractRunParameters()
        return listOf(
            ScenarioSpec(
                name = "LowLoad",
                runParameters = baseParams,
                rvOverrides = listOf(
                    RVParameterOverride("MM1:ServiceTime", "mean", 0.3)
                )
            ),
            ScenarioSpec(
                name = "MedLoad",
                runParameters = baseParams,
                rvOverrides = listOf(
                    RVParameterOverride("MM1:ServiceTime", "mean", 0.5)
                )
            ),
            ScenarioSpec(
                name = "HighLoad",
                runParameters = baseParams,
                rvOverrides = listOf(
                    RVParameterOverride("MM1:ServiceTime", "mean", 0.7)
                )
            )
        )
    }

    private fun lkScenarios(provider: ModelProviderIfc): List<ScenarioSpec> {
        val baseParams = provider.provideModel(LKInventoryBundle.MODEL_ID).extractRunParameters()
        return listOf(
            ScenarioSpec(
                name = "ShortRun",
                runParameters = baseParams.copy(
                    lengthOfReplication = 120.0,
                    lengthOfReplicationWarmUp = 20.0
                )
            ),
            ScenarioSpec(
                name = "LongRun",
                runParameters = baseParams.copy(
                    lengthOfReplication = 240.0,
                    lengthOfReplicationWarmUp = 40.0
                )
            )
        )
    }
}
