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
import ksl.app.config.BundleRef
import ksl.app.config.ExecutionMode
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.session.withoutScenario
import ksl.app.settings.UserSettingsStore
import java.nio.file.Path

/**
 *  Document state-holder for one `kslScenarioApp(...)` instance.
 *
 *  Owns the in-memory [RunConfiguration] that represents the
 *  scenarios document the analyst is editing: its list of
 *  [ScenarioSpec] entries, the document-level [OutputConfig], the
 *  [ExecutionMode] toggle, and the current selection.  Drives the
 *  file-state surface (`currentFile`, `isDirty`) that powers the
 *  File menu's Save / Save As / Open round-trip and the window
 *  title's `*` dirty marker.
 *
 *  **Phase C scope:** document state + mutators + save/load.  Run
 *  state ([runningFlow], [lastResult], `editedSinceLastSim`) and the
 *  bundle/model provider arrive in Phase H/I respectively.
 *
 *  ## Not thread-safe
 *
 *  All mutation is expected on the Swing EDT.  Coroutine plumbing
 *  inside [edtScope] schedules onto [Dispatchers.Swing] so subscribers
 *  receive emissions on the right thread.
 *
 *  @param appName window title; also drives the per-app workspace
 *   subdirectory under `<KSLWork>/<appNameSanitized>/`.
 */
class ScenarioAppController(
    val appName: String
) : AutoCloseable {

    /** Scope for EDT-confined coroutine work. */
    val edtScope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    /** User-wide settings (workspace, recent list). */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /**
     *  Sanitized version of [appName], suitable for use as a
     *  filesystem directory segment.  Spaces become underscores
     *  (matching KSL's `Model(simulationName)` convention so the two
     *  apps agree on naming).  Delegates to
     *  [AppWorkspacePaths.sanitizeAppName].
     */
    val appNameSanitized: String = AppWorkspacePaths.sanitizeAppName(appName)

    /**
     *  Workspace subdirectory dedicated to this app.  Equal to
     *  `settingsStore.activeWorkspace().resolve(appNameSanitized)`.
     *  All per-app files (saved configurations, model runtime
     *  output, rendered reports) live under here.  Read each access
     *  — the underlying `activeWorkspace()` is permitted to change
     *  between calls.  Delegates to [AppWorkspacePaths.appWorkspaceDir].
     */
    val appWorkspace: Path
        get() = AppWorkspacePaths.appWorkspaceDir(settingsStore.activeWorkspace(), appName)

    // ── Document state ─────────────────────────────────────────────────────

    private val myScenarios = MutableStateFlow<List<ScenarioSpec>>(emptyList())
    /** Ordered list of scenarios in the document. */
    val scenarios: StateFlow<List<ScenarioSpec>> = myScenarios.asStateFlow()

    private val mySelectedIndex = MutableStateFlow(-1)
    /**
     *  Index of the currently-selected scenario in [scenarios], or
     *  `-1` when nothing is selected.  Drives which scenario is
     *  loaded by the Scenarios tab's editor-open action in Phase F.
     *  Auto-shifts on add/delete/reorder.
     */
    val selectedIndex: StateFlow<Int> = mySelectedIndex.asStateFlow()

    private val myOutputConfig = MutableStateFlow(
        // Per the locked Scenario-app design, the shared SQLite KSL
        // database is on by default (captures all scenarios' runs).
        // CSV / per-scenario DB live in the scenario editor instead.
        OutputConfig(enableKSLDatabase = true)
    )
    /** Document-level output options.  CSV flags here are unused in
     *  the Scenario app (per-scenario CSV lives on [ScenarioSpec]);
     *  the relevant fields are [OutputConfig.enableKSLDatabase] and
     *  [OutputConfig.reports]. */
    val outputConfig: StateFlow<OutputConfig> = myOutputConfig.asStateFlow()

    private val myExecutionMode = MutableStateFlow(ExecutionMode.SEQUENTIAL)
    /** Sequential vs parallel scenario execution. */
    val executionMode: StateFlow<ExecutionMode> = myExecutionMode.asStateFlow()

    // ── File state ─────────────────────────────────────────────────────────

    private val myCurrentFile = MutableStateFlow<Path?>(null)
    /** Path of the configuration file currently associated with the
     *  in-memory state, or `null` when not yet saved or loaded. */
    val currentFile: StateFlow<Path?> = myCurrentFile.asStateFlow()

    private val myIsDirty = MutableStateFlow(false)
    /** `true` when in-memory configuration has been edited since the
     *  last save or load.  Drives the title `*` marker and the
     *  Save Configuration menu-item asterisk. */
    val isDirty: StateFlow<Boolean> = myIsDirty.asStateFlow()

    private fun markDirty() {
        if (!myIsDirty.value) myIsDirty.value = true
        if (!myEditedSinceLastSim.value) myEditedSinceLastSim.value = true
    }

    /**
     *  Drop the snapshot for [scenarioName] from any in-memory
     *  [RunResult.BatchCompleted], collapsing [lastResult] to `null`
     *  when the last snapshot is removed.  No-op for any other
     *  `RunResult` variant (Completed / Optimization / Failed /
     *  Cancelled / null) and for unknown names — those are silent
     *  passes so callers can fire-and-forget without guarding.
     *
     *  Supports identity-coupled lifecycle: scenario-list mutators
     *  call this to keep the result aligned with the editable list.
     */
    private fun dropResultFor(scenarioName: String) {
        val current = myLastResult.value as? RunResult.BatchCompleted ?: return
        myLastResult.value = current.withoutScenario(scenarioName)
    }

    // ── Bundle library ─────────────────────────────────────────────────────

    private val myLoadedBundles = MutableStateFlow<List<LoadedBundle>>(emptyList())
    /** All bundles currently loaded into this controller (classpath + any
     *  JARs the user has loaded interactively).  Append-only in v1 — no
     *  unload because `LoadedBundle`s can share classloaders. */
    val loadedBundles: StateFlow<List<LoadedBundle>> = myLoadedBundles.asStateFlow()

    private val myBundleProvider = MutableStateFlow<BundleModelProvider?>(null)
    /** Adapter exposing every loaded bundle as a single
     *  [BundleModelProvider].  `null` when [loadedBundles] is empty. */
    val bundleProvider: StateFlow<BundleModelProvider?> = myBundleProvider.asStateFlow()

    init {
        // Auto-discover bundles already on the JVM classpath so analysts
        // who launch a packaged scenario app immediately see the
        // available models in the picker.  JAR-loaded bundles join this
        // list later via [loadBundleJar].
        val classpathBundles = BundleLoader.loadFromClasspath()
        if (classpathBundles.isNotEmpty()) updateBundles(classpathBundles)
    }

    private fun updateBundles(bundles: List<LoadedBundle>) {
        myLoadedBundles.value = bundles
        myBundleProvider.value = if (bundles.isEmpty()) null else BundleModelProvider(bundles)
    }

    /**
     *  Outcome of [loadBundleJar].
     *
     *  - [Loaded] — one or more bundles were discovered and added.
     *  - [NoBundles] — the JAR carried no `KSLModelBundle` service
     *    registration.
     *  - [Failed] — the load attempt threw; [Failed.reason] is suitable
     *    for surfacing to the user.
     */
    sealed class LoadBundleResult {
        data class Loaded(val newBundleIds: List<String>) : LoadBundleResult()
        object NoBundles : LoadBundleResult()
        data class Failed(val reason: String) : LoadBundleResult()
    }

    /**
     *  Load every `KSLModelBundle` from the JAR at [jarPath] and append
     *  the discovered bundles to [loadedBundles].  Bundles whose
     *  `bundleId` already exists in the controller are skipped (the
     *  first registration wins, matching [BundleModelProvider]'s
     *  duplicate-handling).
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
        // Close duplicates immediately — they own a redundant classloader.
        duplicates.forEach { runCatching { it.close() } }
        if (toAdd.isEmpty()) return LoadBundleResult.NoBundles
        updateBundles(myLoadedBundles.value + toAdd)
        return LoadBundleResult.Loaded(toAdd.map { it.bundle.bundleId })
    }

    // ── Run state ──────────────────────────────────────────────────────────

    /**
     *  Per-scenario lifecycle status driving the Scenarios-table
     *  *Status* column chips.
     */
    enum class ScenarioStatus {
        IDLE,
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        /** User-initiated stop — distinct from FAILED, which represents
         *  a model-level exception.  Set by both per-row ✕ cancels and
         *  global *Cancel* button when a scenario was running but did
         *  not finish, or was queued and never got to start. */
        CANCELLED,
        SKIPPED
    }

    private val myRunning = MutableStateFlow(false)
    /** `true` while a scenario sweep is in flight. */
    val runningFlow: StateFlow<Boolean> = myRunning.asStateFlow()

    private val myLastResult = MutableStateFlow<RunResult?>(null)
    /** Most recently observed terminal [RunResult], or null when none yet. */
    val lastResult: StateFlow<RunResult?> = myLastResult.asStateFlow()

    private val myEventFlow = MutableSharedFlow<RunEvent>(replay = 0, extraBufferCapacity = 256)
    /** Hot stream of [RunEvent]s from the active run, for console drawers. */
    val eventFlow: SharedFlow<RunEvent> = myEventFlow.asSharedFlow()

    private val myScenarioStatuses = MutableStateFlow<Map<String, ScenarioStatus>>(emptyMap())
    /** Per-scenario status indexed by [ScenarioSpec.name]. */
    val scenarioStatuses: StateFlow<Map<String, ScenarioStatus>> = myScenarioStatuses.asStateFlow()

    /** Per-scenario replication progress: `(current, total)`.  Absent
     *  entries mean "no replication events received yet" (i.e. scenario
     *  hasn't started, or is queued).  Updated by the substrate's
     *  `ScenarioReplicationStarted` / `ScenarioReplicationEnded` events. */
    private val myReplicationProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val replicationProgress: StateFlow<Map<String, Pair<Int, Int>>> = myReplicationProgress.asStateFlow()

    /** Lazily-populated cache of `(bundleId, modelId) → ExperimentRunDefaults`.
     *  Used by the Scenarios-table *Reps* column to show the effective
     *  replication count (override OR model default) without re-probing
     *  on every render. */
    private val modelDefaultsCache: MutableMap<Pair<String, String>, ksl.controls.experiments.ExperimentRunDefaults> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     *  Returns the model default `ExperimentRunDefaults` for the
     *  scenario at [index], probing the matching bundle's descriptor
     *  on first access.  Returns `null` when the scenario's model
     *  reference is unresolvable (missing bundle, non-bundled
     *  reference, or probe failure).  Cache is per-controller — survives
     *  until [close].
     */
    fun modelDefaultsFor(index: Int): ksl.controls.experiments.ExperimentRunDefaults? {
        val spec = myScenarios.value.getOrNull(index) ?: return null
        val ref = spec.modelReference as? ModelReference.ByBundleAndModelId ?: return null
        val key = ref.bundleId to ref.modelId
        modelDefaultsCache[key]?.let { return it }
        val bundle = myLoadedBundles.value.firstOrNull { it.bundle.bundleId == ref.bundleId } ?: return null
        return try {
            val d = bundle.descriptorFor(ref.modelId).experimentRunDefaults
            modelDefaultsCache[key] = d
            d
        } catch (_: Throwable) {
            null
        }
    }

    private var currentHandle: RunHandle? = null
    private var session: KSLAppSession? = null

    /** Names of scenarios the user explicitly cancelled via
     *  [cancelScenario] during the current run.  Cleared at the start
     *  of every [submit]; consulted by the `ScenarioCompleted` handler
     *  and the smart-finalize step to distinguish CANCELLED from
     *  FAILED. */
    private val explicitlyCancelled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** `true` once the user has pressed the global *Cancel* button
     *  during the current run.  Cleared at the start of every
     *  [submit].  Combined with [explicitlyCancelled] in status
     *  reconciliation: a snapshot-null ScenarioCompleted under either
     *  signal maps to CANCELLED instead of FAILED. */
    @Volatile
    private var globalCancelRequested: Boolean = false

    /**
     *  Submit the document's scenarios for execution.  No-op when a
     *  run is already in flight or there are no runnable scenarios.
     *  Returns `true` when a submission was made.
     *
     *  Output is routed under `<appWorkspace>/output/`.  A fresh
     *  [KSLAppSession] is built each call using the current
     *  [bundleProvider] so newly-loaded bundles are picked up
     *  without restarting the app.
     */
    fun submit(): Boolean {
        if (myRunning.value) return false
        val scenarios = myScenarios.value
        if (scenarios.none { !it.skipOnRun }) return false

        // R1 lifecycle: hitting Simulate is a destructive act on the
        // prior in-memory result.  Clear it *before* the run kicks off
        // so the Reports surfaces flip to their empty state through
        // the normal flow (rather than as a side-effect of a substrate
        // RunStarted event, which the substrate does not emit for
        // ScenarioOrchestrator runs anyway).  If the new run aborts,
        // the user re-runs to repopulate; this matches the chosen
        // R1 semantics — see the lifecycle plan for the discussion.
        myLastResult.value = null

        // Seed: skipped scenarios stay skipped, runnable scenarios start
        // PENDING (queued).  The substrate's per-scenario
        // ScenarioStarted event promotes them to RUNNING when the
        // runner actually begins them — under SEQUENTIAL this is
        // visibly one-at-a-time; under CONCURRENT all rows flip to
        // RUNNING almost simultaneously.
        myScenarioStatuses.value = scenarios.associate { spec ->
            spec.name to if (spec.skipOnRun) ScenarioStatus.SKIPPED else ScenarioStatus.PENDING
        }
        myReplicationProgress.value = emptyMap()
        explicitlyCancelled.clear()
        globalCancelRequested = false

        // Nest under <workspace>/output/<analysisName>/ so every
        // artifact of this run (KSL database, kslOutput.txt, CSVs,
        // reports) lives in a subdirectory keyed by the user's
        // analysis identity.  Re-running the same document writes
        // back into the same folder (with the DatabasePolicy
        // controlling what happens to the .db file inside).
        val outputDir = AppWorkspacePaths.outputDir(appWorkspace, myOutputConfig.value.analysisName)
            .toAbsolutePath().normalize().toString()
        val config = RunConfiguration(
            scenarios = scenarios,
            bundleRefs = deriveBundleRefs(scenarios),
            outputConfig = myOutputConfig.value.copy(outputDirectory = outputDir),
            executionMode = myExecutionMode.value
        )

        val newSession = KSLAppSession(provider = myBundleProvider.value)
        session?.close()
        session = newSession

        val handle = newSession.submit(RunSpec.Scenarios(config))
        currentHandle = handle
        myRunning.value = true

        edtScope.launch {
            handle.events.collect { ev ->
                when (ev) {
                    is RunEvent.ScenarioStarted -> {
                        myScenarioStatuses.value =
                            myScenarioStatuses.value + (ev.scenarioName to ScenarioStatus.RUNNING)
                    }
                    is RunEvent.ScenarioReplicationStarted -> {
                        myReplicationProgress.value =
                            myReplicationProgress.value + (ev.scenarioName to (ev.repNumber to ev.totalReplications))
                        // Defensive: a runner started reps before we got
                        // the matching ScenarioStarted (event interleave).
                        if (myScenarioStatuses.value[ev.scenarioName] == ScenarioStatus.PENDING) {
                            myScenarioStatuses.value =
                                myScenarioStatuses.value + (ev.scenarioName to ScenarioStatus.RUNNING)
                        }
                    }
                    is RunEvent.ScenarioReplicationEnded -> {
                        myReplicationProgress.value =
                            myReplicationProgress.value + (ev.scenarioName to (ev.repNumber to ev.totalReplications))
                    }
                    is RunEvent.ScenarioReplicationsCompleted -> {
                        // Fired in Phase 1 of ConcurrentScenarioRunner the
                        // moment the scenario's replications finish — well
                        // before the sequential Phase-2 commit reaches this
                        // scenario.  Flip status to COMPLETED here so the
                        // table reflects reality without waiting for sibling
                        // scenarios to finish.  The eventual ScenarioCompleted
                        // event below is idempotent on success and only
                        // overrides this status if the commit produced a
                        // null snapshot (rare; the existing CANCELLED /
                        // FAILED branches handle it).
                        myScenarioStatuses.value =
                            myScenarioStatuses.value + (ev.scenarioName to ScenarioStatus.COMPLETED)
                    }
                    is RunEvent.ScenarioCompleted -> {
                        val status = when {
                            ev.snapshot != null -> ScenarioStatus.COMPLETED
                            ev.scenarioName in explicitlyCancelled -> ScenarioStatus.CANCELLED
                            globalCancelRequested -> ScenarioStatus.CANCELLED
                            else -> ScenarioStatus.FAILED
                        }
                        myScenarioStatuses.value = myScenarioStatuses.value + (ev.scenarioName to status)
                    }
                    else -> { /* console drawer + other surfaces handle */ }
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
            // Reconcile leftover statuses with what actually happened.
            // ScenarioOrchestrator emits ScenarioCompleted only in the
            // commit phase, after every scenario has finished its in-
            // memory simulation; if the user cancels between "reps
            // done" and "commit", a scenario could still read RUNNING
            // even though its work is complete.  Combine the
            // replication progress map with the cancel-intent flags
            // to assign accurate terminal statuses:
            //   PENDING (never started) under cancel intent → CANCELLED
            //   PENDING with no cancel intent → SKIPPED
            //     (defensive; shouldn't happen under normal
            //     flow since the orchestrator emits ScenarioStarted
            //     before any work begins, but the substrate could
            //     fail before reaching that point)
            //   RUNNING + reps done → COMPLETED
            //   RUNNING + reps unfinished, cancel intent → CANCELLED
            //   RUNNING + reps unfinished, no cancel intent → FAILED
            val progress = myReplicationProgress.value
            myScenarioStatuses.value = myScenarioStatuses.value.mapValues { (name, s) ->
                val cancelIntended = name in explicitlyCancelled || globalCancelRequested
                when (s) {
                    ScenarioStatus.PENDING ->
                        if (cancelIntended) ScenarioStatus.CANCELLED else ScenarioStatus.SKIPPED
                    ScenarioStatus.RUNNING -> {
                        val p = progress[name]
                        val repsDone = p != null && p.first >= p.second
                        when {
                            repsDone -> ScenarioStatus.COMPLETED
                            cancelIntended -> ScenarioStatus.CANCELLED
                            else -> ScenarioStatus.FAILED
                        }
                    }
                    else -> s
                }
            }
        }
        return true
    }

    /** Cancel the in-flight run, if any.  Cancels every scenario; the
     *  per-scenario form [cancelScenario] cancels just one.  Sets
     *  [globalCancelRequested] so the status reconciliation
     *  distinguishes "user-stopped" from "model exception." */
    fun cancel() {
        if (currentHandle == null) return
        globalCancelRequested = true
        currentHandle?.cancel("Cancelled by user")
    }

    /**
     *  Cancel a single scenario by name without stopping the rest of
     *  the run.  No-op when no run is in flight or [name] doesn't
     *  match a currently-running scenario.  Records the intent in
     *  [explicitlyCancelled] so the resulting `ScenarioCompleted`
     *  event with a null snapshot is interpreted as CANCELLED rather
     *  than FAILED.
     */
    fun cancelScenario(name: String): Boolean {
        val handle = currentHandle ?: return false
        explicitlyCancelled.add(name)
        return handle.cancelScenario(name)
    }

    private val myEditedSinceLastSim = MutableStateFlow(false)
    /** `true` when in-memory state has been edited since the most
     *  recent terminal run.  Cleared by [submit] on terminal result. */
    val editedSinceLastSim: StateFlow<Boolean> = myEditedSinceLastSim.asStateFlow()

    /**
     *  Returns the list of `(bundleId, modelId)` pairs in the document
     *  whose [ScenarioSpec.modelReference] does not resolve against
     *  the current [bundleProvider].  Used by the Open Configuration
     *  flow to surface unresolved-reference warnings.  References that
     *  are not [ModelReference.ByBundleAndModelId] are ignored (only
     *  the bundled form participates in this check).
     */
    fun unresolvedBundleReferences(): List<Pair<String, String>> {
        val provider = myBundleProvider.value
        return myScenarios.value.mapNotNull { spec ->
            val ref = spec.modelReference as? ModelReference.ByBundleAndModelId ?: return@mapNotNull null
            if (provider != null && provider.isModelProvided(ref.bundleId, ref.modelId)) null
            else ref.bundleId to ref.modelId
        }
    }

    // ── Document-level mutators ────────────────────────────────────────────

    /** Replace the entire output config.  Idempotent — equal value is a no-op. */
    fun setOutputConfig(config: OutputConfig) {
        if (myOutputConfig.value == config) return
        myOutputConfig.value = config
        markDirty()
    }

    /** Toggle the document-level [OutputConfig.enableKSLDatabase] flag. */
    fun setEnableKSLDatabase(enabled: Boolean) {
        if (myOutputConfig.value.enableKSLDatabase == enabled) return
        myOutputConfig.value = myOutputConfig.value.copy(enableKSLDatabase = enabled)
        markDirty()
    }

    /**
     *  Replace the document's analysis name.  The stored value is the
     *  raw user input (so the UI shows what they typed); sanitisation
     *  happens at the points that touch the filesystem.  No-op when
     *  the value is unchanged.  Marks the document dirty so the user
     *  is prompted to save the rename.
     */
    fun setAnalysisName(raw: String) {
        if (myOutputConfig.value.analysisName == raw) return
        myOutputConfig.value = myOutputConfig.value.copy(analysisName = raw)
        markDirty()
    }

    /** Replace the document's [DatabasePolicy]. */
    fun setDatabasePolicy(policy: ksl.app.config.DatabasePolicy) {
        if (myOutputConfig.value.databasePolicy == policy) return
        myOutputConfig.value = myOutputConfig.value.copy(databasePolicy = policy)
        markDirty()
    }

    /** Set the document-level execution mode (sequential or parallel). */
    fun setExecutionMode(mode: ExecutionMode) {
        if (myExecutionMode.value == mode) return
        myExecutionMode.value = mode
        markDirty()
    }

    // ── Scenario list mutators ─────────────────────────────────────────────

    /**
     *  Append [spec] to the end of the scenarios list.  Throws
     *  `IllegalArgumentException` when the spec's name collides with
     *  an existing scenario.  Selects the new scenario.
     */
    fun addScenario(spec: ScenarioSpec) {
        require(myScenarios.value.none { it.name == spec.name }) {
            "Scenario name '${spec.name}' already exists in the document"
        }
        myScenarios.value = myScenarios.value + spec
        mySelectedIndex.value = myScenarios.value.lastIndex
        markDirty()
    }

    /**
     *  Insert a deep-copy of the scenario at [sourceIndex] immediately
     *  after the source.  The clone's name is the source's name with
     *  a `_copy` (or `_copy_N`) suffix until unique.  Selects the
     *  clone.
     */
    fun cloneScenario(sourceIndex: Int) {
        val list = myScenarios.value
        require(sourceIndex in list.indices) {
            "cloneScenario: source index $sourceIndex out of range 0..${list.lastIndex}"
        }
        val source = list[sourceIndex]
        val cloneName = uniqueCloneName(source.name, list.map { it.name }.toSet())
        val clone = source.copy(name = cloneName)
        val updated = list.toMutableList().also { it.add(sourceIndex + 1, clone) }
        myScenarios.value = updated
        mySelectedIndex.value = sourceIndex + 1
        markDirty()
    }

    private fun uniqueCloneName(baseName: String, existing: Set<String>): String {
        val firstAttempt = "${baseName}_copy"
        if (firstAttempt !in existing) return firstAttempt
        var n = 2
        while (true) {
            val attempt = "${baseName}_copy_$n"
            if (attempt !in existing) return attempt
            n++
        }
    }

    /**
     *  Remove the scenario at [index].  Selection shifts to the
     *  scenario that previously followed it (or the new last
     *  scenario, or `-1` if the list is now empty).
     */
    fun deleteScenario(index: Int) {
        val list = myScenarios.value
        require(index in list.indices) {
            "deleteScenario: index $index out of range 0..${list.lastIndex}"
        }
        val doomedName = list[index].name
        val updated = list.toMutableList().also { it.removeAt(index) }
        myScenarios.value = updated
        mySelectedIndex.value = when {
            updated.isEmpty() -> -1
            index < updated.size -> index             // keep slot
            else -> updated.lastIndex                 // was last; shift up
        }
        // Identity-coupled lifecycle: dropping a scenario from the
        // editable list also drops its snapshot from any in-memory
        // result so the Reports tabs can't render a phantom row for
        // a scenario the user has removed.  The per-scenario status
        // map is similarly pruned so the Scenarios table doesn't
        // carry a status for a name that no longer exists.
        dropResultFor(doomedName)
        if (doomedName in myScenarioStatuses.value) {
            myScenarioStatuses.value = myScenarioStatuses.value - doomedName
        }
        markDirty()
    }

    /**
     *  Remove every scenario from the list and detach the document
     *  from its on-disk source file.  Returns the [Path] the document
     *  was associated with at the time of the call, or `null` when it
     *  had no file association (callers use this to decide whether to
     *  surface a "detached from <file>" notification).  No-op (returns
     *  `null`) when the list is already empty.
     *
     *  Why detach: this is a single-click whole-document wipe.  Under
     *  the previous "clear-and-stay-dirty" contract, a subsequent
     *  Save would overwrite the loaded file with an empty
     *  configuration — a one-click data-loss path.  Treating *Clear
     *  All* as "start a new document, keep my output-config /
     *  execution-mode preferences" closes that path: Save now has no
     *  current-file target and routes to *Save As*, forcing the
     *  destination choice to be explicit.  The original file on disk
     *  is preserved.
     *
     *  Identity-coupled lifecycle: removing every scenario also
     *  invalidates any in-memory [lastResult] (its snapshots no
     *  longer describe anything in the editable list) and the
     *  per-scenario status map.  Dirty + edited-since-last-sim flags
     *  are reset since the document is effectively new.  The
     *  document-level [OutputConfig] and [ExecutionMode] are NOT
     *  touched — those are user preferences, not document content;
     *  `resetConfiguration` / `loadConfiguration` are the paths that
     *  reset them.
     */
    fun clearScenarios(): Path? {
        if (myScenarios.value.isEmpty()) return null
        val previousFile = myCurrentFile.value
        myScenarios.value = emptyList()
        mySelectedIndex.value = -1
        myLastResult.value = null
        myScenarioStatuses.value = emptyMap()
        myCurrentFile.value = null
        myIsDirty.value = false
        myEditedSinceLastSim.value = false
        // Analysis name is document identity, not a session
        // preference — reset it alongside the file detach so a
        // subsequent Simulate doesn't write into the directory of
        // the document we just discarded.  Preference-style fields
        // on OutputConfig (enableKSLDatabase, CSV toggles,
        // databasePolicy) survive Clear All by design.
        if (myOutputConfig.value.analysisName != "Untitled") {
            myOutputConfig.value =
                myOutputConfig.value.copy(analysisName = "Untitled")
        }
        return previousFile
    }

    /** Swap the scenario at [index] with its predecessor.  No-op when [index] is 0 or out of range. */
    fun moveScenarioUp(index: Int) {
        val list = myScenarios.value
        if (index !in 1..list.lastIndex) return
        val updated = list.toMutableList().also {
            val above = it[index - 1]
            it[index - 1] = it[index]
            it[index] = above
        }
        myScenarios.value = updated
        // Selection follows the moved scenario.
        if (mySelectedIndex.value == index) mySelectedIndex.value = index - 1
        else if (mySelectedIndex.value == index - 1) mySelectedIndex.value = index
        markDirty()
    }

    /** Swap the scenario at [index] with its successor.  No-op when [index] is the last index or out of range. */
    fun moveScenarioDown(index: Int) {
        val list = myScenarios.value
        if (index !in 0..(list.lastIndex - 1)) return
        val updated = list.toMutableList().also {
            val below = it[index + 1]
            it[index + 1] = it[index]
            it[index] = below
        }
        myScenarios.value = updated
        if (mySelectedIndex.value == index) mySelectedIndex.value = index + 1
        else if (mySelectedIndex.value == index + 1) mySelectedIndex.value = index
        markDirty()
    }

    /**
     *  Replace the scenario at [index] with [updated].  Used by the
     *  scenario editor window on Commit.  Throws if [updated].name
     *  collides with a different scenario in the document.
     */
    fun updateScenario(index: Int, updated: ScenarioSpec) {
        val list = myScenarios.value
        require(index in list.indices) {
            "updateScenario: index $index out of range 0..${list.lastIndex}"
        }
        val nameCollision = list.withIndex().any { (i, s) ->
            i != index && s.name == updated.name
        }
        require(!nameCollision) {
            "Scenario name '${updated.name}' already exists in the document"
        }
        if (list[index] == updated) return
        val oldName = list[index].name
        myScenarios.value = list.toMutableList().also { it[index] = updated }
        // Rename semantics: identity-breaking change.  Per the
        // lifecycle plan, rename = delete-old + add-new at the
        // result level — the old name's snapshot is dropped, and
        // the new name has no snapshot until the next Simulate.
        // Field-only edits (same name) leave the result intact;
        // the stale-results banner handles the user-facing
        // freshness signal.
        if (oldName != updated.name) {
            dropResultFor(oldName)
            if (oldName in myScenarioStatuses.value) {
                myScenarioStatuses.value = myScenarioStatuses.value - oldName
            }
        }
        markDirty()
    }

    /** Convenience: toggle the skip-on-run flag for the scenario at [index]. */
    fun setSkipOnRun(index: Int, skip: Boolean) {
        val list = myScenarios.value
        require(index in list.indices) {
            "setSkipOnRun: index $index out of range 0..${list.lastIndex}"
        }
        if (list[index].skipOnRun == skip) return
        myScenarios.value = list.toMutableList().also { it[index] = it[index].copy(skipOnRun = skip) }
        markDirty()
    }

    /** Set the selected-scenario index.  `-1` clears the selection.
     *  Out-of-range positive values are coerced to the last index. */
    fun setSelectedIndex(index: Int) {
        val list = myScenarios.value
        val target = when {
            index < 0 -> -1
            list.isEmpty() -> -1
            index > list.lastIndex -> list.lastIndex
            else -> index
        }
        if (mySelectedIndex.value != target) mySelectedIndex.value = target
        // Selection changes are not document edits — don't flip dirty.
    }

    // ── Save / Load ────────────────────────────────────────────────────────

    /**
     *  Outcome of [loadConfiguration].
     *
     *  - [Loaded] — populated successfully, possibly with notes about
     *    unresolved model references that the caller can surface as
     *    warnings.  In Phase C [warnings] is always empty; Phase I
     *    will populate it.
     *  - [Rejected] — the configuration was structurally unloadable;
     *    controller state left unchanged.
     */
    sealed class LoadResult {
        data class Loaded(val warnings: List<String> = emptyList()) : LoadResult()
        data class Rejected(val reason: String) : LoadResult()
    }

    /**
     *  Snapshot the current in-memory editor state as a
     *  [RunConfiguration].  Suitable for TOML serialization via
     *  [ksl.app.config.RunConfigurationToml.encode].  Pure read — does
     *  not mutate the controller, does not clear [isDirty].
     *
     *  The `outputConfig.outputDirectory` field is intentionally
     *  blanked in the snapshot — it's an install-local path computed
     *  at submit time from the workspace.
     */
    fun currentConfiguration(): RunConfiguration =
        RunConfiguration(
            scenarios = myScenarios.value,
            bundleRefs = deriveBundleRefs(myScenarios.value),
            outputConfig = myOutputConfig.value.copy(outputDirectory = null),
            executionMode = myExecutionMode.value
        )

    /**
     *  Build the `bundleRefs` manifest for a [RunConfiguration] from
     *  the scenarios' bundled model references plus the currently
     *  loaded bundles.  Every `ByBundleAndModelId.bundleId` referenced
     *  by [scenarios] appears in the result; the matching
     *  [LoadedBundle.sourceJar] (when present) is recorded as the
     *  candidate path so the saved document is portable.  Bundles
     *  that aren't currently loaded are still listed with empty
     *  [BundleRef.paths] so the validator's bundleRefs check
     *  passes — the substrate will surface unresolved refs at submit
     *  time as a separate validation step.
     */
    private fun deriveBundleRefs(scenarios: List<ScenarioSpec>): List<BundleRef> {
        val referenced = scenarios
            .mapNotNull { it.modelReference as? ModelReference.ByBundleAndModelId }
            .map { it.bundleId }
            .toSet()
        if (referenced.isEmpty()) return emptyList()
        val loadedById = myLoadedBundles.value.associateBy { it.bundle.bundleId }
        return referenced.sorted().map { id ->
            val path = loadedById[id]?.sourceJar?.toString()
            BundleRef(
                paths = listOfNotNull(path),
                bundleId = id
            )
        }
    }

    /**
     *  Replace the in-memory editor state with [config].  Clears
     *  [isDirty] on success.  Does not change [currentFile]; callers
     *  (typically the Open flow) should call [markSaved] separately
     *  after a successful load.
     *
     *  In Phase C this method assumes [config] is well-formed (the
     *  TOML codec produces structurally-valid `RunConfiguration`s).
     *  Phase I adds per-scenario model-reference resolution against
     *  loaded bundles, surfacing unresolved references in the
     *  returned [LoadResult.Loaded.warnings].
     */
    fun loadConfiguration(config: RunConfiguration): LoadResult {
        myScenarios.value = config.scenarios
        myOutputConfig.value = config.outputConfig.copy(outputDirectory = null)
        myExecutionMode.value = config.executionMode
        mySelectedIndex.value = if (config.scenarios.isEmpty()) -1 else 0
        // Clear dirty AFTER the StateFlow assignments so any
        // listener-triggered state flip is overwritten.
        myIsDirty.value = false
        // Also wipe any leftover run-time state from a previous
        // document so the Reports tab doesn't think a run completed
        // and stale FAILED badges don't appear next to fresh scenario
        // rows.  See [clearRunState] for the full list.
        clearRunState()
        return LoadResult.Loaded()
    }

    /**
     *  Reset editor state to empty defaults — equivalent to
     *  *File → Reset to Defaults*.  Clears scenarios, output config,
     *  execution mode, current file, and dirty — plus all run-time
     *  state via [clearRunState].
     */
    fun resetConfiguration() {
        myScenarios.value = emptyList()
        myOutputConfig.value = OutputConfig(enableKSLDatabase = true)
        myExecutionMode.value = ExecutionMode.SEQUENTIAL
        mySelectedIndex.value = -1
        myCurrentFile.value = null
        myIsDirty.value = false
        clearRunState()
    }

    /**
     *  Reset every piece of run-time state — last result, per-scenario
     *  statuses, replication progress, the "edited since last sim"
     *  flag, and the in-flight flag — so a freshly loaded or reset
     *  configuration starts from a clean slate.
     *
     *  Document-level configuration state (scenarios, output config,
     *  execution mode, current file, dirty) is the caller's
     *  responsibility; this helper is run-state only.
     *
     *  [eventFlow] has `replay = 0`, so historical events do not
     *  replay to new collectors and there is nothing to clear here.
     *  Already-attached collectors see only events emitted after
     *  they subscribed.
     */
    internal fun clearRunState() {
        myRunning.value = false
        myLastResult.value = null
        myScenarioStatuses.value = emptyMap()
        myReplicationProgress.value = emptyMap()
        myEditedSinceLastSim.value = false
    }

    /** Test seam — seed run-time state so [clearRunState],
     *  [loadConfiguration], and [resetConfiguration] paths can be
     *  verified.  Production code should not call this. */
    internal fun seedRunStateForTesting(
        lastResult: RunResult? = null,
        scenarioStatuses: Map<String, ScenarioStatus> = emptyMap(),
        replicationProgress: Map<String, Pair<Int, Int>> = emptyMap(),
        editedSinceLastSim: Boolean = false,
        running: Boolean = false
    ) {
        myLastResult.value = lastResult
        myScenarioStatuses.value = scenarioStatuses
        myReplicationProgress.value = replicationProgress
        myEditedSinceLastSim.value = editedSinceLastSim
        myRunning.value = running
    }

    /**
     *  Record that the current state has been persisted to [path].
     *  Sets [currentFile] and clears [isDirty].  Called by the
     *  frame's *Save* / *Save As…* handlers after a successful write.
     *
     *  Once-at-default auto-fill: if [OutputConfig.analysisName] is
     *  still at its default `"Untitled"` value, replace it with the
     *  saved file's stem (e.g. `mySim.toml` → `"mySim"`).  Thereafter
     *  the user owns the name — a subsequent *Save As* to a
     *  differently-named file does NOT silently rename the analysis,
     *  because the field is no longer at the default.
     */
    fun markSaved(path: Path) {
        myCurrentFile.value = path
        myIsDirty.value = false
        if (myOutputConfig.value.analysisName == "Untitled") {
            val stem = path.fileName.toString().substringBeforeLast('.')
            if (stem.isNotBlank()) {
                myOutputConfig.value =
                    myOutputConfig.value.copy(analysisName = stem)
            }
        }
    }

    override fun close() {
        currentHandle?.cancel("App closed")
        currentHandle = null
        session?.close()
        session = null
        edtScope.cancel("ScenarioAppController closed")
        myLoadedBundles.value.forEach { runCatching { it.close() } }
        modelDefaultsCache.clear()
    }
}
