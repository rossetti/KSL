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

package ksl.app.optimization.naming

import ksl.app.config.ModelReference
import ksl.app.config.optimization.AlgorithmKind
import ksl.simulation.ModelDescriptor

/**
 *  Pure derivation helpers for the human-readable identifiers an
 *  optimization run carries in its persisted document, its
 *  `summary.toml`, and its HTML report.
 *
 *  Substrate-level API — any host application (Swing controller,
 *  web form binding, CLI flag parser) uses these to ensure runs
 *  with no user-supplied name still display meaningful identifiers
 *  rather than the substrate's `Identity(null)` → `"ID_<counter>"`
 *  fallback.
 *
 *  All functions are pure projections over their explicit inputs —
 *  no live engine state, no Swing dependency.
 */

/**
 *  Produce a non-blank `modelIdentifier` for an
 *  `ksl.app.config.optimization.OptimizationProblemSpec`.
 *
 *  Order of preference:
 *  1. `descriptor.modelIdentifier` (when available — that's the
 *     identifier of the actual `Model` the runtime will build).
 *  2. A natural identifier derived from the model reference
 *     (`"bundleId:modelId"` for `ByBundleAndModelId`, the
 *     `providerId` for `ByProviderId`, etc.).
 *
 *  Returns `null` only when [modelReference] is `null`.  The
 *  `OptimizationProblemSpec` init accepts a `null` field but
 *  rejects a blank string.
 */
fun deriveModelIdentifier(
    descriptor: ModelDescriptor?,
    modelReference: ModelReference?
): String? {
    descriptor?.modelIdentifier?.takeIf { it.isNotBlank() }?.let { return it }
    return when (modelReference) {
        null -> null
        is ModelReference.ByBundleAndModelId -> "${modelReference.bundleId}:${modelReference.modelId}"
        is ModelReference.ByProviderId -> modelReference.providerId
        is ModelReference.ByJar -> modelReference.builderClassName
        is ModelReference.Embedded -> modelReference.modelName
    }
}

/**
 *  Effective problem name for the persisted spec.  Uses
 *  [explicitProblemName] when non-blank; otherwise derives a
 *  readable default so reports and `summary.toml` show something
 *  meaningful instead of the substrate's `Identity(null)` →
 *  `"ID_<counter>"` fallback.
 *
 *  Order of preference:
 *  1. user-supplied [explicitProblemName],
 *  2. `descriptor.modelName`,
 *  3. [deriveModelIdentifier] (the model-reference natural id),
 *  4. `"Optimization"` (last-resort non-null sentinel).
 */
fun deriveProblemName(
    explicitProblemName: String?,
    descriptor: ModelDescriptor?,
    modelReference: ModelReference?
): String {
    explicitProblemName?.takeIf { it.isNotBlank() }?.let { return it }
    descriptor?.modelName?.takeIf { it.isNotBlank() }?.let { return it }
    deriveModelIdentifier(descriptor, modelReference)
        ?.takeIf { it.isNotBlank() }?.let { return it }
    return "Optimization"
}

/**
 *  Effective solver name for the persisted spec.  Uses
 *  [explicitSolverName] when non-blank; otherwise derives a name
 *  from the chosen algorithm so reports and `summary.toml`
 *  describe what was run instead of the substrate's
 *  `Identity(null)` → `"ID_<counter>"` fallback.
 *
 *  Order of preference:
 *  1. user-supplied [explicitSolverName],
 *  2. `algorithmKind.displayName` (e.g. `"Stochastic Hill Climbing"`).
 *
 *  Returns `null` only when [algorithmKind] is also `null` — in
 *  that case the host hasn't picked an algorithm yet and the
 *  question of a solver name is moot.
 */
fun deriveSolverName(
    explicitSolverName: String?,
    algorithmKind: AlgorithmKind?
): String? {
    explicitSolverName?.takeIf { it.isNotBlank() }?.let { return it }
    return algorithmKind?.displayName
}
