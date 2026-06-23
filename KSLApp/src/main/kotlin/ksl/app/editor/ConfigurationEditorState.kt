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

package ksl.app.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.RVParameterOverride
import ksl.app.validation.ValidationFeedbackBus
import ksl.controls.ModelControlsExport
import ksl.controls.experiments.ExperimentRunDefaults
import ksl.simulation.ModelCatalog
import ksl.utilities.random.rvariable.parameters.RVParameterData

/**
 *  Host contract for the reusable configuration-editor panels
 *  (`ParameterPanel`, `ControlOverridesPanel`, `RVOverridesPanel`,
 *  and the composite `ConfigurationEditorPanel`).
 *
 *  The interface decouples those panels from any specific
 *  controller so they can be re-mounted in other GUI surfaces — the
 *  Scenario app's per-scenario editor window in particular, which
 *  hosts the same three panels against a per-scenario edit-buffer
 *  rather than a process-wide controller — and so future non-Swing
 *  hosts (web form, CLI editor) can drive the same state shape.
 *
 *  ## What the host owns
 *
 *  - **Snapshots** captured at probe time
 *    ([modelDefaults], [controlsSnapshot], [rvSnapshot]) — the
 *    panels read these once for rendering placeholder text, column
 *    populations, and table rows.  They do not change during the
 *    editor's lifetime.
 *  - **Live state** ([runOverrides], [controlOverrides],
 *    [rvOverrides]) — the panels both subscribe to these flows (to
 *    update their displays on external mutation) and push changes
 *    via the matching mutators below.
 *  - **Validation bus** + **EDT-confined coroutine scope** — for
 *    field-level error markers and panel-internal collectors.
 *
 *  ## What the panels do
 *
 *  - Subscribe to the StateFlows for display sync.
 *  - Call the mutators when the user edits a field, toggles an
 *    override checkbox, or clicks Reset-all.
 *  - Decorate field/row appearance from validation findings via
 *    [validationBus].
 *
 *  The panels never reach back to host concerns like Save, Run,
 *  window title, or dirty-tracking — those belong to the parent
 *  frame/controller and are above this interface.
 *
 *  ## Implementations
 *
 *  - `ksl.app.swing.single.SingleAppController` — owns single-app
 *    state directly.
 *  - `ksl.app.swing.scenario.ScenarioEditBuffer` — adapts a
 *    `ScenarioSpec`-shaped buffer to this contract for the
 *    per-scenario editor window.
 */
interface ConfigurationEditorState {

    /** Probe-time model defaults; drives placeholder text in `ParameterPanel`. */
    val modelDefaults: ExperimentRunDefaults

    /** Probe-time controls snapshot; drives the rows of `ControlOverridesPanel`. */
    val controlsSnapshot: ModelControlsExport

    /** Probe-time RV snapshot; drives the rows of `RVOverridesPanel`. */
    val rvSnapshot: List<RVParameterData>

    /**
     *  Probe-time author-curated catalog of nominated inputs and outputs, or
     *  `null` when the model supplied none.  Optional metadata: editor panels may
     *  use it to label and feature the salient inputs/outputs, but must behave
     *  exactly as before when it is `null`.  Defaults to `null` so hosts that do
     *  not supply a catalog need not override it.
     */
    val modelCatalog: ModelCatalog?
        get() = null

    /** Pending run-parameter overrides. */
    val runOverrides: StateFlow<ExperimentRunOverrides>

    /** Pending control overrides (all three families). */
    val controlOverrides: StateFlow<ModelControlsExport>

    /** Pending RV-parameter overrides. */
    val rvOverrides: StateFlow<List<RVParameterOverride>>

    /** Validation feedback bus; panels decorate field/row state from it. */
    val validationBus: ValidationFeedbackBus

    /** EDT-confined scope for panel-internal collectors. */
    val edtScope: CoroutineScope

    // ── Mutators ─────────────────────────────────────────────────────────

    fun updateRunOverride(transform: (ExperimentRunOverrides) -> ExperimentRunOverrides)

    fun setNumericOverride(keyName: String, value: Double)
    fun clearNumericOverride(keyName: String)

    fun setStringOverride(keyName: String, value: String)
    fun clearStringOverride(keyName: String)

    fun setJsonOverride(keyName: String, jsonValue: String)
    fun clearJsonOverride(keyName: String)

    fun setRVOverride(rvName: String, paramName: String, value: Double)
    fun clearRVOverride(rvName: String, paramName: String)
}
