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

/** Named, discoverable [ModelBuilderIfc] for the 'TestAndRepairShopWithMovableResources' book example; the build that [BookExamplesBundle] delegates to. */
class TestAndRepairShopWithMovableResourcesModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Child element name ("TestRepairMR") must differ from the Model name.
        val model = Model("TestAndRepairShopWithMovableResources", autoCSVReports = false)
        val sim = TestAndRepairShopWithMovableResources(model, name = "TestRepairMR")
        model.numberOfReplications = 10
        model.lengthOfReplication = 30000.0
        model.lengthOfReplicationWarmUp = 5000.0
        model.curateCatalog {
            input("DiagnosticWorkers.initialCapacity") { displayName = "Diagnostic Workers"; unit = "workers" }
            input("RepairWorkers.initialCapacity") { displayName = "Repair Workers"; unit = "workers" }
            input("Test1.initialCapacity") { displayName = "Test-1 Machines"; unit = "machines" }
            input("Test2.initialCapacity") { displayName = "Test-2 Machines"; unit = "machines" }
            input("Test3.initialCapacity") { displayName = "Test-3 Machines"; unit = "machines" }
            output(sim.numInSystem) { displayName = "Avg Number in System" }
            output(sim.systemTime) { displayName = "Avg Time in System"; unit = "min" }
            output(sim.probWithinLimit) { displayName = "P(Within 480-min Contract)" }
        }
        return model
    }
}
