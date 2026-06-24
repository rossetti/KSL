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

import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Named, discoverable [ModelBuilderIfc] for the textbook Hadley/Whitin (s,S) inventory
 * model, tuned for Single / Scenario / Experiment workflows (a quick 10-replication
 * cycle), nominating the (s,S) policy knobs and headline cost outputs via
 * `curateCatalog`. Default `modelId` is `LKInventory`.
 *
 * This is the build that [LKInventoryBundle] delegates to.
 */
class LKInventoryModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("LKInventory", autoCSVReports = false)
        val inv = LKInventoryModel(model, "Inventory")
        model.numberOfReplications = 10
        model.lengthOfReplication = 120.0
        model.lengthOfReplicationWarmUp = 20.0
        // Author-nominated catalog of the (s,S) policy knobs and headline
        // cost outputs, declared on the unedited LKInventoryModel.
        model.curateCatalog {
            input(inv, LKInventoryModel::reorderPoint) { displayName = "Reorder Point (s)"; unit = "units" }
            input(inv, LKInventoryModel::orderQuantity) { displayName = "Order Quantity"; unit = "units" }
            input(inv, LKInventoryModel::initialInventoryLevel) { displayName = "Initial Inventory"; unit = "units" }
            output(inv.avgTotalCost) { displayName = "Avg Total Cost"; unit = "\$/period" }
            output(inv.posInventoryLevel) { displayName = "Avg On-Hand Inventory"; unit = "units" }
            output(inv.negInventoryLevel) { displayName = "Avg Backorders"; unit = "units" }
        }
        return model
    }
}
