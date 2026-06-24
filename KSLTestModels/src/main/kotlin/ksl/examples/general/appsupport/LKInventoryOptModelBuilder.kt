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

import ksl.examples.general.simopt.BuildLKModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Named, discoverable [ModelBuilderIfc] for the optimization-tuned LK (s,S) inventory
 * model. Wraps the shared [BuildLKModel] (so the build logic stays in one place) and
 * nominates the optimization-relevant inputs/outputs by key. Default `modelId` is
 * `LKInventoryOpt`.
 *
 * This is one of the two builds that [SimoptTestModelsBundle] delegates to. Its run
 * parameters (longer horizon, more replications) come from `BuildLKModel` and
 * intentionally differ from [LKInventoryModelBuilder]'s quicker preset.
 */
class LKInventoryOptModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = BuildLKModel.build(modelConfiguration, experimentRunParameters)
        model.curateCatalog {
            input("Inventory.reorderPoint") { displayName = "Reorder Point (s)"; unit = "units" }
            input("Inventory.orderQuantity") { displayName = "Order Quantity"; unit = "units" }
            output("TotalCost") { displayName = "Avg Total Cost"; unit = "\$/period" }
            output("OnHandLevel") { displayName = "Avg On-Hand Inventory"; unit = "units" }
        }
        return model
    }
}
