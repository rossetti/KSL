/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.appsupport

import ksl.examples.general.simopt.BuildRQModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Named, discoverable [ModelBuilderIfc] for the (R,Q) inventory simopt fixture. Wraps
 * the shared [BuildRQModel]; the (R,Q) controls/responses live on the child element
 * `Inventory:Item`, so the catalog keys carry that prefix. Default `modelId` is
 * `RQInventoryOpt`.
 *
 * This is one of the two builds that [SimoptTestModelsBundle] delegates to.
 */
class RQInventoryOptModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = BuildRQModel.build(modelConfiguration, experimentRunParameters)
        model.curateCatalog {
            input("Inventory:Item.initialReorderPoint") { displayName = "Reorder Point (R)"; unit = "units" }
            input("Inventory:Item.initialReorderQty") { displayName = "Order Quantity (Q)"; unit = "units" }
            output("Inventory:Item:TotalCost") { displayName = "Avg Total Cost"; unit = "\$/period" }
            output("Inventory:Item:OrderingFrequency") { displayName = "Ordering Frequency" }
        }
        return model
    }
}
