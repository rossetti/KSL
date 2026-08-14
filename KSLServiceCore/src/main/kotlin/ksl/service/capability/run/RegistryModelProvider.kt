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

package ksl.service.capability.run

import ksl.app.bundle.BundleModelProvider
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelProviderIfc

/**
 * A [ModelProviderIfc] that resolves against the registry's *current* bundle
 * set on every call, so models added at runtime (Phase 8.6 dynamic loading)
 * become resolvable without rebuilding the session. A `KSLAppSession` built over
 * this provider therefore sees newly-dropped bundles.
 */
class RegistryModelProvider(private val registry: BundleRegistry) : ksl.app.bundle.BundleModelProviderIfc {

    private fun current(): BundleModelProvider = BundleModelProvider(registry.currentBundles())

    override fun isModelProvided(modelIdentifier: String): Boolean =
        current().isModelProvided(modelIdentifier)

    override fun provideModel(
        modelIdentifier: String,
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?,
    ): Model = current().provideModel(modelIdentifier, modelConfiguration, experimentRunParameters)

    override fun modelIdentifiers(): List<String> = current().modelIdentifiers()

    // The bundle-pair half of the contract. The registry has always been able to answer these —
    // current() IS a BundleModelProvider — but until this class declared the capability, a
    // configuration saved by a desktop app (which writes modelReference.byBundleAndModelId) was
    // rejected by the server's resolution guards for being the wrong concrete type. Declaring it
    // is what makes ONE RunConfiguration file run in the apps and on the server alike.

    override fun isModelProvided(bundleId: String, modelId: String): Boolean =
        current().isModelProvided(bundleId, modelId)

    override fun provideModel(
        bundleId: String,
        modelId: String,
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?,
    ): Model = current().provideModel(bundleId, modelId, modelConfiguration, experimentRunParameters)

    override fun builderFor(bundleId: String, modelId: String): ksl.simulation.ModelBuilderIfc =
        current().builderFor(bundleId, modelId)
}
