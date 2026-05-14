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

package ksl.app.config

import kotlinx.serialization.Serializable
import ksl.controls.ModelControlsExport

/**
 * Serialisable specification for a single scenario in a scenarios document.
 *
 * Each scenario is **self-contained**: it carries its own
 * [modelReference] (which model from which bundle), its own partial
 * overrides relative to that model's defaults, and its own name. There
 * is no inheritance from a document-level "parent" — `RunConfiguration`
 * is a thin container that holds bundle references and the list of
 * scenarios; it does not own defaults that scenarios fall back to.
 *
 * Override semantics are uniform across all four override surfaces:
 *
 * - [runOverrides]      — partial run-parameter overrides; `null` means
 *                         "use the model's `ExperimentRunDefaults` for
 *                         every field." Non-`null` overlays only the
 *                         fields the user set.
 * - [controlOverrides]  — control-key → value overrides for the model's
 *                         declared controls (numeric / string / JSON);
 *                         an empty export means no overrides.
 * - [rvOverrides]       — per-RV-parameter overrides; an empty list
 *                         means no overrides.
 * - [modelConfiguration] — optional `Map<String, String>` forwarded to
 *                         the bundle author's `ModelBuilderIfc.build(...)`;
 *                         `null` means no map is supplied (distinct from
 *                         an empty map, which is supplied but contains
 *                         nothing).
 *
 * `ksl.app.orchestrator.ScenarioOrchestrator.buildScenario` resolves
 * this spec at submit time by:
 *
 *   1. Resolving [modelReference] against the document's bundle registry
 *      (built from `RunConfiguration.bundleRefs`).
 *   2. Computing the runtime [ksl.controls.experiments.ExperimentRunParameters]
 *      as `model.extractRunParameters() + runOverrides.applyTo(...)`,
 *      with `experimentName` set to [name].
 *   3. Applying [controlOverrides], then [rvOverrides].
 *   4. Honoring [skipOnRun] (excluded from the runnable set at submit
 *      time when `true`).
 *
 * @property name              user-given scenario name; unique within
 *                             the enclosing `RunConfiguration.scenarios`.
 *                             Used as the experiment name for the run.
 * @property modelReference    which model the scenario runs; the bundled
 *                             `(bundleId, modelId)` form is the typical
 *                             choice (`ModelReference.ByBundleAndModelId`)
 *                             when authoring through the GUI; legacy
 *                             references (`ByProviderId` / `ByJar`) are
 *                             still accepted for programmatic
 *                             constructions.
 * @property runOverrides      per-field run-parameter overrides; `null`
 *                             when the scenario inherits every model
 *                             default.
 * @property controlOverrides  control overrides for this scenario; an
 *                             empty export (the default) leaves model
 *                             control values unchanged.
 * @property rvOverrides       RV parameter overrides; an empty list
 *                             (the default) leaves model RV parameters
 *                             unchanged.
 * @property modelConfiguration optional model-construction map
 *                             forwarded to `ModelBuilderIfc.build`;
 *                             `null` (the default) supplies no map.
 * @property skipOnRun         when `true`, the orchestrator excludes
 *                             this scenario from the runnable set even
 *                             if it is otherwise valid; useful for
 *                             staging which scenarios to run.
 */
@Serializable
data class ScenarioSpec(
    val name: String,
    val modelReference: ModelReference,
    val runOverrides: ExperimentRunOverrides? = null,
    val controlOverrides: ModelControlsExport = ModelControlsExport(modelName = ""),
    val rvOverrides: List<RVParameterOverride> = emptyList(),
    val modelConfiguration: Map<String, String>? = null,
    val skipOnRun: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "name must be non-blank" }
        require(modelConfiguration == null || modelConfiguration.keys.all { it.isNotBlank() }) {
            "every key in modelConfiguration must be non-blank when modelConfiguration is non-null"
        }
    }
}
