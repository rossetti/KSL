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

package ksl.app.swing.single

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RVParameterOverride
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.settings.UserSettingsStore
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import ksl.controls.ControlData
import ksl.controls.JsonControlData
import ksl.controls.ModelControlsExport
import ksl.controls.StringControlData
import ksl.controls.experiments.ExperimentRunDefaults
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rvariable.parameters.RVParameterData
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Internal state-holder for one `kslSingleApp(...)` instance.
 * Owns the per-document model provider, validation bus, settings
 * store, run-event flow, running-state flag, and the in-flight
 * [RunHandle] when one exists.
 *
 * Not thread-safe — all mutation expected on the Swing EDT.
 * Coroutine plumbing internally schedules onto [Dispatchers.Swing]
 * so the [eventFlow] and [runningFlow] emissions reach widgets on
 * the right thread.
 *
 * @param appName window title; also used as the embedded model
 *   identifier when constructing `ModelReference.Embedded`.
 * @param modelBuilder the developer's named [ModelBuilderIfc].
 */
class SingleAppController(
    val appName: String,
    val modelBuilder: ModelBuilderIfc
) : AutoCloseable {

    /** Scope for EDT-confined coroutine work (event forwarding, etc.). */
    val edtScope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    /** User-wide settings (workspace, recent list).  Real file at `~/.ksl/settings.toml`. */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /** Validation bus.  Empty until parameter-panel wiring lands in N2. */
    val validationBus: ValidationFeedbackBus = ValidationFeedbackBus()

    private val provider: MapModelProvider = MapModelProvider(appName, modelBuilder)
    private val session: KSLAppSession = KSLAppSession(provider = provider)

    /**
     * Model defaults captured by probing the developer's
     * [ModelBuilderIfc] once at construction.  Used as the
     * placeholder values rendered by the default parameter panel.
     *
     * If the probe build throws, this falls back to a safe
     * placeholder shape ([SAFE_FALLBACK_DEFAULTS]); the underlying
     * Throwable is exposed as [probeFailure] so the frame can
     * surface a notification.
     */
    val modelDefaults: ExperimentRunDefaults

    /**
     * The model's controls snapshot captured at probe time.
     * Empty (`modelName = appName`, all family lists empty) when
     * the probe fails — see [probeFailure].
     */
    val controlsSnapshot: ModelControlsExport

    /**
     * The model's random-variable parameter snapshot captured at
     * probe time.  Each entry is one parameter on one parameterized
     * `RandomVariable`; an RV with multiple parameters (e.g. a
     * `NormalRV` with `mean` + `variance`) appears as multiple rows.
     * Empty when the model exposes no parameterized RVs or when the
     * probe build fails — see [probeFailure].
     */
    val rvSnapshot: List<RVParameterData>

    /**
     * Sanitized model name captured at probe time, suitable for use
     * as a directory segment under the user's workspace.  Equals
     * `model.name` from the probe build, which is itself the
     * `simulationName` argument passed to `Model(...)` with spaces
     * replaced by underscores (see `ksl.simulation.Model`).
     * Empty when the probe failed.
     */
    val modelName: String

    /**
     * `null` when the probe build succeeded; the underlying
     * Throwable otherwise.  Frames surface this as an ERROR
     * notification when the app starts up so the developer can
     * fix the broken builder.
     */
    val probeFailure: Throwable?

    init {
        val probe = probeModel()
        this.modelDefaults = probe.defaults
        this.controlsSnapshot = probe.controlsSnapshot
        this.rvSnapshot = probe.rvSnapshot
        this.modelName = probe.modelName
        this.probeFailure = probe.failure
    }

    /**
     * Workspace subdirectory dedicated to this app.  Equal to
     * `settingsStore.activeWorkspace().resolve(modelName)` when the
     * probe succeeded.  All per-app files (saved configurations,
     * model runtime output, rendered reports) live under here.  Read
     * each access — the underlying `activeWorkspace()` is permitted
     * to change between calls.
     *
     * When the probe failed (`modelName` is empty) this falls back
     * to the parent workspace itself so file dialogs still have a
     * valid starting point.
     */
    val appWorkspace: Path
        get() {
            val parent = settingsStore.activeWorkspace()
            return if (modelName.isNotEmpty()) parent.resolve(modelName) else parent
        }

    /**
     * Recomputes pre-run validation findings from the current
     * [runOverrides] + [modelDefaults] and publishes them to
     * [validationBus].  Called on construction and on every
     * `runOverrides` emission.
     *
     * Currently surfaces one finding:
     *
     *  - **Infinite-horizon, no timeout** (WARNING) — when the
     *    effective `lengthOfReplication` is infinite AND the
     *    model's `maximumAllowedExecutionTimePerReplication` is
     *    `Duration.ZERO`.  Mirrors the engine-side warning
     *    emitted by `Model.ReplicationProcess.initializeIterations`
     *    so the user sees it before clicking Run.  The user can
     *    clear it by overriding `lengthOfReplication` to a finite
     *    value, or accept the warning and rely on the model's
     *    internal stopping mechanism (e.g. `addCountLimitStoppingAction`,
     *    `stopReplication()`, or `endSimulation()`).
     */
    private fun computeValidation(): ValidationResult {
        val warnings = mutableListOf<FieldError>()
        val effectiveLength = myRunOverrides.value.lengthOfReplication
            ?: modelDefaults.lengthOfReplication
        val maxTime = modelDefaults.maximumAllowedExecutionTimePerReplication
        if (effectiveLength.isInfinite() && maxTime == Duration.ZERO) {
            warnings.add(
                FieldError(
                    path = "scenarios[0].runOverrides.lengthOfReplication",
                    message = "Length of replication is infinite and no maximum execution time is set. " +
                        "The model must include a stopping mechanism " +
                        "(e.g. addCountLimitStoppingAction on a Response/Counter, or call stopReplication() / endSimulation() " +
                        "from inside the model) or the run will not terminate.",
                    severity = ValidationSeverity.WARNING,
                    code = "INFINITE_HORIZON_NO_TIMEOUT"
                )
            )
        }
        return ValidationResult(warnings = warnings)
    }

    /**
     * Aggregate of everything one probe build produces for the GUI:
     * the run-parameter defaults, the controls snapshot, the
     * RV-parameter snapshot, and (when the build threw) the failure.
     * Grouped as a single result object so [probeModel] can stay
     * one-shot and the `init` block can destructure cleanly.
     */
    private data class ProbeResult(
        val defaults: ExperimentRunDefaults,
        val controlsSnapshot: ModelControlsExport,
        val rvSnapshot: List<RVParameterData>,
        val modelName: String,
        val failure: Throwable?
    )

    private fun probeModel(): ProbeResult = try {
        val model: Model = modelBuilder.build(null, null)
        val descriptor = model.modelDescriptor()
        ProbeResult(
            defaults = descriptor.experimentRunDefaults,
            controlsSnapshot = descriptor.controls,
            rvSnapshot = descriptor.rvParameterData,
            modelName = model.name,
            failure = null
        )
    } catch (t: Throwable) {
        logger.warn(t) { "ModelBuilder probe build failed; falling back to safe defaults." }
        ProbeResult(
            defaults = SAFE_FALLBACK_DEFAULTS,
            controlsSnapshot = ModelControlsExport(modelName = appName),
            rvSnapshot = emptyList(),
            modelName = "",
            failure = t
        )
    }

    private val myEventFlow = MutableSharedFlow<RunEvent>(replay = 0, extraBufferCapacity = 256)
    /** Hot flow of run events, fed from the active [RunHandle]. */
    val eventFlow: SharedFlow<RunEvent> = myEventFlow.asSharedFlow()

    private val myRunningFlow = MutableStateFlow(false)
    /** True while a run is in flight. */
    val runningFlow: StateFlow<Boolean> = myRunningFlow.asStateFlow()

    private val myLastResult = MutableStateFlow<RunResult?>(null)
    /** Most recently observed terminal [RunResult], or null when none yet. */
    val lastResult: StateFlow<RunResult?> = myLastResult.asStateFlow()

    private val myRunOverrides = MutableStateFlow(ExperimentRunOverrides())
    /** Pending run-parameter overrides.  Threaded into the ScenarioSpec on [submit]. */
    val runOverrides: StateFlow<ExperimentRunOverrides> = myRunOverrides.asStateFlow()

    // Seeded with the probe-captured modelName so the orchestrator's
    // Controls.importAll() doesn't emit CONTROL_MODEL_NAME_MISMATCH.  The
    // probe ran in the first init block (above) so controlsSnapshot is
    // already populated by the time this property initializes.  When the
    // probe failed, the fallback snapshot has modelName = appName but its
    // control lists are empty, so importAll is skipped entirely and the
    // modelName never matters.
    private val myControlOverrides = MutableStateFlow(
        ModelControlsExport(modelName = controlsSnapshot.modelName)
    )
    /**
     * Pending control overrides.  Each per-family list holds **only** the
     * controls the analyst has explicitly overridden — entries absent from
     * the snapshot are left at model defaults by
     * [ksl.controls.Controls.importAll] at submit time.  Threaded into the
     * `ScenarioSpec.controlOverrides` field on [submit].
     */
    val controlOverrides: StateFlow<ModelControlsExport> = myControlOverrides.asStateFlow()

    private val myRVOverrides = MutableStateFlow<List<RVParameterOverride>>(emptyList())
    /**
     * Pending random-variable parameter overrides.  Each entry pins a
     * specific `(rvName, paramName)` pair to a numeric value; absent
     * keys are left at model defaults by
     * [ksl.utilities.random.rvariable.parameters.RVParameterSetter.changeParameters]
     * at submit time.  Threaded into the `ScenarioSpec.rvOverrides`
     * field on [submit].
     */
    val rvOverrides: StateFlow<List<RVParameterOverride>> = myRVOverrides.asStateFlow()

    private val myCurrentFile = MutableStateFlow<Path?>(null)
    /**
     * Path of the configuration file currently associated with the in-memory
     * state, or `null` when the state has not yet been saved or loaded.
     * Updated by [markSaved] and by [loadConfiguration].  The frame uses
     * this to render the current file name in the window title.
     */
    val currentFile: StateFlow<Path?> = myCurrentFile.asStateFlow()

    private val myIsDirty = MutableStateFlow(false)
    /**
     * `true` when in-memory configuration has been edited since the last
     * save or load, `false` otherwise.  Every editing mutator on this
     * controller (run-parameter, control, and RV override setters and
     * clearers) flips this to `true`.  [loadConfiguration] and
     * [markSaved] clear it.  The frame uses this to render an unsaved
     * marker (`*`) in the window title.
     */
    val isDirty: StateFlow<Boolean> = myIsDirty.asStateFlow()

    private val myEditedSinceLastSim = MutableStateFlow(false)
    /**
     * `true` when the in-memory configuration has been edited *since*
     * the last completed simulation.  Distinct from [isDirty], which
     * tracks "differs from the saved file" — saving clears [isDirty]
     * but does NOT clear this flag because saving has nothing to do
     * with simulating.  Resetting to defaults also leaves this flag
     * meaningful (the editor now differs from what was last
     * simulated), though `resetConfiguration()` also clears
     * [lastResult] so the badge falls to *Defaults* anyway.
     *
     * Set by every editing mutator (run-parameter, control, and RV
     * override setters and clearers, plus this flag is left untouched
     * by saves).  Cleared when a new terminal result arrives —
     * the just-completed run reflects the configuration as submitted.
     */
    val editedSinceLastSim: StateFlow<Boolean> = myEditedSinceLastSim.asStateFlow()

    private fun markDirty() {
        // Editing mutators flip BOTH flags.  isDirty tracks "differs
        // from saved file"; editedSinceLastSim tracks "differs from
        // what was last simulated".  Saving clears isDirty only;
        // a new terminal result clears editedSinceLastSim only.
        if (!myIsDirty.value) myIsDirty.value = true
        if (!myEditedSinceLastSim.value) myEditedSinceLastSim.value = true
    }

    private var currentHandle: RunHandle? = null

    init {
        // Initial validation pass + subscribe to runOverrides so the bus
        // reflects current state without the caller having to recompute.
        validationBus.publish(computeValidation())
        edtScope.launch {
            myRunOverrides
                .onEach { validationBus.publish(computeValidation()) }
                .collect { /* no-op terminal */ }
        }
    }

    /**
     * Mutates the pending [runOverrides] via the supplied transform.
     * Typical use from a parameter-panel field:
     *
     * ```kotlin
     * controller.updateRunOverride { it.copy(numberOfReplications = newValue) }
     * ```
     */
    fun updateRunOverride(transform: (ExperimentRunOverrides) -> ExperimentRunOverrides) {
        val updated = transform(myRunOverrides.value)
        if (updated != myRunOverrides.value) {
            myRunOverrides.value = updated
            markDirty()
        }
    }

    // ── Control overrides — per-family mutators ────────────────────────────

    /**
     * Sets the numeric-control override for [keyName] to [value].  The
     * existing snapshot entry for that key (if any) is captured from
     * [controlsSnapshot] for its metadata (bounds, element ids) and the
     * value field replaced.  If [keyName] is not in the model snapshot,
     * the call is a no-op (defensive — the GUI should only offer keys
     * the model exposes).
     */
    fun setNumericOverride(keyName: String, value: Double) {
        val template = controlsSnapshot.numericControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            numericControls = myControlOverrides.value.numericControls
                .filter { it.keyName != keyName } + template.copy(value = value)
        )
        markDirty()
    }

    /** Removes the numeric override for [keyName], reverting to the model default. */
    fun clearNumericOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.numericControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(
            numericControls = current.numericControls.filter { it.keyName != keyName }
        )
        markDirty()
    }

    /** Sets the string-control override for [keyName] to [value]. */
    fun setStringOverride(keyName: String, value: String) {
        val template = controlsSnapshot.stringControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            stringControls = myControlOverrides.value.stringControls
                .filter { it.keyName != keyName } + template.copy(value = value)
        )
        markDirty()
    }

    /** Removes the string override for [keyName]. */
    fun clearStringOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.stringControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(
            stringControls = current.stringControls.filter { it.keyName != keyName }
        )
        markDirty()
    }

    /** Sets the JSON-control override for [keyName] to [jsonValue]. */
    fun setJsonOverride(keyName: String, jsonValue: String) {
        val template = controlsSnapshot.jsonControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            jsonControls = myControlOverrides.value.jsonControls
                .filter { it.keyName != keyName } + template.copy(jsonValue = jsonValue)
        )
        markDirty()
    }

    /** Removes the JSON override for [keyName]. */
    fun clearJsonOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.jsonControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(
            jsonControls = current.jsonControls.filter { it.keyName != keyName }
        )
        markDirty()
    }

    // ── RV parameter overrides — mutators ───────────────────────────────────

    /**
     * Sets the RV-parameter override identified by [rvName] + [paramName]
     * to [value].  No-op when the pair is not present in [rvSnapshot]
     * (defensive — the GUI should only offer keys the model exposes).
     * If an override already exists for the pair, it is replaced.
     */
    fun setRVOverride(rvName: String, paramName: String, value: Double) {
        val knownPair = rvSnapshot.any { it.rvName == rvName && it.paramName == paramName }
        if (!knownPair) return
        val current = myRVOverrides.value
        val without = current.filterNot { it.rvName == rvName && it.paramName == paramName }
        myRVOverrides.value = without + RVParameterOverride(rvName, paramName, value)
        markDirty()
    }

    /** Removes the RV-parameter override for the (rvName, paramName) pair, reverting to model default. */
    fun clearRVOverride(rvName: String, paramName: String) {
        val current = myRVOverrides.value
        if (current.none { it.rvName == rvName && it.paramName == paramName }) return
        myRVOverrides.value = current.filterNot { it.rvName == rvName && it.paramName == paramName }
        markDirty()
    }

    // ── Configuration snapshot / load / save ────────────────────────────────

    /**
     * Outcome of [loadConfiguration]:
     *  - [Loaded] — populated successfully, possibly with a non-fatal note
     *    (e.g. the loaded `modelReference.modelName` doesn't match
     *    `appName`).  The note is surfaced as a notification by the frame
     *    and the user can decide whether to keep the loaded state.
     *  - [Rejected] — the configuration was structurally unloadable
     *    (zero scenarios, malformed input, etc.).  The controller state
     *    is left unchanged.
     */
    sealed class LoadResult {
        data class Loaded(val warning: String? = null) : LoadResult()
        data class Rejected(val reason: String) : LoadResult()
    }

    /**
     * Snapshot the current in-memory editor state as a [RunConfiguration]
     * with a single [ScenarioSpec].  Suitable for TOML serialization via
     * [ksl.app.config.RunConfigurationToml.encode].  Pure read — does not
     * mutate the controller, does not clear [isDirty].
     */
    fun currentConfiguration(): RunConfiguration =
        // Always emit a non-null runOverrides so the encoded TOML carries
        // an empty `[scenarios.runOverrides]` section even when nothing
        // has been edited.  Combined with `explicitNulls = false` in
        // RunConfigurationToml, an unedited override emits the section
        // header with no body — self-documenting hint that the section
        // exists and which fields can be added, without 12 explicit-null
        // lines.  Edited fields appear under the header; absent fields
        // are simply not written.
        RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = appName,
                    modelReference = ModelReference.Embedded(appName),
                    runOverrides = myRunOverrides.value,
                    controlOverrides = myControlOverrides.value,
                    rvOverrides = myRVOverrides.value
                )
            )
        )

    /**
     * Replace the in-memory editor state with the first scenario from
     * [config].  Clears [isDirty] on success — the just-loaded state is by
     * definition equivalent to the file it came from.  Does not change
     * [currentFile]; callers (typically the *Open* flow) should call
     * [markSaved] separately after a successful load.
     *
     * Returns a [LoadResult] describing the outcome.  A
     * `modelReference.modelName` that does not match this controller's
     * [appName] is *not* a hard error — many shared configurations
     * legitimately move between apps that wrap the same model — so the
     * load proceeds with a [LoadResult.Loaded] carrying a warning.
     */
    fun loadConfiguration(config: RunConfiguration): LoadResult {
        val scenario = config.scenarios.firstOrNull()
            ?: return LoadResult.Rejected("Configuration has no scenarios.")
        val ref = scenario.modelReference
        val warning: String? = if (ref is ModelReference.Embedded && ref.modelName != appName) {
            "Loaded modelReference '${ref.modelName}' does not match this app's '$appName'. " +
                "Overrides applied to whatever names match the current model."
        } else null

        myRunOverrides.value = scenario.runOverrides ?: ExperimentRunOverrides()
        myControlOverrides.value = scenario.controlOverrides
        myRVOverrides.value = scenario.rvOverrides
        // Clear dirty + edited-since-last-sim + lastResult AFTER the
        // StateFlow assignments so any listener-triggered state flip
        // gets overwritten.  Loading a fresh configuration starts a
        // virgin session: no unsaved changes, no pending edits, and
        // any prior run's results are no longer related to this
        // configuration.
        myIsDirty.value = false
        myEditedSinceLastSim.value = false
        myLastResult.value = null
        return LoadResult.Loaded(warning)
    }

    /**
     * Reset editor state to empty defaults — equivalent to *File → New*.
     * Clears [currentFile] and [isDirty].  Validation will subsequently
     * re-run (via the existing `runOverrides` subscriber).
     */
    fun resetConfiguration() {
        myRunOverrides.value = ExperimentRunOverrides()
        myControlOverrides.value = ModelControlsExport(modelName = controlsSnapshot.modelName)
        myRVOverrides.value = emptyList()
        myCurrentFile.value = null
        myIsDirty.value = false
        // Reset to defaults means a virgin session: any prior run's
        // result no longer applies to what's now in the editor, so
        // clear lastResult + editedSinceLastSim and let the badge
        // fall back to "Defaults".  The Reports tab also disables
        // because no snapshot exists.
        myEditedSinceLastSim.value = false
        myLastResult.value = null
    }

    /**
     * Record that the current state has been persisted to [path].  Sets
     * [currentFile] and clears [isDirty].  Called by the frame's *Save* /
     * *Save As…* handlers after a successful write.
     */
    fun markSaved(path: Path) {
        myCurrentFile.value = path
        myIsDirty.value = false
    }

    /**
     * Submits a run with the model built fresh from [modelBuilder].
     * The configuration is a one-scenario [RunConfiguration] keyed
     * by [appName] (via [ModelReference.Embedded]); the runtime
     * resolves it through [MapModelProvider].
     *
     * No-op when a run is already in flight.  The caller is
     * expected to disable the *Run* control while [runningFlow] is
     * true.
     */
    fun submit() {
        if (myRunningFlow.value) return
        // Direct the model's runtime output (kslOutput.txt, csvDir,
        // dbDir, plotDir, etc.) into `<appWorkspace>/output/` instead
        // of the JVM launch directory — the orchestrator honors this
        // via OutputConfig.outputDirectory.  Skipped when the probe
        // failed (appWorkspace == parent workspace); the Model's
        // constructor-supplied default is used as a fallback.
        val outputDirectoryString = if (modelName.isNotEmpty()) {
            appWorkspace.resolve("output").toAbsolutePath().normalize().toString()
        } else null
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = appName,
                    modelReference = ModelReference.Embedded(appName),
                    runOverrides = myRunOverrides.value,
                    controlOverrides = myControlOverrides.value,
                    rvOverrides = myRVOverrides.value
                )
            ),
            outputConfig = ksl.app.config.OutputConfig(outputDirectory = outputDirectoryString)
        )
        val handle = session.submit(RunSpec.Single(config))
        currentHandle = handle
        myRunningFlow.value = true

        edtScope.launch {
            handle.events.collect { ev -> myEventFlow.emit(ev) }
        }
        edtScope.launch {
            val result = handle.result.await()
            myLastResult.value = result
            // The just-completed run reflects the configuration as
            // submitted; clear the "edited since last sim" flag so the
            // status badge falls back from "Edited / Previous run: …"
            // to "Completed (etc.) …" until the next override edit.
            myEditedSinceLastSim.value = false
            myRunningFlow.value = false
            currentHandle = null
        }
    }

    /** Cancels the in-flight run, if any. */
    fun cancel() {
        currentHandle?.cancel("Cancelled by user")
    }

    override fun close() {
        currentHandle?.cancel("App closed")
        currentHandle = null
        session.close()
        edtScope.cancel("controller closed")
        // Defensive: if the user enabled "Capture stdout" and closed the
        // window without unchecking it, restore the original streams so
        // a long-lived JVM (IDE Run session) isn't left with a dangling
        // tee pointing at a destroyed Swing component.  StdoutCapture
        // also registers a JVM shutdown hook as a backstop.
        ksl.app.swing.common.runcontrol.StdoutCapture.uninstall()
    }

    companion object {
        /**
         * Defaults used when the developer's `ModelBuilderIfc` throws on the
         * probe build.  Conservative values so the GUI renders something
         * sensible even when the real defaults are unreachable.  See
         * [probeFailure] for surfacing the underlying error.
         */
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
