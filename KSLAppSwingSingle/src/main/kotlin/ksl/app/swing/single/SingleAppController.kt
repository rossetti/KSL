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
import ksl.app.editor.DocumentLifecycleController
import ksl.app.editor.RunLifecycleController
import ksl.app.single.results.ReportSaveRecord
import ksl.app.single.results.SingleAppPaths
import ksl.app.config.DatabasePolicy
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RVParameterOverride
import ksl.app.config.ReportFormat
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
) : AutoCloseable, ksl.app.editor.ConfigurationEditorState {

    /** Scope for EDT-confined coroutine work (event forwarding, etc.). */
    override val edtScope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    /** User-wide settings (workspace, recent list).  Real file at `~/.ksl/settings.toml`. */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /** Validation bus.  Empty until parameter-panel wiring lands in N2. */
    override val validationBus: ValidationFeedbackBus = ValidationFeedbackBus()

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
    override val modelDefaults: ExperimentRunDefaults

    /**
     * The model's controls snapshot captured at probe time.
     * Empty (`modelName = appName`, all family lists empty) when
     * the probe fails — see [probeFailure].
     */
    override val controlsSnapshot: ModelControlsExport

    /**
     * The model's random-variable parameter snapshot captured at
     * probe time.  Each entry is one parameter on one parameterized
     * `RandomVariable`; an RV with multiple parameters (e.g. a
     * `NormalRV` with `mean` + `variance`) appears as multiple rows.
     * Empty when the model exposes no parameterized RVs or when the
     * probe build fails — see [probeFailure].
     */
    override val rvSnapshot: List<RVParameterData>

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
     * Workspace subdirectory dedicated to this analysis.  Derived
     * each read so a change to [OutputConfig.analysisName] or to
     * [UserSettingsStore.activeWorkspace] is reflected on the next
     * call without a subscriber.  Delegates to
     * [SingleAppPaths.appWorkspaceDir], which encodes the
     * single-app three-tier fallback: sanitized analysis name when
     * set + non-blank + non-"Untitled", else the (already-sanitized)
     * modelName, else the parent workspace itself.
     */
    val appWorkspace: Path
        get() = SingleAppPaths.appWorkspaceDir(
            activeWorkspace = settingsStore.activeWorkspace(),
            analysisName = myOutputConfig.value.analysisName,
            modelName = modelName
        )

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

    /**
     *  Substrate-owned run-lifecycle bookkeeping.  Composed
     *  (E.5.6); this controller delegates [lastResult] and the
     *  [editedSinceLastSim] flag to this object via the substrate's
     *  `editedSinceLastRun`.  Side effects on edit (this app has
     *  none beyond the flag flip) and on run completion stay on the
     *  controller.
     */
    private val runLifecycle = RunLifecycleController<RunResult>()

    /** Most recently observed terminal [RunResult], or null when none yet. */
    val lastResult: StateFlow<RunResult?> = runLifecycle.lastResult

    private val myRunOverrides = MutableStateFlow(ExperimentRunOverrides())
    /** Pending run-parameter overrides.  Threaded into the ScenarioSpec on [submit]. */
    override val runOverrides: StateFlow<ExperimentRunOverrides> = myRunOverrides.asStateFlow()

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
    override val controlOverrides: StateFlow<ModelControlsExport> = myControlOverrides.asStateFlow()

    private val myRVOverrides = MutableStateFlow<List<RVParameterOverride>>(emptyList())
    /**
     * Pending random-variable parameter overrides.  Each entry pins a
     * specific `(rvName, paramName)` pair to a numeric value; absent
     * keys are left at model defaults by
     * [ksl.utilities.random.rvariable.parameters.RVParameterSetter.changeParameters]
     * at submit time.  Threaded into the `ScenarioSpec.rvOverrides`
     * field on [submit].
     */
    override val rvOverrides: StateFlow<List<RVParameterOverride>> = myRVOverrides.asStateFlow()

    private val myOutputConfig = MutableStateFlow(OutputConfig())
    /**
     * Pending output-options state — database / CSV toggles plus the
     * set of reports to auto-render after the next Run.  Threaded into
     * `RunConfiguration.outputConfig` on [submit].  The
     * `outputDirectory` field is overwritten at submit time with the
     * per-app workspace path (see [submit]) regardless of what's set
     * here, so callers should leave that field null.
     */
    val outputConfig: StateFlow<OutputConfig> = myOutputConfig.asStateFlow()

    private val myRecentReportSaves = MutableStateFlow<List<ReportSaveRecord>>(emptyList())
    /**
     * In-memory history of report files materialised since the most
     * recent Simulate.  Populated both by auto-render (post-simulate)
     * and by manual saves from the Post-Run Reporting tab.  Cleared
     * on Simulate (R1), on resetConfiguration, and on loadConfiguration.
     * Bounded at [MAX_RECENT_REPORT_SAVES]; oldest entries are
     * silently FIFO-evicted regardless of origin.
     *
     * Removing a record (or clearing the whole list) does NOT delete
     * the file on disk — publication is the user's domain, not the
     * controller's.
     */
    val recentReportSaves: StateFlow<List<ReportSaveRecord>> =
        myRecentReportSaves.asStateFlow()

    /**
     *  Substrate-owned file + dirty bookkeeping.  Composed (E.5.2);
     *  this controller delegates [currentFile], [isDirty],
     *  [markSaved], and the file/dirty mutations inside
     *  [resetConfiguration] / [loadConfiguration] to this object.
     *  The app-specific [editedSinceLastSim] / [lastResult] cross-flow
     *  stays on the controller until the run lifecycle decomposition
     *  in E.5.5/6.
     */
    private val documentLifecycle = DocumentLifecycleController()

    /**
     * Path of the configuration file currently associated with the in-memory
     * state, or `null` when the state has not yet been saved or loaded.
     * Updated by [markSaved] and by [loadConfiguration].  The frame uses
     * this to render the current file name in the window title.
     */
    val currentFile: StateFlow<Path?> = documentLifecycle.currentFile

    /**
     * `true` when in-memory configuration has been edited since the last
     * save or load, `false` otherwise.  Every editing mutator on this
     * controller (run-parameter, control, and RV override setters and
     * clearers) flips this to `true`.  [loadConfiguration] and
     * [markSaved] clear it.  The frame uses this to render an unsaved
     * marker (`*`) in the window title.
     */
    val isDirty: StateFlow<Boolean> = documentLifecycle.isDirty

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
     *
     * Re-points to `runLifecycle.editedSinceLastRun` (E.5.6);
     * the host-side name `editedSinceLastSim` is preserved for
     * public-API stability.
     */
    val editedSinceLastSim: StateFlow<Boolean> = runLifecycle.editedSinceLastRun

    private fun markDirty() {
        // Editing mutators flip BOTH flags.  isDirty tracks "differs
        // from saved file"; editedSinceLastSim tracks "differs from
        // what was last simulated".  Saving clears isDirty only;
        // a new terminal result clears editedSinceLastSim only.
        documentLifecycle.markDirty()
        runLifecycle.markEdited()
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
    override fun updateRunOverride(transform: (ExperimentRunOverrides) -> ExperimentRunOverrides) {
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
    override fun setNumericOverride(keyName: String, value: Double) {
        val template = controlsSnapshot.numericControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            numericControls = myControlOverrides.value.numericControls
                .filter { it.keyName != keyName } + template.copy(value = value)
        )
        markDirty()
    }

    /** Removes the numeric override for [keyName], reverting to the model default. */
    override fun clearNumericOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.numericControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(
            numericControls = current.numericControls.filter { it.keyName != keyName }
        )
        markDirty()
    }

    /** Sets the string-control override for [keyName] to [value]. */
    override fun setStringOverride(keyName: String, value: String) {
        val template = controlsSnapshot.stringControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            stringControls = myControlOverrides.value.stringControls
                .filter { it.keyName != keyName } + template.copy(value = value)
        )
        markDirty()
    }

    /** Removes the string override for [keyName]. */
    override fun clearStringOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.stringControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(
            stringControls = current.stringControls.filter { it.keyName != keyName }
        )
        markDirty()
    }

    /** Sets the JSON-control override for [keyName] to [jsonValue]. */
    override fun setJsonOverride(keyName: String, jsonValue: String) {
        val template = controlsSnapshot.jsonControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            jsonControls = myControlOverrides.value.jsonControls
                .filter { it.keyName != keyName } + template.copy(jsonValue = jsonValue)
        )
        markDirty()
    }

    /** Removes the JSON override for [keyName]. */
    override fun clearJsonOverride(keyName: String) {
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
    override fun setRVOverride(rvName: String, paramName: String, value: Double) {
        val knownPair = rvSnapshot.any { it.rvName == rvName && it.paramName == paramName }
        if (!knownPair) return
        val current = myRVOverrides.value
        val without = current.filterNot { it.rvName == rvName && it.paramName == paramName }
        myRVOverrides.value = without + RVParameterOverride(rvName, paramName, value)
        markDirty()
    }

    /** Removes the RV-parameter override for the (rvName, paramName) pair, reverting to model default. */
    override fun clearRVOverride(rvName: String, paramName: String) {
        val current = myRVOverrides.value
        if (current.none { it.rvName == rvName && it.paramName == paramName }) return
        myRVOverrides.value = current.filterNot { it.rvName == rvName && it.paramName == paramName }
        markDirty()
    }

    // ── Output-options mutators ─────────────────────────────────────────────

    /**
     * Toggle the SQLite KSLDatabase observer for the next Run.  Has
     * no effect on the current or any in-flight run.
     */
    fun setEnableKSLDatabase(enabled: Boolean) {
        if (myOutputConfig.value.enableKSLDatabase == enabled) return
        myOutputConfig.value = myOutputConfig.value.copy(enableKSLDatabase = enabled)
        markDirty()
    }

    /**
     * Set the analysis name (identity for output routing).  Names the
     * `<workspace>/reports/<sanitized-analysisName>/` directory tree
     * and the default report filename stem.  Whitespace-only values
     * fall back to the model's [appName] when consumed downstream;
     * the field itself stores whatever the user typed verbatim.
     */
    fun setAnalysisName(name: String) {
        if (myOutputConfig.value.analysisName == name) return
        myOutputConfig.value = myOutputConfig.value.copy(analysisName = name)
        markDirty()
    }

    /**
     * Set the [DatabasePolicy] governing what happens when the
     * KSLDatabase file already exists at submit time.  OVERWRITE
     * replaces the existing file; NEW writes a timestamped sibling.
     * Has no effect when [OutputConfig.enableKSLDatabase] is `false`.
     */
    fun setDatabasePolicy(policy: DatabasePolicy) {
        if (myOutputConfig.value.databasePolicy == policy) return
        myOutputConfig.value = myOutputConfig.value.copy(databasePolicy = policy)
        markDirty()
    }

    /** Toggle per-replication CSV output for the next Run. */
    fun setEnableReplicationCSV(enabled: Boolean) {
        if (myOutputConfig.value.enableReplicationCSV == enabled) return
        myOutputConfig.value = myOutputConfig.value.copy(enableReplicationCSV = enabled)
        markDirty()
    }

    /** Toggle across-replication summary CSV output for the next Run. */
    fun setEnableExperimentCSV(enabled: Boolean) {
        if (myOutputConfig.value.enableExperimentCSV == enabled) return
        myOutputConfig.value = myOutputConfig.value.copy(enableExperimentCSV = enabled)
        markDirty()
    }

    /**
     * Toggle a single [ReportFormat] in the auto-render-after-Simulate
     * set.  Idempotent — enabling an already-enabled format is a no-op.
     */
    fun setReportFormatEnabled(format: ReportFormat, enabled: Boolean) {
        val current = myOutputConfig.value.reports
        val updated = if (enabled) current + format else current - format
        if (updated == current) return
        myOutputConfig.value = myOutputConfig.value.copy(reports = updated)
        markDirty()
    }

    /**
     * Append [record] to [recentReportSaves], FIFO-evicting beyond
     * [MAX_RECENT_REPORT_SAVES].  Records are prepended (most-recent
     * first) so the UI doesn't have to reverse the list.
     */
    fun addReportSaveRecord(record: ReportSaveRecord) {
        val updated = (listOf(record) + myRecentReportSaves.value).take(MAX_RECENT_REPORT_SAVES)
        myRecentReportSaves.value = updated
    }

    /** Remove the record at [index] from [recentReportSaves].  No-op
     *  when out of range.  The file on disk is NOT deleted. */
    fun removeReportSaveRecord(index: Int) {
        val list = myRecentReportSaves.value
        if (index !in list.indices) return
        myRecentReportSaves.value = list.toMutableList().also { it.removeAt(index) }
    }

    /** Empty [recentReportSaves].  Files on disk are NOT deleted. */
    fun clearReportSaves() {
        if (myRecentReportSaves.value.isEmpty()) return
        myRecentReportSaves.value = emptyList()
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
        //
        // outputConfig is included.  The outputDirectory field is
        // *not* persisted (it's per-installation, computed from the
        // workspace) — it's blanked here and re-applied at submit time.
        RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = appName,
                    modelReference = ModelReference.Embedded(appName),
                    runOverrides = myRunOverrides.value,
                    controlOverrides = myControlOverrides.value,
                    rvOverrides = myRVOverrides.value
                )
            ),
            outputConfig = myOutputConfig.value.copy(outputDirectory = null)
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
        // Restore outputConfig but blank outputDirectory — it's an
        // install-local path the submit-time wiring re-computes.
        myOutputConfig.value = config.outputConfig.copy(outputDirectory = null)
        // Clear dirty + edited-since-last-sim + lastResult AFTER the
        // StateFlow assignments so any listener-triggered state flip
        // gets overwritten.  Loading a fresh configuration starts a
        // virgin session: no unsaved changes, no pending edits, and
        // any prior run's results are no longer related to this
        // configuration.
        documentLifecycle.clearDirty()
        runLifecycle.reset()
        myRecentReportSaves.value = emptyList()
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
        myOutputConfig.value = OutputConfig()
        documentLifecycle.reset()
        // Reset to defaults means a virgin session: any prior run's
        // result no longer applies to what's now in the editor, so
        // clear lastResult + editedSinceLastSim and let the badge
        // fall back to "Defaults".  The Reports tab also disables
        // because no snapshot exists.
        runLifecycle.reset()
        myRecentReportSaves.value = emptyList()
    }

    /**
     * Record that the current state has been persisted to [path].  Sets
     * [currentFile] and clears [isDirty].  Called by the frame's *Save* /
     * *Save As…* handlers after a successful write.
     */
    fun markSaved(path: Path) {
        documentLifecycle.markSaved(path)
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
        // R1: the new run will produce a fresh snapshot that replaces
        // whatever the prior run wrote.  Drop any in-memory record of
        // reports materialised against the prior snapshot so the
        // Post-Run Reporting tab's "Recent saves" doesn't confusingly
        // mix two runs.  Files on disk are not touched.
        myRecentReportSaves.value = emptyList()
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
            // Merge the analyst's outputConfig flags with the
            // per-app outputDirectory we compute here.  The analyst
            // owns the toggles; the framework owns the path.
            outputConfig = myOutputConfig.value.copy(outputDirectory = outputDirectoryString)
        )
        val handle = session.submit(RunSpec.Single(config))
        currentHandle = handle
        myRunningFlow.value = true

        edtScope.launch {
            handle.events.collect { ev -> myEventFlow.emit(ev) }
        }
        edtScope.launch {
            val result = handle.result.await()
            // The just-completed run reflects the configuration as
            // submitted; record the result AND clear the "edited
            // since last sim" flag (one atomic substrate call) so
            // the status badge falls back from "Edited / Previous
            // run: …" to "Completed (etc.) …" until the next
            // override edit.
            runLifecycle.markRunCompleted(result)
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
        ksl.utilities.io.StdoutCapture.uninstall()
    }

    companion object {
        /** Bound for [recentReportSaves].  Older records are silently
         *  FIFO-evicted on each new append, matching the Experiment
         *  app's regression-fits cache contract. */
        const val MAX_RECENT_REPORT_SAVES: Int = 10

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
