/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.app.bundle

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc

/**
 * A [ModelProviderIfc] that can also resolve a model by the unambiguous
 * `(bundleId, modelId)` pair — the capability `ModelReference.ByBundleAndModelId`
 * needs, and the one the flat single-string lookup cannot express (two bundles may
 * ship the same `modelId`, and the flat map shadows all but the first).
 *
 * ## Why this interface exists
 *
 * Resolution sites used to test `provider is BundleModelProvider` — a concrete
 * class. That made the *implementation* the contract, so a provider that could
 * perfectly well answer a two-key lookup was rejected for having the wrong type.
 * The server's `RegistryModelProvider` was exactly that case: it builds a
 * [BundleModelProvider] internally on every call, yet a configuration saved by the
 * Scenario or Single app — which writes `byBundleAndModelId` — was refused with
 * "requires a BundleModelProvider; got RegistryModelProvider". One document could
 * therefore run in the desktop apps or on the server, but never both, which
 * undercut the portable-configuration promise.
 *
 * Depending on the capability instead of the class lets any provider that can do
 * the lookup satisfy it, so the same file runs in either place.
 *
 * Implementations must keep the two-key lookup **unshadowed**: unlike
 * [ModelProviderIfc.provideModel], which resolves a bare `modelId` first-wins
 * across bundles, these overloads must resolve the exact pair.
 */
interface BundleModelProviderIfc : ModelProviderIfc {

    /**
     * True when a model is provided at the unambiguous `(bundleId, modelId)` pair.
     * Distinct from the single-string [ModelProviderIfc.isModelProvided], which uses
     * the flat lookup with first-wins shadowing.
     */
    fun isModelProvided(bundleId: String, modelId: String): Boolean

    /**
     * Builds and returns the model identified by the unambiguous `(bundleId, modelId)`
     * pair, applying [modelConfiguration] and [experimentRunParameters] as the
     * single-string overload does.
     *
     * @throws IllegalArgumentException if no bundle with [bundleId] is provided, or
     *   that bundle has no model with [modelId]
     */
    fun provideModel(
        bundleId: String,
        modelId: String,
        modelConfiguration: Map<String, String>? = null,
        experimentRunParameters: ExperimentRunParametersIfc? = null
    ): Model

    /**
     * The [ModelBuilderIfc] for the `(bundleId, modelId)` pair — the factory, not a
     * built model. Callers that must build the same model many times (parallel
     * designed experiments, simulation-optimization oracles) need the builder rather
     * than one instance.
     *
     * @throws IllegalArgumentException if the pair is not provided
     */
    fun builderFor(bundleId: String, modelId: String): ModelBuilderIfc
}
