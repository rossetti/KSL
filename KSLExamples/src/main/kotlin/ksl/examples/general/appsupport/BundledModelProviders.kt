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
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc

/**
 * Shared registry of bundled example-model providers used by the reference
 * Swing applications under `KSLAppSwingSingle`, `KSLAppSwingScenario`, and
 * `KSLAppSwingExperiment`.
 *
 * Lives in `KSLExamples` rather than a separate "common" module because
 * this is example-model wiring (it composes `GIGcQueue` and
 * `LKInventoryModel`, both of which are already in `KSLExamples`), not
 * GUI-common code.  If shared GUI widgets emerge in later Phase 6
 * modules, those would belong in a dedicated `KSLAppSwingCommon` module —
 * but bundled example models are not GUI widgets.
 *
 * The bundled models:
 * - **MM1**: a single-server M/M/1 queue via [GIGcQueue].  Has one
 *   `@KSLControl`-marked property (`numServers`) so it can also be used
 *   as a designed-experiment subject with a 1-factor design.
 * - **LKInventory**: the textbook Hadley/Whitin (s, S) inventory model
 *   via [LKInventoryModel].  Exposes several `@KSLControl`-marked
 *   properties (`orderQuantity`, `reorderPoint`, etc.) suitable for
 *   factorial designs.
 *
 * Each [ModelBuilderIfc] sets reasonable defaults for
 * `numberOfReplications`, `lengthOfReplication`, and
 * `lengthOfReplicationWarmUp`; callers that need different values pass
 * an [ExperimentRunParametersIfc] through to
 * [ModelProviderIfc.provideModel] in the usual way.
 */
object BundledModelProviders {

    /** Stable identifier for the M/M/1 example queue. */
    const val MM1_ID: String = "MM1"

    /** Stable identifier for the textbook LK inventory model. */
    const val LK_INVENTORY_ID: String = "LKInventory"

    /** Display order suitable for a model-picker UI. */
    val availableModelIds: List<String> = listOf(MM1_ID, LK_INVENTORY_ID)

    private val builders: Map<String, ModelBuilderIfc> = mapOf(
        MM1_ID to mm1Builder(),
        LK_INVENTORY_ID to lkBuilder()
    )

    /** The single [ModelProviderIfc] holding all bundled model builders. */
    val provider: ModelProviderIfc = MapModelProvider(builders.toMutableMap())

    /**
     * Returns the [ModelBuilderIfc] for [modelId].  Useful for callers that
     * need to pass a builder directly (e.g. [ksl.controls.experiments.ParallelDesignedExperiment]),
     * where the [provider] indirection would otherwise force a delegating
     * adapter.
     *
     * @throws IllegalArgumentException if [modelId] is not one of
     *         [availableModelIds]
     */
    fun builderFor(modelId: String): ModelBuilderIfc =
        builders[modelId] ?: throw IllegalArgumentException("Unknown bundled model id: $modelId")

    private fun mm1Builder(): ModelBuilderIfc = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            // Note: child element name must not equal the Model's own name,
            // or it will collide as a duplicate ModelElement at the root.
            val model = Model(MM1_ID, autoCSVReports = false)
            GIGcQueue(model, numServers = 1, name = "MM1Queue")
            model.numberOfReplications = 30
            model.lengthOfReplication = 500.0
            model.lengthOfReplicationWarmUp = 50.0
            return model
        }
    }

    private fun lkBuilder(): ModelBuilderIfc = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(LK_INVENTORY_ID, autoCSVReports = false)
            LKInventoryModel(model, "Inventory")
            model.numberOfReplications = 10
            model.lengthOfReplication = 120.0
            model.lengthOfReplicationWarmUp = 20.0
            return model
        }
    }
}
