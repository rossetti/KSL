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

/** Named, discoverable [ModelBuilderIfc] for the 'TieDyeTShirts' book example. */
class TieDyeTShirtsModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // Child element name ("TieDye") must differ from the Model name.
        val model = Model("TieDyeTShirts", autoCSVReports = false)
        val sim = TieDyeTShirts(model, name = "TieDye")
        model.numberOfReplications = 30
        model.lengthOfReplication = 480.0
        model.curateCatalog {
            input("ShirtMakers_R.initialCapacity") {
                displayName = "Shirt Makers"; unit = "workers"
            }
            input("Packager_R.initialCapacity") {
                displayName = "Packagers"; unit = "workers"
            }
            output("System Time") { displayName = "Avg Order Time in System"; unit = "min" }
            output("Num in System") { displayName = "Avg Number of Orders in System" }
        }
        return model
    }
}
