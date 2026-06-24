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

/** Named, discoverable [ModelBuilderIfc] for the 'WalkInHealthClinic' book example. */
class WalkInHealthClinicModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Child element name ("WalkInClinic") must differ from the Model name.
        val model = Model("WalkInHealthClinic", autoCSVReports = false)
        val sim = WalkInHealthClinic(model, name = "WalkInClinic")
        model.numberOfReplications = 30
        model.lengthOfReplication = 10.0 * 60.0   // a 10-hour clinic day
        model.curateCatalog {
            input("Doctors.initialCapacity") { displayName = "Number of Doctors"; unit = "doctors" }
            input("TriageNurse.initialCapacity") { displayName = "Number of Triage Nurses"; unit = "nurses" }
            input(sim, WalkInHealthClinic::balkCriteria) {
                displayName = "Balk Threshold (queue length)"; unit = "patients"
            }
            output(sim.systemTime) { displayName = "Avg Time in System"; unit = "min" }
            output("WalkInClinic:TimeInSystemHigh") { displayName = "Avg Time in System (high priority)"; unit = "min" }
            output("WalkInClinic:TimeInSystemMedium") { displayName = "Avg Time in System (medium priority)"; unit = "min" }
            output("WalkInClinic:TimeInSystemLow") { displayName = "Avg Time in System (low priority)"; unit = "min" }
            output(sim.probBalking) { displayName = "P(Balk)" }
            output(sim.probReneging) { displayName = "P(Renege)" }
            output("WalkInClinic:NumServed") { displayName = "Number Served" }
            output("WalkInClinic:NumBalked") { displayName = "Number Balked" }
            output("WalkInClinic:NumReneged") { displayName = "Number Reneged" }
        }
        return model
    }
}
