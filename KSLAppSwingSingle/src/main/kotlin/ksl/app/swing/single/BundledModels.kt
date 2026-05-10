package ksl.app.swing.single

import ksl.examples.book.appendixD.GIGcQueue
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc

/**
 * Small fixed registry of model providers bundled with the reference Swing
 * app, so the GUI has something concrete to run out of the box.
 *
 * The intent of this object is **not** to be a general model browser — it's
 * a copy-this-and-extend hook for users who want to plug their own model
 * into the reference app.  Add an entry to [provider] for any model that
 * implements [ModelBuilderIfc]; the GUI's model picker reads
 * [availableModelIds] to populate its dropdown.
 *
 * Generic loading of models from arbitrary JAR files at runtime is not
 * provided here; that is a deliberate Phase 6 follow-up.
 */
internal object BundledModels {

    /** Stable identifier for the M/M/1 example queue. */
    const val MM1_ID: String = "MM1"

    /** Stable identifier for the textbook LK inventory model. */
    const val LK_INVENTORY_ID: String = "LKInventory"

    /** Display order for the model-picker UI. */
    val availableModelIds: List<String> = listOf(MM1_ID, LK_INVENTORY_ID)

    /** The single [ModelProviderIfc] passed to `KSLAppSession`. */
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
            // Note: child element name must not equal the Model's own name,
            // or it will collide as a duplicate ModelElement at the root.
            val model = Model(MM1_ID, autoCSVReports = false)
            GIGcQueue(model, numServers = 1, name = "MM1Queue")
            // Default run parameters; the GUI overrides these via the form
            // before submission.
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
