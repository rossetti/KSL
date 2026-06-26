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

import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.optimization.OptimizationRunConfiguration

/**
 * Computes the result-cache version salt for a document from the models it
 * references (Phase 8 §9 bundle-version invalidation). The salt folds the
 * providing bundle's code version into the cache key, so a *rebuilt* model —
 * same document, new code — produces a different key and is not served a stale
 * cached result. Resolves only `ModelReference.ByProviderId` (the form the
 * server uses); a model the registry no longer provides contributes an empty
 * token, so its results naturally miss after removal.
 */
object CacheVersion {

    fun forRun(registry: BundleRegistry, config: RunConfiguration): String =
        registry.versionSaltFor(config.scenarios.mapNotNull { providerId(it.modelReference) })

    fun forOptimization(registry: BundleRegistry, config: OptimizationRunConfiguration): String =
        registry.versionSaltFor(listOfNotNull(config.problem?.modelIdentifier))

    fun forExperiment(registry: BundleRegistry, config: ExperimentConfiguration): String =
        registry.versionSaltFor(listOfNotNull(providerId(config.modelReference)))

    private fun providerId(ref: ModelReference): String? =
        (ref as? ModelReference.ByProviderId)?.providerId
}
