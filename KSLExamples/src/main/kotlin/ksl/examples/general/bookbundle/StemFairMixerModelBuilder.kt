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

/** Named, discoverable [ModelBuilderIfc] for the 'StemFairMixer' book example. */
class StemFairMixerModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Child element name ("StemFair") must differ from the Model name.
        val model = Model("StemFairMixer", autoCSVReports = false)
        val sim = StemFairMixer(model, name = "StemFair")
        model.numberOfReplications = 400
        model.lengthOfReplication = 6.0 * 60.0   // a single 6-hour mixer
        model.curateCatalog {
            // Recruiter-team capacities (ResourceWithQ initialCapacity controls).
            input("JHBuntR.initialCapacity") {
                displayName = "JH-Bunt Recruiters"; unit = "recruiters"
            }
            input("MalWartR.initialCapacity") {
                displayName = "Mal-Wart Recruiters"; unit = "recruiters"
            }
            output("OverallSystemTime") { displayName = "Avg Time in System"; unit = "min" }
            output("NumInSystem") { displayName = "Avg Number in System" }
            output("NonWanderSystemTime") { displayName = "Avg Time in System (non-wanderers)"; unit = "min" }
            output("WanderSystemTime") { displayName = "Avg Time in System (wanderers)"; unit = "min" }
            output("LeaverSystemTime") { displayName = "Avg Time in System (leavers)"; unit = "min" }
        }
        return model
    }
}
