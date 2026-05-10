package ksl.app.swing.scenario

import ksl.examples.book.appendixD.GIGcQueue
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc

/**
 * Small fixed registry of model providers bundled with the scenario-sweep
 * reference Swing app.
 *
 * **Cross-module duplication note:** this object is currently a near-copy
 * of `ksl.app.swing.single.BundledModels`.  The umbrella Phase 6 plan says
 * a shared `KSLAppSwingCommon` module should be created "only if patterns
 * repeat organically — do not pre-design it."  We're at two instances of
 * organic repetition; the third (when `KSLAppSwingExperiment` lands) is
 * the natural extraction signal.
 */
internal object BundledModels {

    const val MM1_ID: String = "MM1"
    const val LK_INVENTORY_ID: String = "LKInventory"

    val availableModelIds: List<String> = listOf(MM1_ID, LK_INVENTORY_ID)

    val provider: ModelProviderIfc = MapModelProvider(
        mutableMapOf(
            MM1_ID to mm1Builder(),
            LK_INVENTORY_ID to lkBuilder()
        )
    )

    private fun mm1Builder(): ModelBuilderIfc = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(MM1_ID, autoCSVReports = false)
            GIGcQueue(model, numServers = 1, name = "MM1Queue")
            model.numberOfReplications = 30
            model.lengthOfReplication = 500.0
            model.lengthOfReplicationWarmUp = 50.0
            return model
        }
    }

    private fun lkBuilder(): ModelBuilderIfc = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(LK_INVENTORY_ID, autoCSVReports = false)
            LKInventoryModel(model, "Inventory")
            model.numberOfReplications = 10
            model.lengthOfReplication = 120.0
            model.lengthOfReplicationWarmUp = 20.0
            return model
        }
    }
}
