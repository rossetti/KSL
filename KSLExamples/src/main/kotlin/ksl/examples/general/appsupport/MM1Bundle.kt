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

import ksl.app.bundle.KSLAppKind
import ksl.app.bundle.KSLBundledModel
import ksl.app.bundle.KSLModelBundle
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Bundle exposing the textbook single-server M/M/1 queue example as a
 * `KSLModelBundle`. The model is built with `GIGcQueue` configured for one
 * server; the model declares a `@KSLControl`-marked `numServers` factor, so
 * it is also a workable subject for a one-factor designed experiment.
 *
 * Registered for `ServiceLoader` discovery via
 * `META-INF/services/ksl.app.bundle.KSLModelBundle` in this module.
 */
class MM1Bundle : KSLModelBundle {

    override val bundleId: String = "ksl.examples.mm1"

    override val displayName: String = "M/M/1 Queue Example"

    override val description: String =
        "Single-server M/M/1 queue (GIGcQueue) with one controllable factor (numServers)."

    override val version: String = "1.0.0"

    override val kslApiVersion: String = "1.2"

    override val models: List<KSLBundledModel> = listOf(MM1Model)

    private object MM1Model : KSLBundledModel {

        override val modelId: String = "MM1"

        override val displayName: String = "M/M/1 Queue"

        override val description: String =
            "A single-server M/M/1 queue with exponential interarrivals and service."

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
                // The child element name must not equal the Model's own name;
                // doing so would collide as a duplicate ModelElement at the root.
                val model = Model(modelId, autoCSVReports = false)
                GIGcQueue(model, numServers = 1, name = "MM1Queue")
                model.numberOfReplications = 30
                model.lengthOfReplication = 500.0
                model.lengthOfReplicationWarmUp = 50.0
                return model
            }
        }
    }
}
