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

package ksl.examples.general.bundling

import ksl.app.bundle.KSLAppKind
import ksl.app.bundle.KSLBundledModel
import ksl.app.bundle.KSLModelBundle
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * The bundle wiring for the [SimpleServer] model — the worked example for the
 * **Preparing a Model for Bundling** guide.
 *
 * A [KSLModelBundle] is the self-describing unit of model distribution: it names
 * one or more models, says which apps each supports, and supplies a
 * [ModelBuilderIfc] that constructs the model on demand. Registering this class
 * for `ServiceLoader` (see
 * `META-INF/services/ksl.app.bundle.KSLModelBundle`) makes it discoverable from
 * a JAR without loading any Kotlin class up front.
 */
class SimpleServerBundle : KSLModelBundle {

    override val bundleId: String = "ksl.guide.simple-server"
    override val displayName: String = "Simple Server Queue (Guide Example)"
    override val description: String =
        "A minimal single-queue, c-server station used by the model-bundling guide."
    override val version: String = "1.0.0"
    override val kslApiVersion: String = "1.2"

    override val author: String? = "KSL"
    override val tags: Set<String> = setOf("guide", "queue", "example")

    override val models: List<KSLBundledModel> = listOf(SimpleServerModel)

    private object SimpleServerModel : KSLBundledModel {

        override val modelId: String = "SimpleServer"
        override val displayName: String = "Simple Server Queue"
        override val description: String =
            "Customers arrive, wait in one queue, are served by one of c servers, and leave."

        // The apps this model is meaningful in. A single-response queue is a fine
        // subject for a single run, a scenario comparison, a one-factor design,
        // and an optimization over the number of servers.
        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT,
            KSLAppKind.SIMOPT
        )

        override fun builder(): ModelBuilderIfc = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                // The child element name ("Server") must differ from the Model's
                // own name ("SimpleServer"), or the two collide at the root.
                val model = Model(modelId, autoCSVReports = false)
                val server = SimpleServer(model, numServers = 1, name = "Server")
                model.numberOfReplications = 30
                model.lengthOfReplication = 5000.0
                model.lengthOfReplicationWarmUp = 1000.0

                // Nominate the headline inputs and outputs. This catalog is what
                // drives the apps' model-info panels and input pickers — it gives
                // each control / RV parameter / response a friendly name and unit.
                model.curateCatalog {
                    input(server, SimpleServer::numServers) {
                        displayName = "Number of Servers"; unit = "servers"
                    }
                    rvParameter(server.serviceTime, "mean") {
                        displayName = "Mean Service Time"; unit = "min"
                    }
                    rvParameter(server.timeBetweenArrivals, "mean") {
                        displayName = "Mean Time Between Arrivals"; unit = "min"
                    }
                    output(server.systemTime) { displayName = "Avg Time in System"; unit = "min" }
                    output(server.numInSystem) { displayName = "Avg Number in System" }
                    output(server.numServed) { displayName = "Number Served" }
                }

                // Honor any run-parameter overrides the host passes in.
                if (experimentRunParameters != null) {
                    model.changeRunParameters(experimentRunParameters)
                }
                return model
            }
        }
    }
}
