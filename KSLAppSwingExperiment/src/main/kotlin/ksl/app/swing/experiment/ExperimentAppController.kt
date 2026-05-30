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

package ksl.app.swing.experiment

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.bundle.BundleLoader
import ksl.app.bundle.BundleModelProvider
import ksl.app.bundle.LoadedBundle
import ksl.app.config.DatabasePolicy
import ksl.app.config.ExecutionMode
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.experiment.ControlBinding
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.ExperimentOutputSpec
import ksl.app.config.experiment.RunParameterOverridesSpec
import ksl.app.editor.DocumentLifecycleController
import ksl.app.experiment.regression.RegressionFitRecord
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.ReplicationSpec
import ksl.app.config.experiment.StreamPolicy
import ksl.app.config.experiment.toDesignedExperiment
import ksl.app.config.sanitizeAnalysisName
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.settings.UserSettingsStore
import ksl.controls.experiments.DesignedExperimentIfc
import ksl.controls.experiments.LinearModel
import ksl.simulation.ModelDescriptor
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.statistic.RegressionResultsIfc
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 *  Document state-holder for one `kslExperimentApp(...)` instance.
 *
 *  Owns the in-memory [ExperimentConfiguration] the analyst is
 *  editing: model reference, factor list, design spec, replications,
 *  stream policy, execution mode, output config.  Drives the
 *  file-state surface (`currentFile`, `isDirty`) that powers the File
 *  menu's Save / Save As / Open round-trip and the window title's
 *  `*` dirty marker.
 *
 *  Modelled on `ksl.app.swing.scenario.ScenarioAppController`.  The
 *  two share patterns (bundle library, R1 lifecycle, identity-coupled
 *  document, analysisName-driven output dir, DatabasePolicy) and
 *  diverge where the domains differ (single model vs N scenarios;
 *  factors + design vs scenarios list; per-design-point progress
 *  vs per-scenario).
 *
 *  ### R1 lifecycle
 *  Hitting *Simulate* clears the prior in-memory result, the
 *  retained experiment instance, and any lastRegression fit before
 *  the run kicks off.  If the new run aborts, the user re-runs to
 *  repopulate; the controller does not preserve the previous result
 *  past a Simulate click.
 *
 *  ### Identity-couple
 *  Any structural factor mutation (add / update / delete / move /
 *  clear) drops [lastResult] entirely.  Per the Phase E3 plan, design
 *  points are not addressable by name the way Scenario snapshots
 *  are — a factor change always invalidates the whole result set
 *  rather than trying to preserve subsets.  `clearFactors` also
 *  detaches the file and resets `analysisName` to `"Untitled"`,
 *  mirroring Scenario's Clear-All semantics.
 */
class ExperimentAppController(
    val appName: String
) : AutoCloseable {

    companion object {
        /** Bound for [recentRegressionFits].  Beyond this, the oldest
         *  record is evicted on each new successful fit regardless of
         *  saved state.  Kept small so the table doesn't become a
         *  vertical scroll target — users who need persistence should
         *  Save. */
        const val MAX_RECENT_FITS: Int = 10
    }


    /** Scope for EDT-confined coroutine work. */
    val edtScope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    /** User-wide settings (workspace, recent list). */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /** Sanitized [appName] for filesystem-segment use.  Mirrors
     *  Scenario controller's convention (spaces → underscores).
     *  Delegates to [AppWorkspacePaths.sanitizeAppName]. */
    val appNameSanitized: String = AppWorkspacePaths.sanitizeAppName(appName)

    /** Workspace subdirectory dedicated to this app — see Scenario
     *  controller's KDoc for the same property; rules are identical.
     *  Delegates to [AppWorkspacePaths.appWorkspaceDir]. */
    val appWorkspace: Path
        get() = AppWorkspacePaths.appWorkspaceDir(settingsStore.activeWorkspace(), appName)

    // ── Document state ─────────────────────────────────────────────────────

    private val myModelReference = MutableStateFlow<ModelReference?>(null)
    /** The single model this experiment binds to.  `null` on a fresh
     *  document or after `resetConfiguration`. */
    val modelReference: StateFlow<ModelReference?> = myModelReference.asStateFlow()

    private val myFactors = MutableStateFlow<List<FactorSpec>>(emptyList())
    /** Ordered list of factors in the document. */
    val factors: StateFlow<List<FactorSpec>> = myFactors.asStateFlow()

    private val mySelectedFactorIndex = MutableStateFlow(-1)
    /** Index of the currently-selected factor in [factors], or `-1`
     *  when nothing is selected.  Auto-shifts on add/delete/reorder. */
    val selectedFactorIndex: StateFlow<Int> = mySelectedFactorIndex.asStateFlow()

    private val myDesignSpec = MutableStateFlow<DesignSpec>(DesignSpec.FullFactorial)
    /** How the engine enumerates design points from [factors]. */
    val designSpec: StateFlow<DesignSpec> = myDesignSpec.asStateFlow()

    private val myReplications = MutableStateFlow<ReplicationSpec>(ReplicationSpec.Uniform(10))
    /** Per-design-point replication strategy. */
    val replications: StateFlow<ReplicationSpec> = myReplications.asStateFlow()

    private val myStreamPolicy = MutableStateFlow<StreamPolicy>(StreamPolicy.Independent())
    /** Random-stream policy across design points. */
    val streamPolicy: StateFlow<StreamPolicy> = myStreamPolicy.asStateFlow()

    private val myExecutionMode = MutableStateFlow(ExecutionMode.CONCURRENT)
    /** Sequential vs parallel design-point execution.  Defaults to
     *  CONCURRENT (per the Experiment-app plan — design points are
     *  independent by construction). */
    val executionMode: StateFlow<ExecutionMode> = myExecutionMode.asStateFlow()

    private val myExperimentOutput = MutableStateFlow(ExperimentOutputSpec())
    /** Experiment-app-specific output preferences (per-design-point
     *  output layout for now; richer per-point options when they
     *  land in Phase E11 polish).  Distinct from [outputConfig],
     *  which is shared across Single / Scenario / Experiment apps. */
    val experimentOutput: StateFlow<ExperimentOutputSpec> = myExperimentOutput.asStateFlow()

    private val myRunParameterOverrides = MutableStateFlow(RunParameterOverridesSpec())
    /** Document-level overrides for the model's baked-in run
     *  parameters (length, warm-up).  Per-design-point replications
     *  live on [replications] (`ReplicationSpec`) — not duplicated
     *  here. */
    val runParameterOverrides: StateFlow<RunParameterOverridesSpec> =
        myRunParameterOverrides.asStateFlow()

    private val myOutputConfig = MutableStateFlow(
        OutputConfig(enableKSLDatabase = true)
    )
    /** Document-level output options. */
    val outputConfig: StateFlow<OutputConfig> = myOutputConfig.asStateFlow()

    // ── File state ─────────────────────────────────────────────────────────

    /**
     *  Substrate-owned file + dirty bookkeeping.  Composed (E.5.4);
     *  this controller delegates [currentFile], [isDirty],
     *  [markSaved], and the file/dirty mutations inside
     *  [clearFactors] / [resetConfiguration] / [loadConfiguration]
     *  to this object.  The Experiment-specific [editedSinceLastSim]
     *  cross-flow and the [markSaved] analysis-name-derivation block
     *  stay on the controller.
     */
    private val documentLifecycle = DocumentLifecycleController()

    val currentFile: StateFlow<Path?> = documentLifecycle.currentFile

    val isDirty: StateFlow<Boolean> = documentLifecycle.isDirty

    private val myEditedSinceLastSim = MutableStateFlow(false)
    /** `true` when in-memory state has been edited since the last
     *  successful [submit].  Drives the "stale results" banner the
     *  Phase E4 frame shows above the run toolbar. */
    val editedSinceLastSim: StateFlow<Boolean> = myEditedSinceLastSim.asStateFlow()

    private fun markDirty() {
        documentLifecycle.markDirty()
        if (!myEditedSinceLastSim.value) myEditedSinceLastSim.value = true
    }

    // ── Bundle library ─────────────────────────────────────────────────────

    private val myLoadedBundles = MutableStateFlow<List<LoadedBundle>>(emptyList())
    val loadedBundles: StateFlow<List<LoadedBundle>> = myLoadedBundles.asStateFlow()

    private val myBundleProvider = MutableStateFlow<BundleModelProvider?>(null)
    val bundleProvider: StateFlow<BundleModelProvider?> = myBundleProvider.asStateFlow()

    private val myCurrentModelDescriptor = MutableStateFlow<ModelDescriptor?>(null)
    /**
     *  Descriptor for the currently-selected model — controls,
     *  RV-parameter surface, response names, run defaults.  Populated
     *  whenever [modelReference] is a [ModelReference.ByBundleAndModelId]
     *  whose bundle is present in [loadedBundles].  `null` for
     *  non-bundle refs (`ByProviderId` / `Embedded` — those have no
     *  introspection source from this controller) and for refs whose
     *  bundle isn't loaded yet.
     *
     *  Phase E6 (Factors tab) reads this to populate the binding
     *  picker; Phase E9 (Regression tab) reads `responseNames` to
     *  populate the response dropdown.
     */
    val currentModelDescriptor: StateFlow<ModelDescriptor?> = myCurrentModelDescriptor.asStateFlow()

    init {
        // Auto-discover classpath bundles so a packaged app shows
        // available models immediately.  Mirrors Scenario controller.
        val classpathBundles = BundleLoader.loadFromClasspath()
        if (classpathBundles.isNotEmpty()) updateBundles(classpathBundles)
    }

    private fun updateBundles(bundles: List<LoadedBundle>) {
        myLoadedBundles.value = bundles
        myBundleProvider.value = if (bundles.isEmpty()) null else BundleModelProvider(bundles)
        // Re-resolve the descriptor — a previously-unresolvable ref
        // may now resolve because the bundle it points at just
        // arrived in the loaded set.
        refreshModelDescriptor()
    }

    /**
     *  Resolve [modelReference] against the loaded bundles and
     *  publish the descriptor.  Sets [currentModelDescriptor] to
     *  `null` when:
     *  - the ref is `null` (no model picked yet),
     *  - the ref is `ByProviderId`, `Embedded`, or `ByJar` — those
     *    forms have no descriptor source from this controller
     *    (`BundleModelProvider` could be queried, but the controller
     *    doesn't carry that path today),
     *  - the ref is `ByBundleAndModelId` but the bundle isn't loaded
     *    or the descriptor lookup throws.
     */
    private fun refreshModelDescriptor() {
        val ref = myModelReference.value as? ModelReference.ByBundleAndModelId
        if (ref == null) {
            if (myCurrentModelDescriptor.value != null) myCurrentModelDescriptor.value = null
            return
        }
        val bundle = myLoadedBundles.value.firstOrNull { it.bundle.bundleId == ref.bundleId }
        val descriptor = try {
            bundle?.descriptorFor(ref.modelId)
        } catch (_: Throwable) {
            null
        }
        if (myCurrentModelDescriptor.value != descriptor) {
            myCurrentModelDescriptor.value = descriptor
        }
    }

    sealed class LoadBundleResult {
        data class Loaded(val newBundleIds: List<String>) : LoadBundleResult()
        object NoBundles : LoadBundleResult()
        data class Failed(val reason: String) : LoadBundleResult()
    }

    /**
     *  Load every `KSLModelBundle` from the JAR at [jarPath] and append
     *  the discovered bundles to [loadedBundles].  Same shape as
     *  Scenario controller's loader.
     */
    fun loadBundleJar(jarPath: Path): LoadBundleResult {
        val newBundles = try {
            BundleLoader.loadJar(jarPath)
        } catch (t: Throwable) {
            return LoadBundleResult.Failed(t.message ?: t::class.simpleName ?: "load failed")
        }
        if (newBundles.isEmpty()) return LoadBundleResult.NoBundles
        val existingIds = myLoadedBundles.value.map { it.bundle.bundleId }.toSet()
        val (toAdd, duplicates) = newBundles.partition { it.bundle.bundleId !in existingIds }
        duplicates.forEach { runCatching { it.close() } }
        if (toAdd.isEmpty()) return LoadBundleResult.NoBundles
        updateBundles(myLoadedBundles.value + toAdd)
        return LoadBundleResult.Loaded(toAdd.map { it.bundle.bundleId })
    }

    // ── Run state ──────────────────────────────────────────────────────────

    /** Per-design-point lifecycle status, keyed by `DesignPoint.number`
     *  (1-based — matches the substrate's `RunEvent.DesignPointCompleted.pointId`).
     *
     *  - PENDING    — queued, not yet started
     *  - RUNNING    — coroutine launched, model is building / simulating
     *  - CANCELLING — user-cancelled via [cancelDesignPoint] but the
     *                 substrate hasn't fired the corresponding
     *                 DesignPointCompleted(wasCancelled=true) yet
     *                 (added in E7.11 #7 for immediate per-row feedback)
     *  - COMPLETED  — finished with a snapshot
     *  - FAILED     — finished without a snapshot (model threw)
     *  - CANCELLED  — final terminal state for a cancelled point
     *                 (no DB row written)
     */
    enum class DesignPointStatus { PENDING, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED }

    private val myRunning = MutableStateFlow(false)
    val runningFlow: StateFlow<Boolean> = myRunning.asStateFlow()

    private val myLastResult = MutableStateFlow<RunResult?>(null)
    val lastResult: StateFlow<RunResult?> = myLastResult.asStateFlow()

    private val myEventFlow = MutableSharedFlow<RunEvent>(replay = 0, extraBufferCapacity = 256)
    val eventFlow: SharedFlow<RunEvent> = myEventFlow.asSharedFlow()

    private val myDesignPointStatuses = MutableStateFlow<Map<Int, DesignPointStatus>>(emptyMap())
    /** Per-design-point status indexed by the substrate's pointId.
     *  Populated only while a run is in flight and immediately after;
     *  cleared on the next Simulate. */
    val designPointStatuses: StateFlow<Map<Int, DesignPointStatus>> =
        myDesignPointStatuses.asStateFlow()

    // ── Experiment-specific runtime ────────────────────────────────────────

    private val myExperimentInstance = MutableStateFlow<DesignedExperimentIfc?>(null)
    /** The most recently submitted experiment instance.  Retained
     *  after the run completes so [fitRegression] can call
     *  `regressionResults(...)` on it.  Cleared on Simulate (R1) and
     *  on `resetConfiguration` / `clearFactors`. */
    val experimentInstance: StateFlow<DesignedExperimentIfc?> = myExperimentInstance.asStateFlow()

    private val myRecentRegressionFits = MutableStateFlow<List<RegressionFitRecord>>(emptyList())
    /** Bounded (most-recent-first) history of successful regression fits
     *  produced by [fitRegression].  Bounded to [MAX_RECENT_FITS]
     *  entries; the oldest entries are silently evicted regardless of
     *  whether they have been materialised to disk.  Cleared on
     *  Simulate (R1) and on any structural mutation that drops
     *  [experimentInstance]; saved files on disk are NOT deleted —
     *  publication is the Reports tab's domain.
     *
     *  Each [RegressionFitRecord] is self-contained (the fit is a
     *  numeric object that doesn't reference the underlying
     *  [DesignedExperimentIfc]) — clearing the experiment instance
     *  doesn't strictly require clearing the fits, but R1 semantics
     *  argue for consistency: once the user re-simulates, the
     *  in-memory fits become stale relative to the new data and we'd
     *  rather drop them than leave them around to be confused with
     *  fits against the fresh experiment. */
    val recentRegressionFits: StateFlow<List<RegressionFitRecord>> =
        myRecentRegressionFits.asStateFlow()

    private var currentHandle: RunHandle? = null
    private var session: KSLAppSession? = null

    // ── Document mutators: model + factors ─────────────────────────────────

    /** Set the document's model reference.  Drops [lastResult] +
     *  [experimentInstance] + [recentRegressionFits] (identity-couple:
     *  changing the model invalidates everything). */
    fun setModelReference(ref: ModelReference) {
        if (myModelReference.value == ref) return
        myModelReference.value = ref
        refreshModelDescriptor()
        dropRuntimeArtefacts()
        markDirty()
    }

    /**
     *  Switch the active model AND clear all model-dependent
     *  document state (factors, design spec, run-parameter
     *  overrides) — used by the GUI when the user confirms a model
     *  switch that would leave behind stale bindings to the prior
     *  model's controls.  The output config + analysis name +
     *  execution mode + stream policy survive (they aren't model-
     *  specific).
     */
    fun setModelReferenceAndClear(ref: ModelReference) {
        myModelReference.value = ref
        myFactors.value = emptyList()
        mySelectedFactorIndex.value = -1
        myDesignSpec.value = DesignSpec.FullFactorial
        myReplications.value = ReplicationSpec.Uniform(10)
        myRunParameterOverrides.value = RunParameterOverridesSpec()
        myExperimentOutput.value = ExperimentOutputSpec()
        refreshModelDescriptor()
        dropRuntimeArtefacts()
        markDirty()
    }

    fun addFactor(spec: FactorSpec) {
        require(myFactors.value.none { it.name == spec.name }) {
            "Factor name '${spec.name}' already exists in the document"
        }
        val updated = myFactors.value + spec
        myFactors.value = updated
        // Always select the newly-added factor so the UI's detail
        // editor lands on it.  The earlier "select 0 only when no
        // selection" behaviour created confusion in the Factors tab
        // where the second Add silently kept focus on the first
        // factor (see E6.1 follow-up in the plan doc).
        mySelectedFactorIndex.value = updated.lastIndex
        dropRuntimeArtefacts()
        markDirty()
    }

    fun updateFactor(index: Int, updated: FactorSpec) {
        val list = myFactors.value
        require(index in list.indices) {
            "updateFactor: index $index out of range 0..${list.lastIndex}"
        }
        val nameCollision = list.withIndex().any { (i, s) ->
            i != index && s.name == updated.name
        }
        require(!nameCollision) {
            "Factor name '${updated.name}' already exists in the document"
        }
        if (list[index] == updated) return
        myFactors.value = list.toMutableList().also { it[index] = updated }
        dropRuntimeArtefacts()
        markDirty()
    }

    /**
     *  Remove the factor at [index].  Selection shifts to the factor
     *  that previously followed it (or the new last factor, or `-1`
     *  if empty).
     */
    fun deleteFactor(index: Int) {
        val list = myFactors.value
        require(index in list.indices) {
            "deleteFactor: index $index out of range 0..${list.lastIndex}"
        }
        val updated = list.toMutableList().also { it.removeAt(index) }
        myFactors.value = updated
        mySelectedFactorIndex.value = when {
            updated.isEmpty() -> -1
            index < updated.size -> index
            else -> updated.lastIndex
        }
        dropRuntimeArtefacts()
        markDirty()
    }

    /** Swap the factor at [index] with its predecessor.  No-op when
     *  [index] is 0 or out of range. */
    fun moveFactorUp(index: Int) {
        val list = myFactors.value
        if (index !in 1..list.lastIndex) return
        val updated = list.toMutableList().also {
            val above = it[index - 1]
            it[index - 1] = it[index]
            it[index] = above
        }
        myFactors.value = updated
        // Selection follows the moved factor.
        if (mySelectedFactorIndex.value == index) mySelectedFactorIndex.value = index - 1
        else if (mySelectedFactorIndex.value == index - 1) mySelectedFactorIndex.value = index
        dropRuntimeArtefacts()
        markDirty()
    }

    /** Swap the factor at [index] with its successor.  No-op when
     *  [index] is the last index or out of range. */
    fun moveFactorDown(index: Int) {
        val list = myFactors.value
        if (index !in 0 until list.lastIndex) return
        val updated = list.toMutableList().also {
            val below = it[index + 1]
            it[index + 1] = it[index]
            it[index] = below
        }
        myFactors.value = updated
        if (mySelectedFactorIndex.value == index) mySelectedFactorIndex.value = index + 1
        else if (mySelectedFactorIndex.value == index + 1) mySelectedFactorIndex.value = index
        dropRuntimeArtefacts()
        markDirty()
    }

    /**
     *  Remove every factor and detach the document from any loaded
     *  file.  Returns the previous file path (or `null`) so the host
     *  can surface a "detached from <file>" notification — mirrors
     *  Scenario's `clearScenarios`.  No-op when the factor list is
     *  already empty.
     *
     *  Identity-coupled lifecycle: clears [lastResult] +
     *  [experimentInstance] + [recentRegressionFits] + the per-point
     *  status map.  Analysis name resets to `"Untitled"` (identity
     *  field); preference-style fields on OutputConfig (database
     *  toggle, CSV flags, databasePolicy) and the design / replication
     *  / stream / execution settings survive (they're session
     *  preferences, not document identity).
     */
    fun clearFactors(): Path? {
        if (myFactors.value.isEmpty()) return null
        val previousFile = currentFile.value
        myFactors.value = emptyList()
        mySelectedFactorIndex.value = -1
        documentLifecycle.reset()
        myEditedSinceLastSim.value = false
        dropRuntimeArtefacts()
        if (myOutputConfig.value.analysisName != "Untitled") {
            myOutputConfig.value = myOutputConfig.value.copy(analysisName = "Untitled")
        }
        return previousFile
    }

    fun setSelectedFactorIndex(index: Int) {
        val list = myFactors.value
        val coerced = when {
            list.isEmpty() -> -1
            index < 0 -> -1
            index >= list.size -> list.lastIndex
            else -> index
        }
        if (mySelectedFactorIndex.value == coerced) return
        mySelectedFactorIndex.value = coerced
    }

    // ── Document mutators: design / replications / streams / exec ──────────

    fun setDesignSpec(spec: DesignSpec) {
        if (myDesignSpec.value == spec) return
        myDesignSpec.value = spec
        dropRuntimeArtefacts()
        markDirty()
    }

    fun setReplications(spec: ReplicationSpec) {
        if (myReplications.value == spec) return
        myReplications.value = spec
        dropRuntimeArtefacts()
        markDirty()
    }

    fun setStreamPolicy(policy: StreamPolicy) {
        if (myStreamPolicy.value == policy) return
        myStreamPolicy.value = policy
        markDirty()
    }

    fun setExecutionMode(mode: ExecutionMode) {
        if (myExecutionMode.value == mode) return
        myExecutionMode.value = mode
        markDirty()
    }

    /** Update the experiment-app output preferences (currently:
     *  per-design-point output dir layout).  Marks the document
     *  dirty but does NOT drop `lastResult` — the choice is a
     *  layout preference, like [setStreamPolicy], not a structural
     *  change to what gets simulated. */
    fun setExperimentOutput(spec: ExperimentOutputSpec) {
        if (myExperimentOutput.value == spec) return
        myExperimentOutput.value = spec
        markDirty()
    }

    /** Update the document-level overrides for the model's baked-in
     *  run parameters.  Marks the document dirty but does NOT drop
     *  `lastResult` — these are values that affect the next run, not
     *  a structural change to what gets simulated.  (A previously-
     *  completed run was made with the old values and remains valid
     *  for inspection.) */
    fun setRunParameterOverrides(spec: RunParameterOverridesSpec) {
        if (myRunParameterOverrides.value == spec) return
        myRunParameterOverrides.value = spec
        markDirty()
    }

    // ── Document mutators: OutputConfig ────────────────────────────────────

    fun setEnableKSLDatabase(enabled: Boolean) {
        if (myOutputConfig.value.enableKSLDatabase == enabled) return
        myOutputConfig.value = myOutputConfig.value.copy(enableKSLDatabase = enabled)
        markDirty()
    }

    fun setEnableReplicationCSV(enabled: Boolean) {
        if (myOutputConfig.value.enableReplicationCSV == enabled) return
        myOutputConfig.value = myOutputConfig.value.copy(enableReplicationCSV = enabled)
        markDirty()
    }

    fun setEnableExperimentCSV(enabled: Boolean) {
        if (myOutputConfig.value.enableExperimentCSV == enabled) return
        myOutputConfig.value = myOutputConfig.value.copy(enableExperimentCSV = enabled)
        markDirty()
    }

    fun setAnalysisName(raw: String) {
        if (myOutputConfig.value.analysisName == raw) return
        myOutputConfig.value = myOutputConfig.value.copy(analysisName = raw)
        markDirty()
    }

    fun setDatabasePolicy(policy: DatabasePolicy) {
        if (myOutputConfig.value.databasePolicy == policy) return
        myOutputConfig.value = myOutputConfig.value.copy(databasePolicy = policy)
        markDirty()
    }

    // ── Document lifecycle ─────────────────────────────────────────────────

    /** Outcome of [loadConfiguration]. */
    sealed class LoadResult {
        data class Loaded(val warnings: List<String> = emptyList()) : LoadResult()
        data class Failed(val reason: String) : LoadResult()
    }

    /**
     *  Replace the in-memory editor state with [config].  Clears
     *  [isDirty] on success.  Does not change [currentFile]; callers
     *  (typically the Open flow) should call [markSaved] separately
     *  after a successful load.
     */
    fun loadConfiguration(config: ExperimentConfiguration): LoadResult {
        myModelReference.value = config.modelReference
        myFactors.value = config.factors
        myDesignSpec.value = config.designSpec
        myReplications.value = config.replications
        myStreamPolicy.value = config.streamPolicy
        myExecutionMode.value = config.executionMode
        myExperimentOutput.value = config.experimentOutput
        myRunParameterOverrides.value = config.runParameterOverrides
        myOutputConfig.value = config.outputConfig.copy(outputDirectory = null)
        mySelectedFactorIndex.value = if (config.factors.isEmpty()) -1 else 0
        documentLifecycle.clearDirty()
        refreshModelDescriptor()
        clearRunState()
        return LoadResult.Loaded()
    }

    /** Reset editor state to empty defaults — equivalent to *File →
     *  New Experiment*.  Mirrors Scenario's `resetConfiguration`. */
    fun resetConfiguration() {
        myModelReference.value = null
        myFactors.value = emptyList()
        myDesignSpec.value = DesignSpec.FullFactorial
        myReplications.value = ReplicationSpec.Uniform(10)
        myStreamPolicy.value = StreamPolicy.Independent()
        myExecutionMode.value = ExecutionMode.CONCURRENT
        myExperimentOutput.value = ExperimentOutputSpec()
        myRunParameterOverrides.value = RunParameterOverridesSpec()
        myOutputConfig.value = OutputConfig(enableKSLDatabase = true)
        mySelectedFactorIndex.value = -1
        documentLifecycle.reset()
        refreshModelDescriptor()
        clearRunState()
    }

    /** Snapshot the current in-memory editor state as an
     *  [ExperimentConfiguration].  Requires [modelReference] to be
     *  set; the caller surfaces a validation error if it isn't.
     *  Used by Save / Save As. */
    fun currentConfiguration(): ExperimentConfiguration {
        val ref = myModelReference.value
            ?: error("Cannot snapshot experiment: model reference is not set")
        return ExperimentConfiguration(
            outputConfig = myOutputConfig.value.copy(outputDirectory = null),
            modelReference = ref,
            factors = myFactors.value,
            designSpec = myDesignSpec.value,
            replications = myReplications.value,
            streamPolicy = myStreamPolicy.value,
            executionMode = myExecutionMode.value,
            experimentOutput = myExperimentOutput.value,
            runParameterOverrides = myRunParameterOverrides.value
        )
    }

    /** Test seam — seed run-time state so [clearRunState],
     *  [loadConfiguration], and [resetConfiguration] paths can be
     *  verified.  Production code should not call this. */
    internal fun seedRunStateForTesting(
        lastResult: RunResult? = null,
        designPointStatuses: Map<Int, DesignPointStatus> = emptyMap(),
        experimentInstance: DesignedExperimentIfc? = null,
        editedSinceLastSim: Boolean = false,
        running: Boolean = false
    ) {
        myLastResult.value = lastResult
        myDesignPointStatuses.value = designPointStatuses
        myExperimentInstance.value = experimentInstance
        myEditedSinceLastSim.value = editedSinceLastSim
        myRunning.value = running
    }

    /** Wipe runtime state.  Called by [clearFactors],
     *  [resetConfiguration], [loadConfiguration], and the start of
     *  every [submit]. */
    internal fun clearRunState() {
        myRunning.value = false
        myLastResult.value = null
        myDesignPointStatuses.value = emptyMap()
        myExperimentInstance.value = null
        myRecentRegressionFits.value = emptyList()
        myEditedSinceLastSim.value = false
    }

    /** Drop runtime artefacts that depend on the previous run's
     *  factor / model / design configuration.  Called by any
     *  structural mutator (Q2 in the plan — factor mutations always
     *  invalidate the whole [lastResult] rather than trying to
     *  preserve subsets). */
    private fun dropRuntimeArtefacts() {
        if (myLastResult.value != null) myLastResult.value = null
        if (myExperimentInstance.value != null) myExperimentInstance.value = null
        if (myRecentRegressionFits.value.isNotEmpty()) {
            myRecentRegressionFits.value = emptyList()
        }
    }

    /**
     *  Record that the current state has been persisted to [path].
     *  Sets [currentFile] and clears [isDirty].
     *
     *  Once-at-default auto-fill: if [OutputConfig.analysisName] is
     *  still at its default `"Untitled"`, replace it with the saved
     *  file's stem (e.g. `myExperiment.toml` → `"myExperiment"`).
     *  Mirrors Scenario's `markSaved` auto-fill behaviour.
     */
    fun markSaved(path: Path) {
        documentLifecycle.markSaved(path)
        if (myOutputConfig.value.analysisName == "Untitled") {
            val stem = path.fileName.toString().substringBeforeLast('.')
            if (stem.isNotBlank()) {
                myOutputConfig.value = myOutputConfig.value.copy(analysisName = stem)
            }
        }
    }

    // ── Submission ─────────────────────────────────────────────────────────

    /**
     *  Submit the document's experiment for execution.  Returns
     *  `true` when a submission was made; `false` when a pre-flight
     *  gate rejected (no model reference, no factors, or already
     *  running).
     *
     *  R1 lifecycle: clears [lastResult] / [experimentInstance] /
     *  [recentRegressionFits] before launching.  Output is routed under
     *  `<workspace>/output/<analysisName>/` so re-runs of the same
     *  document write back into the same folder (with [DatabasePolicy]
     *  governing the .db file inside).
     */
    fun submit(): Boolean {
        if (myRunning.value) return false
        val ref = myModelReference.value ?: return false
        val factors = myFactors.value
        if (factors.isEmpty()) return false
        val provider = myBundleProvider.value ?: return false

        // R1: clear runtime artefacts before kickoff.  The Reports +
        // Regression tabs flip to their empty states through their
        // existing collectors on lastResult.
        clearRunState()
        // Seed per-point statuses to PENDING for the predicted point
        // count.  The substrate's per-point events will promote them
        // to RUNNING / COMPLETED / FAILED.  Predicting the count
        // requires materialising the design; deferring to the first
        // DesignPointCompleted event would lose pre-completion
        // visibility, so we build the design once up-front for the
        // status seed.
        val outputConfig = myOutputConfig.value
        val analysisDirName = sanitizeAnalysisName(outputConfig.analysisName)
        val outputDir = AppWorkspacePaths.outputDir(appWorkspace, outputConfig.analysisName)
            .toAbsolutePath().normalize()
        Files.createDirectories(outputDir)

        // SEQUENTIAL + CRN: substrate silently ignores streamPolicy
        // under SEQUENTIAL (Phase E2 decision).  The Phase E4 frame
        // checks this condition before calling submit() and surfaces
        // a notification — exposed here through [sequentialIgnoresStreamPolicy]
        // so the frame can read it without re-deriving the rule.

        val config = currentConfiguration().copy(
            outputConfig = outputConfig.copy(outputDirectory = outputDir.toString())
        )

        val modelBuilder = try {
            provider.builderFor(ref.toModelIdentifier())
        } catch (t: Throwable) {
            myEditedSinceLastSim.value = true
            return false
        }

        val kslDb = resolveKslDatabase(outputConfig, analysisDirName, outputDir)

        val experiment = try {
            config.toDesignedExperiment(
                modelBuilder = modelBuilder,
                pathToOutputDirectory = outputDir,
                kslDatabase = kslDb,
                name = analysisDirName
            )
        } catch (t: Throwable) {
            return false
        }
        myExperimentInstance.value = experiment

        // Seed per-point status map from the materialised design.
        val pointIds = experiment.design.designIterator().asSequence()
            .map { it.number }
            .toList()
        myDesignPointStatuses.value = pointIds.associateWith { DesignPointStatus.PENDING }

        val newSession = KSLAppSession(provider = provider)
        session?.close()
        session = newSession

        val baseline = RunConfiguration()    // minimal — workload lives on the experiment
        val handle = newSession.submit(
            RunSpec.Experiment(
                config = baseline,
                experiment = experiment
            )
        )
        currentHandle = handle
        myRunning.value = true

        edtScope.launch {
            handle.events.collect { ev ->
                when (ev) {
                    is RunEvent.DesignPointStarted -> {
                        myDesignPointStatuses.value =
                            myDesignPointStatuses.value + (ev.pointId to DesignPointStatus.RUNNING)
                    }
                    is RunEvent.DesignPointCompleted -> {
                        val status = when {
                            ev.wasCancelled -> DesignPointStatus.CANCELLED
                            ev.snapshot != null -> DesignPointStatus.COMPLETED
                            else -> DesignPointStatus.FAILED
                        }
                        myDesignPointStatuses.value =
                            myDesignPointStatuses.value + (ev.pointId to status)
                    }
                    is RunEvent.RunCancelled -> {
                        // Whole-run cancel: any still-PENDING or RUNNING
                        // point that didn't get a per-point completion
                        // event becomes CANCELLED.
                        myDesignPointStatuses.value = myDesignPointStatuses.value.mapValues { (_, s) ->
                            if (s == DesignPointStatus.PENDING || s == DesignPointStatus.RUNNING)
                                DesignPointStatus.CANCELLED
                            else s
                        }
                    }
                    else -> { /* other event types handled by console */ }
                }
                myEventFlow.emit(ev)
            }
        }
        edtScope.launch {
            val result = handle.result.await()
            myLastResult.value = result
            myEditedSinceLastSim.value = false
            myRunning.value = false
            currentHandle = null
        }
        return true
    }

    /** Cancel the in-flight run, if any.  No-op when not running. */
    fun cancel() {
        currentHandle?.cancel("User-requested cancel")
    }

    /**
     *  Clear the per-design-point status map back to empty.  Used by
     *  the Simulate tab's Reset button after a completed / failed /
     *  cancelled run when the user wants to start fresh.  No-op
     *  while a run is in flight (don't trample the live state map).
     */
    fun resetDesignPointStatuses() {
        if (myRunning.value) return
        myDesignPointStatuses.value = emptyMap()
    }

    /**
     *  Request cancellation of a single in-flight design point.  Has
     *  no effect when no run is in progress, when the targeted point
     *  has already completed, or when the current experiment isn't a
     *  [ParallelDesignedExperiment] (sequential designs don't support
     *  per-point cancellation).
     *
     *  Returns `true` when the request was forwarded to a matching
     *  active per-point job; `false` otherwise.  Safe to call from
     *  the EDT (or any thread); the underlying coroutine cancellation
     *  is thread-safe.
     */
    fun cancelDesignPoint(pointId: Int): Boolean {
        val experiment = myExperimentInstance.value as? ParallelDesignedExperiment
            ?: return false
        val ok = experiment.cancelDesignPoint(pointId)
        if (ok) {
            // E7.11 #7 — immediately transition to CANCELLING so the
            // UI shows "Cancelling…" right away instead of waiting
            // for the substrate's commit phase (which only fires the
            // DesignPointCompleted(wasCancelled=true) event after
            // every other in-flight point has finished too).  The
            // eventual completed event will overwrite this to
            // CANCELLED via the existing event subscriber.
            val current = myDesignPointStatuses.value
            if (current[pointId] == DesignPointStatus.RUNNING) {
                myDesignPointStatuses.value = current + (pointId to DesignPointStatus.CANCELLING)
            }
        }
        return ok
    }

    /** `true` when the current document combines `SEQUENTIAL`
     *  execution with `CommonRandomNumbers` — a combination the
     *  substrate silently degrades (Phase E2 decision).  The Phase E4
     *  frame reads this to surface a one-shot notification before
     *  calling [submit]. */
    fun sequentialIgnoresStreamPolicy(): Boolean =
        myExecutionMode.value == ExecutionMode.SEQUENTIAL &&
            myStreamPolicy.value is StreamPolicy.CommonRandomNumbers

    // ── Analysis ───────────────────────────────────────────────────────────

    /**
     *  Fit a regression of [response] under [model] against the
     *  retained [experimentInstance].  Returns `null` when no
     *  experiment instance is retained (no run yet, or it was
     *  cleared by R1 / clearFactors / resetConfiguration).  Prepends
     *  a [RegressionFitRecord] to [recentRegressionFits] (subject to
     *  FIFO eviction at [MAX_RECENT_FITS]).
     *
     *  Wraps the substrate's `DesignedExperimentIfc.regressionResults`
     *  — works identically for both `ParallelDesignedExperiment` and
     *  `DesignedExperiment`.
     */
    fun fitRegression(
        response: String,
        model: LinearModel,
        coded: Boolean,
        confidenceLevel: Double = 0.95
    ): RegressionResultsIfc? {
        val experiment = myExperimentInstance.value ?: return null
        val result = experiment.regressionResults(response, model, coded)
        // Prepend the new record (most-recent-first) and FIFO-evict
        // beyond [MAX_RECENT_FITS].  Eviction is silent regardless of
        // saved state — per the design discussion the Recent Fits
        // table makes saved/unsaved visible so the user can act
        // before fitting past the bound.
        val record = RegressionFitRecord(
            timestamp = LocalDateTime.now(),
            response = response,
            modelExpression = model.asString(),
            coded = coded,
            confidenceLevel = confidenceLevel,
            fit = result
        )
        val previous = myRecentRegressionFits.value
        val updated = (listOf(record) + previous).take(MAX_RECENT_FITS)
        myRecentRegressionFits.value = updated
        return result
    }

    /** Remove the record at [index] from [recentRegressionFits].
     *  No-op when [index] is out of range.  Files on disk (referenced
     *  by [RegressionFitRecord.savedPaths]) are NOT deleted — that's
     *  the Reports tab / user's domain. */
    fun removeRegressionFit(index: Int) {
        val list = myRecentRegressionFits.value
        if (index !in list.indices) return
        myRecentRegressionFits.value = list.toMutableList().also { it.removeAt(index) }
    }

    /** Empty [recentRegressionFits].  Caller is responsible for any
     *  confirmation prompts (the tab confirms only when unsaved
     *  records are present). */
    fun clearRegressionFits() {
        if (myRecentRegressionFits.value.isEmpty()) return
        myRecentRegressionFits.value = emptyList()
    }

    /** Append [paths] to the record at [index]'s [RegressionFitRecord.savedPaths]
     *  and flip its "saved" status visible to the Regression tab.
     *  No-op when [index] is out of range.  Multiple Save clicks on
     *  the same row produce multiple timestamped files on disk and
     *  multiple entries in this list. */
    fun markRegressionFitSaved(index: Int, paths: List<Path>) {
        val list = myRecentRegressionFits.value
        if (index !in list.indices) return
        if (paths.isEmpty()) return
        val updated = list.toMutableList()
        val current = updated[index]
        updated[index] = current.copy(savedPaths = current.savedPaths + paths)
        myRecentRegressionFits.value = updated
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     *  Resolve the [KSLDatabase] this run should use, honouring the
     *  document's [DatabasePolicy].  OVERWRITE deletes any existing
     *  `<analysisName>.db` and creates fresh; NEW writes a
     *  timestamped sibling.  Same pattern as `ScenarioOrchestrator`'s
     *  `resolveKslDatabase` helper.
     */
    private fun resolveKslDatabase(
        outputConfig: OutputConfig,
        runnerName: String,
        outputDir: Path
    ): KSLDatabase {
        val fileStem = when (outputConfig.databasePolicy) {
            DatabasePolicy.OVERWRITE -> {
                val target = outputDir.resolve("$runnerName.db")
                Files.deleteIfExists(target)
                runnerName
            }
            DatabasePolicy.NEW -> {
                val ts = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
                )
                "${runnerName}_$ts"
            }
        }
        return KSLDatabase("$fileStem.db", outputDir)
    }

    /** Convert a [ModelReference] to the flat identifier
     *  [BundleModelProvider.builderFor] expects.  The provider's flat
     *  `byModelId` lookup handles the common case (bundle-and-model-id
     *  references), and the convenience `(bundleId, modelId)` form
     *  is used directly when the reference carries both. */
    private fun ModelReference.toModelIdentifier(): String = when (this) {
        is ModelReference.ByProviderId       -> providerId
        is ModelReference.ByBundleAndModelId -> modelId
        is ModelReference.Embedded           -> modelName
        is ModelReference.ByJar              -> error(
            "ByJar references are not supported by the Experiment app's bundle-backed " +
                "provider; use ByBundleAndModelId or ByProviderId."
        )
    }

    // ── AutoCloseable ──────────────────────────────────────────────────────

    override fun close() {
        currentHandle?.cancel("App closed")
        currentHandle = null
        session?.close()
        session = null
        myLoadedBundles.value.forEach { runCatching { it.close() } }
        edtScope.cancel()
    }
}
