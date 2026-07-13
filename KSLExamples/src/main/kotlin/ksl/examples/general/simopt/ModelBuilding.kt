package ksl.examples.general.simopt

import ksl.examples.book.chapter7.RQInventorySystem
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV


fun selectBuilder(modelIdentifier: String): ModelBuilderIfc {
    return when (modelIdentifier) {
        "LKInventoryModel" -> BuildLKModel
        "RQInventoryModel" -> BuildRQModel
        else -> {
            throw Exception("Unknown model identifier")
        }
    }
}

/**
 * A [ModelBuilderIfc] that builds the (r, Q) inventory model (an [RQInventorySystem]) configured for
 * simulation optimization — fixed demand and lead-time settings, with the reorder point and quantity as
 * the decision inputs. Paired with [BuildLKModel] behind `selectBuilder`.
 */
object BuildRQModel : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val reorderQty: Int = 2
        val reorderPoint: Int = 1
        val model = Model("RQInventoryModel")
        val rqModel = RQInventorySystem(model, reorderPoint, reorderQty, "Inventory")
        rqModel.initialOnHand = 0
        rqModel.demandGenerator.initialTimeBtwEvents = ExponentialRV(1.0 / 3.6)
        rqModel.leadTime.initialRandomSource = ConstantRV(0.5)
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 10000.0
        model.numberOfReplications = 40
        return model
    }
}

/**
 * A [ModelBuilderIfc] that builds the Law & Kelton (s, S) inventory model (an [LKInventoryModel])
 * configured for simulation optimization, with the order quantity and reorder point as the decision
 * inputs. Paired with [BuildRQModel] behind `selectBuilder`.
 */
object BuildLKModel : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("LKInventoryModel")
        val lkInventoryModel = LKInventoryModel(model, "Inventory")
        model.lengthOfReplication = 120.0
        model.numberOfReplications = 1000
        model.lengthOfReplicationWarmUp = 20.0
        lkInventoryModel.orderQuantity = 1
        lkInventoryModel.reorderPoint = 2
        return model
    }
}