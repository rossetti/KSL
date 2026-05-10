package ksl.app.swing.simopt

import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.SolverSpec
import ksl.examples.general.appsupport.BundledModelProviders

/**
 * Pre-built simulation-optimization configurations for the bundled models
 * supported by this reference app.
 *
 * Hardcoded for the proof-of-concept — [OptimizationRunConfiguration] is
 * too rich (problem definition, input bounds, solver choice and tuning)
 * to wrap in a v1 GUI editor.  The proof point here is the orchestrator
 * wiring through [ksl.app.RunSpec.Optimization], not optimization
 * authoring tooling.
 *
 * Supported models:
 * - **LK Inventory**: minimize `Inventory.TotalCost` over
 *   `Inventory.orderQuantity` and `Inventory.reorderPoint` each in
 *   [1, 100] (integer granularity), solved with a small Stochastic Hill
 *   Climber so a single run completes in seconds.
 *
 * MM1 is intentionally not supported here even though
 * [BundledModelProviders] lists it: keeping the picker narrative tight
 * (one well-suited bundled problem) matches the Experiment module.
 */
internal object BundledOptimizations {

    /** Models for which a bundled optimization exists.  The model picker
     *  in this module uses this list instead of
     *  [BundledModelProviders.availableModelIds]. */
    val supportedModelIds: List<String> = listOf(BundledModelProviders.LK_INVENTORY_ID)

    fun forModel(modelId: String): OptimizationRunConfiguration = when (modelId) {
        BundledModelProviders.LK_INVENTORY_ID -> lkOptimization()
        else -> throw IllegalArgumentException("No bundled optimization for model id: $modelId")
    }

    private fun lkOptimization(): OptimizationRunConfiguration {
        val modelId = BundledModelProviders.LK_INVENTORY_ID
        val baseParams = BundledModelProviders.provider.provideModel(modelId).extractRunParameters()
        val template = ModelRunTemplate(
            modelReference = ModelReference.ByProviderId(modelId),
            runParameters = baseParams
        )
        val problem = OptimizationProblemSpec(
            problemName = "LKInventoryOptimization",
            modelIdentifier = modelId,
            objectiveResponseName = "TotalCost",
            inputs = listOf(
                OptimizationInputSpec(
                    "Inventory.orderQuantity",
                    lowerBound = 1.0, upperBound = 100.0, granularity = 1.0
                ),
                OptimizationInputSpec(
                    "Inventory.reorderPoint",
                    lowerBound = 1.0, upperBound = 100.0, granularity = 1.0
                )
            )
        )
        val solver = SolverSpec.StochasticHillClimbing(
            maxIterations = 5,
            replicationsPerEvaluation = 3
        )
        return OptimizationRunConfiguration(template, problem, solver)
    }
}
