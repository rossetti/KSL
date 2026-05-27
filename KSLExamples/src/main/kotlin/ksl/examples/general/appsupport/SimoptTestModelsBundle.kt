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
import ksl.examples.general.simopt.BuildLKModel
import ksl.examples.general.simopt.BuildRQModel
import ksl.simulation.ModelBuilderIfc

/**
 * Bundle exposing the two well-tested simulation-optimization fixture
 * models from `ksl.examples.general.simopt.ModelBuilding` as a
 * `KSLModelBundle`.
 *
 * Both bundled entries delegate to the singletons in `ModelBuilding`
 * (`BuildLKModel` and `BuildRQModel`) so the build logic stays in a
 * single source of truth.  Run parameters are tuned for optimization
 * (longer horizons + more replications for tighter response
 * estimates) — they intentionally differ from the build parameters in
 * `LKInventoryBundle` (which targets Single / Scenario / Experiment
 * workflows with a quicker 10-replication cycle).
 *
 * ## Control surfaces
 *
 * **`LKInventoryOpt`** — `ksl.examples.general.models.LKInventoryModel`
 * declares `@KSLControl`-annotated properties for:
 * `orderQuantity`, `reorderPoint`, `initialInventoryLevel`,
 * `holdingCost`, `costPerItem`, `backLogCost`, `setupCost`.  Decision
 * variables in the SimOpt app's input picker surface with the integer
 * controls (granularity = 1.0) and double controls (granularity = 0.0)
 * pre-filled appropriately.
 *
 * **`RQInventoryOpt`** — `ksl.examples.book.chapter7.RQInventorySystem`
 * contains an `RQInventory` child element named `Inventory:Item` whose
 * `@KSLControl`-annotated properties (`initialOnHand`,
 * `initialReorderPoint`, `initialReorderQty`, `costPerOrder`,
 * `unitHoldingCost`, `unitBackOrderCost`) surface in the descriptor
 * with the `Inventory:Item.` prefix.
 *
 * Registered for `ServiceLoader` discovery via
 * `META-INF/services/ksl.app.bundle.KSLModelBundle` in this module.
 */
class SimoptTestModelsBundle : KSLModelBundle {

    companion object {
        /**
         * Stable `modelId` for the optimization-tuned LK inventory model.
         * Distinct from `LKInventoryBundle.MODEL_ID` (= `"LKInventory"`),
         * whose build targets Single / Scenario / Experiment workflows
         * with a quicker 10-replication cycle.
         */
        const val LK_OPT_MODEL_ID: String = "LKInventoryOpt"

        /**
         * Stable `modelId` for the (R,Q) inventory simopt fixture.
         */
        const val RQ_OPT_MODEL_ID: String = "RQInventoryOpt"
    }

    override val bundleId: String = "ksl.examples.simopt-test-models"

    override val displayName: String = "SimOpt Test Models"

    override val description: String =
        "Two well-tested inventory models with optimization-tuned run parameters " +
            "(longer horizons and more replications for tighter response estimates).  " +
            "Build logic shared with ksl.examples.general.simopt.ModelBuilding."

    override val version: String = "1.0.0"

    override val kslApiVersion: String = "1.2"

    override val models: List<KSLBundledModel> = listOf(LKOptModel, RQOptModel)

    private object LKOptModel : KSLBundledModel {

        override val modelId: String = LK_OPT_MODEL_ID

        override val displayName: String = "LK (s,S) Inventory — optimization preset"

        override val description: String =
            "LK (s,S) inventory; 1000 reps × 120 horizon, warm-up 20.  Controls: " +
                "orderQuantity, reorderPoint, initialInventoryLevel, holdingCost, " +
                "costPerItem, backLogCost, setupCost.  Starting policy: " +
                "(orderQuantity=1, reorderPoint=2)."

        override val supportedApps: Set<KSLAppKind> = setOf(KSLAppKind.SIMOPT)

        override fun builder(): ModelBuilderIfc = BuildLKModel
    }

    private object RQOptModel : KSLBundledModel {

        override val modelId: String = RQ_OPT_MODEL_ID

        override val displayName: String = "(R,Q) Inventory — optimization preset"

        override val description: String =
            "(R,Q) inventory; 40 reps × 20000 horizon, warm-up 10000.  " +
                "Controls (on child element 'Inventory:Item'): initialOnHand, " +
                "initialReorderPoint, initialReorderQty, costPerOrder, " +
                "unitHoldingCost, unitBackOrderCost.  Exponential demand mean = 3.6, " +
                "constant lead time = 0.5."

        override val supportedApps: Set<KSLAppKind> = setOf(KSLAppKind.SIMOPT)

        override fun builder(): ModelBuilderIfc = BuildRQModel
    }
}
