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
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Named, discoverable [ModelBuilderIfc] for the 'RQInventorySystem' book example: the (r, Q) model of
 * the book's chapters 7 and 11, with its demand (3.6 per month), lead time (0.5 months) and costs
 * (0.15 per order, 0.25 per unit per month held, 1.75 per unit per month back ordered). Without them
 * the model runs at its own defaults, which are not the book's.
 */
class RQInventorySystemModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Child element name ("RQInventory") must differ from the Model name;
        // the (R,Q) controls/responses live on its inner "RQInventory:Item".
        val model = Model("RQInventorySystem", autoCSVReports = false)
        val rqModel = RQInventorySystem(model, reorderPt = 1, reorderQty = 2, name = "RQInventory")
        rqModel.initialOnHand = 0
        rqModel.demandGenerator.setInitialEventTimeProcesses(ExponentialRV(1.0 / 3.6))
        rqModel.leadTime.initialRandomSource = ConstantRV(0.5)
        rqModel.costPerOrder = 0.15
        rqModel.unitHoldingCost = 0.25
        rqModel.unitBackorderCost = 1.75
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
