/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.bookbundle

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/** Named, discoverable [ModelBuilderIfc] for the 'RQInventorySystem' book example; the build that [BookExamplesBundle] delegates to. */
class RQInventorySystemModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Child element name ("RQInventory") must differ from the Model name;
        // the (R,Q) controls/responses live on its inner "RQInventory:Item".
        val model = Model("RQInventorySystem", autoCSVReports = false)
        RQInventorySystem(model, reorderPt = 1, reorderQty = 2, name = "RQInventory")
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 10000.0
        model.numberOfReplications = 40
        model.curateCatalog {
            input("RQInventory:Item.initialReorderPoint") { displayName = "Reorder Point (R)"; unit = "units" }
            input("RQInventory:Item.initialReorderQty") { displayName = "Reorder Quantity (Q)"; unit = "units" }
            output("RQInventory:Item:TotalCost") { displayName = "Avg Total Cost"; unit = "\$/period" }
            output("RQInventory:Item:FillRate") { displayName = "Fill Rate" }
            output("RQInventory:Item:OrderingFrequency") { displayName = "Ordering Frequency" }
            output("RQInventory:Item:OnHand") { displayName = "Avg On-Hand Inventory"; unit = "units" }
            output("RQInventory:Item:AmountBackOrdered") { displayName = "Avg Backorders"; unit = "units" }
        }
        return model
    }
}
