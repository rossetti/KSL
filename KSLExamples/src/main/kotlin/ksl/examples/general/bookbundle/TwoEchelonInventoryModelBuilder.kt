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

import ksl.examples.general.models.inventory.BuildTwoEchelonModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/** Named, discoverable [ModelBuilderIfc] for the 'TwoEchelonInventory' book example; the build that [BookExamplesBundle] delegates to. */
class TwoEchelonInventoryModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Reuse the existing general builder, then nominate the
        // optimization-relevant catalog.  Keys match the
        // constrained/unconstrainedTwoEchelonProblemDefinition() functions.
        val model = BuildTwoEchelonModel.build(modelConfiguration, experimentRunParameters)
        model.curateCatalog {
            input("TwoEchelon:DCInventory.initialReorderPoint") { displayName = "DC Reorder Point (R)"; unit = "units" }
            input("TwoEchelon:DCInventory.initialReorderQty") { displayName = "DC Reorder Quantity (Q)"; unit = "units" }
            input("TwoEchelon:BaseInventory.initialReorderPoint") { displayName = "Base Reorder Point (R)"; unit = "units" }
            input("TwoEchelon:BaseInventory.initialReorderQty") { displayName = "Base Reorder Quantity (Q)"; unit = "units" }
            output("TwoEchelon:TotalCost") { displayName = "Avg Total Cost"; unit = "\$/period" }
            output("TwoEchelon:TotalOrderingAndHoldingCost") { displayName = "Avg Ordering + Holding Cost"; unit = "\$/period" }
            output("TwoEchelon:DCInventory:ItemA:FillRate") { displayName = "DC Fill Rate" }
            output("TwoEchelon:BaseInventory:ItemA:FillRate") { displayName = "Base Fill Rate" }
        }
        return model
    }
}
