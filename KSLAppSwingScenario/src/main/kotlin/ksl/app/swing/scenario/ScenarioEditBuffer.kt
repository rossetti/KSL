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

package ksl.app.swing.scenario

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import ksl.app.bundle.LoadedBundle
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RVParameterOverride
import ksl.app.config.ScenarioSpec
import ksl.app.swing.common.editor.ConfigurationEditorState
import ksl.app.validation.ValidationFeedbackBus
import ksl.controls.ModelControlsExport
import ksl.controls.experiments.ExperimentRunDefaults
import ksl.simulation.ModelDescriptor
import ksl.utilities.random.rvariable.parameters.RVParameterData
import kotlin.time.Duration.Companion.minutes

/**
 *  Per-scenario edit-buffer adapting a [ScenarioSpec] to the
 *  reusable [ConfigurationEditorState] contract used by
 *  [ksl.app.swing.common.editor.ParameterPanel],
 *  [ksl.app.swing.common.editor.ControlOverridesPanel], and
 *  [ksl.app.swing.common.editor.RVOverridesPanel].
 *
 *  The buffer is constructed from:
 *  - the [ScenarioSpec] being edited (provides initial overrides),
 *  - a [ModelDescriptor] probed once at construction time (provides
 *    the read-only snapshots the editor panels render against).
 *
 *  On commit the caller asks the buffer for [toSpec], which materialises
 *  the current StateFlow values back into a new [ScenarioSpec] (the
 *  scenario name and CSV toggles travel separately — they live on the
 *  buffer but the panels never touch them).
 *
 *  ## Failure mode
 *
 *  When probing fails (e.g. the scenario's bundle is no longer
 *  loaded), the caller should construct via [empty] — that variant
 *  uses [SAFE_FALLBACK_DEFAULTS] and an empty controls/RV snapshot
 *  so the editor still opens and lets the analyst tweak the name and
 *  CSV toggles, even when the model can't be probed.
 */
class ScenarioEditBuffer private constructor(
    spec: ScenarioSpec,
    override val modelDefaults: ExperimentRunDefaults,
    override val controlsSnapshot: ModelControlsExport,
    override val rvSnapshot: List<RVParameterData>
) : ConfigurationEditorState, AutoCloseable {

    /** Editable scenario name. */
    private val myName = MutableStateFlow(spec.name)
    val name: StateFlow<String> = myName.asStateFlow()

    /** Per-scenario CSV toggles. */
    private val myEnableReplicationCSV = MutableStateFlow(spec.enableReplicationCSV)
    val enableReplicationCSV: StateFlow<Boolean> = myEnableReplicationCSV.asStateFlow()

    private val myEnableExperimentCSV = MutableStateFlow(spec.enableExperimentCSV)
    val enableExperimentCSV: StateFlow<Boolean> = myEnableExperimentCSV.asStateFlow()

    /** Read-only ref the editor displays in its header. */
    val modelReference: ModelReference = spec.modelReference

    /** Reading the spec's overrides as initial values. */
    private val myRunOverrides = MutableStateFlow(spec.runOverrides ?: ExperimentRunOverrides.EMPTY)
    override val runOverrides: StateFlow<ExperimentRunOverrides> = myRunOverrides.asStateFlow()

    private val myControlOverrides = MutableStateFlow(spec.controlOverrides)
    override val controlOverrides: StateFlow<ModelControlsExport> = myControlOverrides.asStateFlow()

    private val myRvOverrides = MutableStateFlow(spec.rvOverrides)
    override val rvOverrides: StateFlow<List<RVParameterOverride>> = myRvOverrides.asStateFlow()

    override val validationBus: ValidationFeedbackBus = ValidationFeedbackBus()
    override val edtScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Swing + SupervisorJob())

    /** `true` once the user touched anything; cleared on construction. */
    private val myIsDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = myIsDirty.asStateFlow()

    private fun markDirty() {
        if (!myIsDirty.value) myIsDirty.value = true
    }

    fun setName(value: String) {
        if (myName.value == value) return
        myName.value = value
        markDirty()
    }

    fun setEnableReplicationCSV(enabled: Boolean) {
        if (myEnableReplicationCSV.value == enabled) return
        myEnableReplicationCSV.value = enabled
        markDirty()
    }

    fun setEnableExperimentCSV(enabled: Boolean) {
        if (myEnableExperimentCSV.value == enabled) return
        myEnableExperimentCSV.value = enabled
        markDirty()
    }

    // ── ConfigurationEditorState mutators ──────────────────────────────────

    override fun updateRunOverride(transform: (ExperimentRunOverrides) -> ExperimentRunOverrides) {
        val updated = transform(myRunOverrides.value)
        if (updated != myRunOverrides.value) {
            myRunOverrides.value = updated
            markDirty()
        }
    }

    override fun setNumericOverride(keyName: String, value: Double) {
        val template = controlsSnapshot.numericControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            numericControls = myControlOverrides.value.numericControls
                .filter { it.keyName != keyName } + template.copy(value = value)
        )
        markDirty()
    }

    override fun clearNumericOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.numericControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(
            numericControls = current.numericControls.filter { it.keyName != keyName }
        )
        markDirty()
    }

    override fun setStringOverride(keyName: String, value: String) {
        val template = controlsSnapshot.stringControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            stringControls = myControlOverrides.value.stringControls
                .filter { it.keyName != keyName } + template.copy(value = value)
        )
        markDirty()
    }

    override fun clearStringOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.stringControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(
            stringControls = current.stringControls.filter { it.keyName != keyName }
        )
        markDirty()
    }

    override fun setJsonOverride(keyName: String, jsonValue: String) {
        val template = controlsSnapshot.jsonControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            jsonControls = myControlOverrides.value.jsonControls
                .filter { it.keyName != keyName } + template.copy(jsonValue = jsonValue)
        )
        markDirty()
    }

    override fun clearJsonOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.jsonControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(
            jsonControls = current.jsonControls.filter { it.keyName != keyName }
        )
        markDirty()
    }

    override fun setRVOverride(rvName: String, paramName: String, value: Double) {
        val known = rvSnapshot.any { it.rvName == rvName && it.paramName == paramName }
        if (!known) return
        val current = myRvOverrides.value
        myRvOverrides.value = current.filterNot { it.rvName == rvName && it.paramName == paramName } +
            RVParameterOverride(rvName, paramName, value)
        markDirty()
    }

    override fun clearRVOverride(rvName: String, paramName: String) {
        val current = myRvOverrides.value
        if (current.none { it.rvName == rvName && it.paramName == paramName }) return
        myRvOverrides.value = current.filterNot { it.rvName == rvName && it.paramName == paramName }
        markDirty()
    }

    /**
     *  Materialize the current buffer state as a new [ScenarioSpec],
     *  preserving the original [modelReference].  `runOverrides` is
     *  set to `null` when no fields differ from defaults.
     */
    fun toSpec(): ScenarioSpec = ScenarioSpec(
        name = myName.value,
        modelReference = modelReference,
        runOverrides = myRunOverrides.value.takeUnless { it.isEmpty },
        controlOverrides = myControlOverrides.value,
        rvOverrides = myRvOverrides.value,
        modelConfiguration = null,
        skipOnRun = false,
        enableReplicationCSV = myEnableReplicationCSV.value,
        enableExperimentCSV = myEnableExperimentCSV.value
    )

    override fun close() {
        edtScope.cancel("ScenarioEditBuffer closed")
    }

    companion object {

        /**
         *  Build a buffer using the supplied [descriptor]'s probe data.
         *  The original [spec] supplies initial override values.
         */
        fun fromDescriptor(spec: ScenarioSpec, descriptor: ModelDescriptor): ScenarioEditBuffer =
            ScenarioEditBuffer(
                spec = spec,
                modelDefaults = descriptor.experimentRunDefaults,
                controlsSnapshot = descriptor.controls,
                rvSnapshot = descriptor.rvParameterData
            )

        /**
         *  Build a buffer with safe-fallback defaults and empty
         *  snapshots — used when probing fails (missing bundle,
         *  non-bundled model reference, descriptor extraction error).
         *  The editor still opens so the user can rename the scenario
         *  and tweak CSV toggles.
         */
        fun empty(spec: ScenarioSpec): ScenarioEditBuffer =
            ScenarioEditBuffer(
                spec = spec,
                modelDefaults = SAFE_FALLBACK_DEFAULTS,
                controlsSnapshot = ModelControlsExport(modelName = ""),
                rvSnapshot = emptyList()
            )

        /**
         *  Convenience: resolve [spec.modelReference] against
         *  [bundles] and probe the model's descriptor.  Falls back to
         *  [empty] when the reference is unbundled or the bundle is
         *  missing.  Throws are caught and folded into the fallback.
         */
        fun probe(spec: ScenarioSpec, bundles: List<LoadedBundle>): ScenarioEditBuffer {
            val ref = spec.modelReference as? ModelReference.ByBundleAndModelId
                ?: return empty(spec)
            val bundle = bundles.firstOrNull { it.bundle.bundleId == ref.bundleId }
                ?: return empty(spec)
            return try {
                fromDescriptor(spec, bundle.descriptorFor(ref.modelId))
            } catch (_: Throwable) {
                empty(spec)
            }
        }

        /** Conservative fallback defaults; mirrors `SingleAppController.SAFE_FALLBACK_DEFAULTS`. */
        val SAFE_FALLBACK_DEFAULTS: ExperimentRunDefaults = ExperimentRunDefaults(
            numberOfReplications = 1,
            numChunks = 1,
            startingRepId = 1,
            lengthOfReplication = 1.0,
            lengthOfReplicationWarmUp = 0.0,
            replicationInitializationOption = true,
            maximumAllowedExecutionTimePerReplication = 5.minutes,
            resetStartStreamOption = true,
            advanceNextSubStreamOption = true,
            antitheticOption = false,
            numberOfStreamAdvancesPriorToRunning = 0,
            garbageCollectAfterReplicationFlag = false
        )
    }
}
