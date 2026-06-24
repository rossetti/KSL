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

/** Named, discoverable [ModelBuilderIfc] for the 'DriveThroughPharmacyWithQ' book example. */
class DriveThroughPharmacyWithQModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("DriveThroughPharmacyWithQ", autoCSVReports = false)
        val sim = DriveThroughPharmacyWithQ(model, numServers = 1, name = "Pharmacy")
        model.numberOfReplications = 30
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 5000.0
        model.curateCatalog {
            input(sim, DriveThroughPharmacyWithQ::numPharmacists) {
                displayName = "Number of Pharmacists"; unit = "pharmacists"
            }
            rvParameter(sim.serviceRV, "mean") { displayName = "Mean Service Time"; unit = "min" }
            output(sim.systemTime) { displayName = "Avg Time in System"; unit = "min" }
            output(sim.numInSystem) { displayName = "Avg Number in System" }
            output(sim.probSystemTimeGT4Minutes) { displayName = "P(System Time >= 4 min)" }
            output(sim.numCustomersServed) { displayName = "Number Served" }
        }
        return model
    }
}
