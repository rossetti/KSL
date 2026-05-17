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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import ksl.app.config.ExecutionMode
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
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
     *  apps agree on naming).
     */
    val appNameSanitized: String = appName.replace(" ", "_")

    /**
     *  Workspace subdirectory dedicated to this app.  Equal to
     *  `settingsStore.activeWorkspace().resolve(appNameSanitized)`.
     *  All per-app files (saved configurations, model runtime
     *  output, rendered reports) live under here.  Read each access
     *  — the underlying `activeWorkspace()` is permitted to change
     *  between calls.
     */
    val appWorkspace: Path
        get() = settingsStore.activeWorkspace().resolve(appNameSanitized)

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
        val updated = list.toMutableList().also { it.removeAt(index) }
        myScenarios.value = updated
        mySelectedIndex.value = when {
            updated.isEmpty() -> -1
            index < updated.size -> index             // keep slot
            else -> updated.lastIndex                 // was last; shift up
        }
        markDirty()
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
        myScenarios.value = list.toMutableList().also { it[index] = updated }
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
            outputConfig = myOutputConfig.value.copy(outputDirectory = null),
            executionMode = myExecutionMode.value
        )

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
        return LoadResult.Loaded()
    }

    /**
     *  Reset editor state to empty defaults — equivalent to
     *  *File → Reset to Defaults*.  Clears scenarios, output config,
     *  execution mode, current file, and dirty.
     */
    fun resetConfiguration() {
        myScenarios.value = emptyList()
        myOutputConfig.value = OutputConfig(enableKSLDatabase = true)
        myExecutionMode.value = ExecutionMode.SEQUENTIAL
        mySelectedIndex.value = -1
        myCurrentFile.value = null
        myIsDirty.value = false
    }

    /**
     *  Record that the current state has been persisted to [path].
     *  Sets [currentFile] and clears [isDirty].  Called by the
     *  frame's *Save* / *Save As…* handlers after a successful write.
     */
    fun markSaved(path: Path) {
        myCurrentFile.value = path
        myIsDirty.value = false
    }

    override fun close() {
        edtScope.cancel("ScenarioAppController closed")
    }
}
