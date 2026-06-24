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

/** Named, discoverable [ModelBuilderIfc] for the 'TandemQueueWithConstrainedMovement' book example. */
class TandemQueueWithConstrainedMovementModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Child element name ("TandemConstrained") must differ from the Model name.
        val model = Model("TandemQueueWithConstrainedMovement", autoCSVReports = false)
        val sim = TandemQueueWithConstrainedMovement(model, name = "TandemConstrained")
        model.numberOfReplications = 30
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 5000.0
        model.curateCatalog {
            input("worker1.initialCapacity") { displayName = "Station-1 Workers"; unit = "workers" }
            input("worker2.initialCapacity") { displayName = "Station-2 Workers"; unit = "workers" }
            rvParameter(sim.service1RV, "mean") { displayName = "Station-1 Mean Service Time"; unit = "min" }
            rvParameter(sim.service2RV, "mean") { displayName = "Station-2 Mean Service Time"; unit = "min" }
            output(sim.numInSystem) { displayName = "Avg Number in System" }
            output(sim.systemTime) { displayName = "Avg Time in System"; unit = "min" }
        }
        return model
    }
}
