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
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc

/**
 * Bundle exposing the textbook Hadley/Whitin (s, S) inventory model as a
 * `KSLModelBundle`. The underlying `ksl.examples.general.models.LKInventoryModel`
 * exposes several `@KSLControl`-marked properties (`orderQuantity`,
 * `reorderPoint`, etc.), making it a suitable subject for factorial
 * designs as well as scenario sweeps.
 *
 * Registered for `ServiceLoader` discovery via
 * `META-INF/services/ksl.app.bundle.KSLModelBundle` in this module.
 */
class LKInventoryBundle : KSLModelBundle {

    companion object {
        /**
         * Stable `modelId` of the single model packaged in this bundle.
         * Exposed as a typed reference so callers that hardcode a default
         * picker selection or filter logic do not depend on a string
         * literal that could drift if the model is ever renamed.
         */
        const val MODEL_ID: String = "LKInventory"
    }

    override val bundleId: String = "ksl.examples.lk-inventory"

    override val displayName: String = "LK (s,S) Inventory Model"

    override val description: String =
        "Textbook Hadley/Whitin (s,S) inventory model with multiple controllable factors."

    override val version: String = "1.0.0"

    override val kslApiVersion: String = "1.2"

    override val models: List<KSLBundledModel> = listOf(LKInventoryBundledModel)

    private object LKInventoryBundledModel : KSLBundledModel {

        override val modelId: String = MODEL_ID

        override val displayName: String = "LK (s,S) Inventory"

        override val description: String =
            "Hadley/Whitin (s,S) inventory model; supports factorial designs over its controls."

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
                val model = Model(modelId, autoCSVReports = false)
                LKInventoryModel(model, "Inventory")
                model.numberOfReplications = 10
                model.lengthOfReplication = 120.0
                model.lengthOfReplicationWarmUp = 20.0
                return model
            }
        }
    }
}
