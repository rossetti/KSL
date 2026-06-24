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

/** Named, discoverable [ModelBuilderIfc] for the 'TestAndRepairShopWithConveyor' book example; the build that [BookExamplesBundle] delegates to. */
class TestAndRepairShopWithConveyorModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Child element name ("TestRepairConv") must differ from the Model name.
        val model = Model("TestAndRepairShopWithConveyor", autoCSVReports = false)
        val sim = TestAndRepairShopWithConveyor(model, name = "TestRepairConv")
        model.numberOfReplications = 10
        model.lengthOfReplication = 30000.0
        model.lengthOfReplicationWarmUp = 5000.0
        model.curateCatalog {
            input("Diagnostics.initialCapacity") { displayName = "Diagnostic Stations"; unit = "stations" }
            input("Repair.initialCapacity") { displayName = "Repair Stations"; unit = "stations" }
            input("Test1.initialCapacity") { displayName = "Test-1 Stations"; unit = "stations" }
            input("Test2.initialCapacity") { displayName = "Test-2 Stations"; unit = "stations" }
            input("Test3.initialCapacity") { displayName = "Test-3 Stations"; unit = "stations" }
            output(sim.numInSystem) { displayName = "Avg Number in System" }
            output(sim.systemTime) { displayName = "Avg Time in System"; unit = "min" }
            output(sim.probWithinLimit) { displayName = "P(Within 480-min Contract)" }
        }
        return model
    }
}
