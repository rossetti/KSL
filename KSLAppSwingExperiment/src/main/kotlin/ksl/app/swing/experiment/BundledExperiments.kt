package ksl.app.swing.experiment

import ksl.app.bundle.BundleModelProvider
import ksl.controls.experiments.Factor
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.general.appsupport.LKInventoryBundle

/**
 * Pre-built designed experiments for the bundled models supported by
 * this reference app.
 *
 * Hardcoded for the proof-of-concept — designed experiments are too
 * complex to wrap in a v1 GUI editor, and the proof point here is the
 * orchestrator wiring, not the experiment author's tooling.
 *
 * Each call to [forModel] constructs a fresh [ParallelDesignedExperiment]
 * (the type owns mutable state like its `KSLDatabase`).
 *
 * Supported models:
 * - **LK Inventory**: 2-factor 2-level full factorial (4 design points)
 *   varying `Inventory.orderQuantity` between 10 and 30 and
 *   `Inventory.reorderPoint` between 10 and 30.
 *
 * MM1 (`GIGcQueue`) is intentionally not supported here even though it
 * has one `@KSLControl`-marked property (`numServers`).  The KSL engine
 * requires `FactorialDesign` to have at least 2 factors, so a 1-factor
 * "degenerate" design is rejected by the constraint.  Bundled
 * experiments therefore restrict the model list to LK only.
 */
internal object BundledExperiments {

    /** Models for which a bundled experiment exists.  The model picker in
     *  this module uses this list rather than the full set of bundled
     *  model identifiers exposed by the `BundleModelProvider`. */
    val supportedModelIds: List<String> = listOf(LKInventoryBundle.MODEL_ID)

    fun forModel(modelId: String, provider: BundleModelProvider): ParallelDesignedExperiment = when (modelId) {
        LKInventoryBundle.MODEL_ID -> lkExperiment(provider)
        else -> throw IllegalArgumentException("No bundled experiment for model id: $modelId")
    }

    private fun lkExperiment(provider: BundleModelProvider): ParallelDesignedExperiment {
        val orderQty = TwoLevelFactor("OrderQuantity", low = 10.0, high = 30.0)
        val reorderPt = TwoLevelFactor("ReorderPoint", low = 10.0, high = 30.0)
        val design = TwoLevelFactorialDesign(setOf(orderQty, reorderPt))
        val settings: Map<Factor, String> = mapOf(
            orderQty to "Inventory.orderQuantity",
            reorderPt to "Inventory.reorderPoint"
        )
        return ParallelDesignedExperiment(
            name = "LKInventory_OrderQtyReorderPtSweep",
            modelBuilder = provider.builderFor(LKInventoryBundle.MODEL_ID),
            factorSettings = settings,
            design = design
        )
    }
}
