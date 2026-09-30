/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.animationbundle.showcase

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * The vehicle models that join the animation pack and the published gallery, beside the animation examples.
 *
 * A chosen few rather than all ten the suite ships: each needs a layout a person has polished, and a long
 * vehicle run is a large trace, so the download stays lean and every vehicle page in it is one worth
 * watching. They are one of each thing a viewer should see: a loop shop, a shop with spills and a
 * maintenance closure, a two-lane warehouse grid, and a two-floor hospital.
 *
 * A showcase trace is one replication of one shift ([SHOWCASE_LENGTH] minutes) with no warm-up: long enough
 * to see congestion build and clear, short enough that the page loads.
 */
object FeaturedVehicles {

    /** The vehicle examples bundle these come from, which is also where their shipped layouts are keyed. */
    const val BUNDLE_ID: String = "edu.uark.ksl.vehicle-examples"

    /** The featured models, by bundle model id, in the order the pack and the gallery show them. */
    val modelIds: List<String> = listOf("SimpleAgvShop", "GuidePathDisturbances", "TwoLaneWarehouse", "MultiFloorHospital")

    /** Simulated minutes captured for a showcase trace: one shift. */
    const val SHOWCASE_LENGTH: Double = 480.0

    /** Where a featured model's source is read, for the gallery's "read the model's source" link. */
    const val SOURCE_URL: String =
        "https://github.com/rossetti/KSL/blob/main/KSLExamples/src/main/kotlin/ksl/examples/general/vehiclebundle/VehicleExampleBuilders.kt"

    /** Builds featured model [modelId] set up for a showcase capture. */
    fun build(modelId: String): Model {
        require(modelId in modelIds) { "$modelId is not a featured vehicle model; featured: $modelIds" }
        val builder = Class.forName("ksl.examples.general.vehiclebundle.${modelId}ModelBuilder")
            .getDeclaredConstructor().newInstance() as ModelBuilderIfc
        return builder.build(null, null as ExperimentRunParametersIfc?).apply {
            numberOfReplications = 1
            lengthOfReplicationWarmUp = 0.0
            lengthOfReplication = minOf(lengthOfReplication, SHOWCASE_LENGTH)
        }
    }
}
