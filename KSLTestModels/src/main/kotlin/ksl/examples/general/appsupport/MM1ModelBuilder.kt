/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.appsupport

import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Named, discoverable [ModelBuilderIfc] for the textbook single-server M/M/1 queue
 * (`GIGcQueue` configured for one server), nominating the headline inputs/outputs via
 * `curateCatalog`. Default `modelId` (class name minus the `ModelBuilder` suffix) is
 * `MM1`.
 *
 * This is the build that `kslpkg assemble` / the Bundle Workbench discover when
 * assembling a manifest bundle from a builders JAR.
 */
class MM1ModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        // The child element name must not equal the Model's own name;
        // doing so would collide as a duplicate ModelElement at the root.
        val model = Model("MM1", autoCSVReports = false)
        val queue = GIGcQueue(model, numServers = 1, name = "MM1Queue")
        model.numberOfReplications = 30
        model.lengthOfReplication = 500.0
        model.lengthOfReplicationWarmUp = 50.0
        // Author-nominated catalog of the model's headline inputs/outputs,
        // declared on the unedited GIGcQueue via curateCatalog.
        model.curateCatalog {
            input(queue, GIGcQueue::numServers) { displayName = "Number of Servers"; unit = "servers" }
            rvParameter(queue.serviceRV, "mean") { displayName = "Mean Service Time"; unit = "min" }
            rvParameter(queue.timeBtwArrivalRV, "mean") { displayName = "Mean Time Between Arrivals"; unit = "min" }
            output(queue.systemTime) { displayName = "Avg Time in System"; unit = "min" }
            output(queue.numInSystem) { displayName = "Avg Number in System" }
            output(queue.numCustomersServed) { displayName = "Number Served" }
        }
        return model
    }
}
