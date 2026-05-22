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
import net.peanuuutz.tomlkt.TomlComment

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
 * @property enableReplicationCSV  when `true`, the orchestrator turns
 *                             on `Model.autoReplicationCSVReports` for
 *                             this scenario so per-replication response
 *                             data is written to the scenario's
 *                             output directory.  Independent of any
 *                             document-level CSV setting and of
 *                             [enableExperimentCSV].  Default false.
 * @property enableExperimentCSV   when `true`, the orchestrator turns
 *                             on `Model.autoExperimentCSVReports` for
 *                             this scenario so across-replication
 *                             summary statistics are written to the
 *                             scenario's output directory.
 *                             Independent of any document-level CSV
 *                             setting and of [enableReplicationCSV].
 *                             Default false.
 */
@Serializable
data class ScenarioSpec(
    @TomlComment(
        "String (required, non-blank). User-given scenario name.  Must\n" +
        "be unique within this document.  Used as the experimentName\n" +
        "for the run and as the row key when writing to the SQLite\n" +
        "database."
    )
    val name: String,

    @TomlComment(
        "Identifies the model this scenario runs.  Required.  Rendered\n" +
        "as a [scenarios.modelReference] sub-table with a 'type'\n" +
        "discriminator and one or more variant-specific keys:\n" +
        "  type = 'byBundleAndModelId'  bundleId, modelId  (typical GUI form)\n" +
        "  type = 'byProviderId'        providerId          (programmatic)\n" +
        "  type = 'byJar'               jarPath, ...        (legacy)\n" +
        "  type = 'embedded'            modelName           (tests / embedded)"
    )
    val modelReference: ModelReference,

    @TomlComment(
        "Optional. Per-scenario overrides for the model's default\n" +
        "experiment run parameters.  Omit the [scenarios.runOverrides]\n" +
        "sub-table entirely to inherit every model default; include only\n" +
        "the fields you want to change.  Each field's default and\n" +
        "expected type are documented above the field."
    )
    val runOverrides: ExperimentRunOverrides? = null,

    @TomlComment(
        "Optional. Per-scenario numeric / boolean control overrides.\n" +
        "Exported by the GUI's control-editor; prefer round-tripping\n" +
        "through the app over hand-editing.  An empty export (the\n" +
        "default) leaves model control values at the model's defaults."
    )
    val controlOverrides: ModelControlsExport = ModelControlsExport(modelName = ""),

    @TomlComment(
        "Optional. Per-scenario random-variable parameter overrides.\n" +
        "Each [[scenarios.rvOverrides]] entry pins one (rvName, paramName)\n" +
        "pair to a new value.  An empty list (the default) leaves model\n" +
        "RV parameters unchanged."
    )
    val rvOverrides: List<RVParameterOverride> = emptyList(),

    @TomlComment(
        "Optional. Free-form (String → String) map forwarded to\n" +
        "ModelBuilderIfc.build().  Use only when the model bundle\n" +
        "documents construction-time inputs; omit otherwise."
    )
    val modelConfiguration: Map<String, String>? = null,

    @TomlComment(
        "Boolean. When true, the orchestrator excludes this scenario\n" +
        "from the runnable set on Simulate, even though it remains in\n" +
        "the list.  Useful for staging which scenarios to run without\n" +
        "deleting them.  Default: false."
    )
    val skipOnRun: Boolean = false,

    @TomlComment(
        "Boolean. Per-scenario override of [outputConfig].enableReplicationCSV.\n" +
        "When true, this scenario emits per-replication CSV regardless\n" +
        "of the document-wide setting.  Default: false."
    )
    val enableReplicationCSV: Boolean = false,

    @TomlComment(
        "Boolean. Per-scenario override of [outputConfig].enableExperimentCSV.\n" +
        "When true, this scenario emits across-replication summary CSV\n" +
        "regardless of the document-wide setting.  Default: false."
    )
    val enableExperimentCSV: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "name must be non-blank" }
        require(modelConfiguration == null || modelConfiguration.keys.all { it.isNotBlank() }) {
            "every key in modelConfiguration must be non-blank when modelConfiguration is non-null"
        }
    }
}
