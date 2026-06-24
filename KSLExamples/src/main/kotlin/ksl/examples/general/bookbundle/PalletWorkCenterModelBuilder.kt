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

/** Named, discoverable [ModelBuilderIfc] for the 'PalletWorkCenter' book example. */
class PalletWorkCenterModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Child element name ("PWC") must differ from the Model name.
        // Terminating simulation: set only the replication count (each
        // replication ends when the day's pallets are processed).
        val model = Model("PalletWorkCenter", autoCSVReports = false)
        val sim = PalletWorkCenter(model, numWorkers = 2, name = "PWC")
        model.numberOfReplications = 30
        model.curateCatalog {
            input(sim, PalletWorkCenter::numWorkers) {
                displayName = "Number of Workers"; unit = "workers"
            }
            rvParameter(sim.transportTimeRV, "mean") {
                displayName = "Mean Transport Time"; unit = "min"
            }
            output(sim.workerUtilization) { displayName = "Worker Utilization" }
            output(sim.probOfOverTime) { displayName = "P(Overtime > 480 min)" }
            output(sim.totalProcessingTime) { displayName = "Total Processing Time"; unit = "min" }
            output(sim.numInSystem) { displayName = "Avg Pallets at Work Center" }
            output(sim.numPalletsProcessed) { displayName = "Pallets Processed" }
        }
        return model
    }
}
