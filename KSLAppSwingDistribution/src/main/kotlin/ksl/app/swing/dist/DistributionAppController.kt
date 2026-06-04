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

package ksl.app.swing.dist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.data.DatasetImporter
import ksl.app.dist.session.FitResult
import ksl.app.dist.validation.FitConfigurationValidator
import ksl.app.editor.DocumentLifecycleController
import ksl.app.editor.RunLifecycleController
import ksl.app.session.AppWorkspacePaths
import ksl.app.settings.UserSettingsStore
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import java.nio.file.Files
import java.nio.file.Path

/** Header progress state. `Idle` hides the progress meter; `Running` drives it. */
sealed interface RunState {
    data object Idle : RunState
    data class Running(val label: String, val current: Int = 0, val total: Int = 0) : RunState
}

/** What the next fit will be, derived from how many datasets are selected. */
sealed interface RunMode {
    data object NoData : RunMode
    data object Single : RunMode
    data class Batch(val count: Int) : RunMode
}

/** Outcome of the most recent add-datasets attempt, shown in the Data tab. */
sealed interface LoadStatus {
    data object Idle : LoadStatus
    data object Loading : LoadStatus
    data class Loaded(val count: Int) : LoadStatus
    data class Failed(val message: String) : LoadStatus
}

/** One dataset in the working collection, tagged with where it came from. */
data class DatasetEntry(val name: String, val data: DoubleArray, val origin: String) {
    override fun equals(other: Any?): Boolean = other is DatasetEntry && other.name == name
    override fun hashCode(): Int = name.hashCode()
}

/**
 * Owns all editable state for the distribution-fitting app and exposes it as
 * read-only [StateFlow]s that the panels bind to. The app is essentially an
 * editor over a [FitConfiguration]: every mutator produces a new immutable
 * config, re-validates, and marks the document dirty.
 *
 * The Data tab assembles a [collection] of named datasets from any number of
 * sources via [addFrom]; `config.dataSource` is derived as a self-contained
 * `Inline` snapshot of that collection, so validation and persistence always
 * track exactly what will be fit. The run/event wiring (the
 * `DistributionModelingSession`) arrives in a later step.
 */
class DistributionAppController(val appName: String) {

    /** EDT-confined scope; all widget-facing flow collection runs here. */
    val edtScope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    private val importer: DatasetImporter = DatasetImporter.default
    private val documentLifecycle = DocumentLifecycleController()
    private val runLifecycle = RunLifecycleController<FitResult>()

    /** User-wide settings (working directory, recent lists). Backed by ~/.ksl/settings.toml. */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /**
     * This app's home folder under the active workspace —
     * `<activeWorkspace>/<appName>/` — matching the sibling apps. Resolved
     * lazily so it tracks changes to the working directory; not created here.
     */
    val appWorkspace: Path
        get() = AppWorkspacePaths.appWorkspaceDir(settingsStore.activeWorkspace(), appName)

    /** Returns [appWorkspace], creating it if missing (e.g. as a file-dialog start dir). */
    fun ensureAppWorkspace(): Path {
        val dir = appWorkspace
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    /** Emitted on New (and later Open) so views can clear transient editor state. */
    private val myDocumentReset = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val documentReset: SharedFlow<Unit> = myDocumentReset.asSharedFlow()

    // --- analysis name (document label / report title / default filename) ---
    private val myAnalysisName = MutableStateFlow("untitled")
    val analysisName: StateFlow<String> = myAnalysisName.asStateFlow()

    // --- the authored configuration -----------------------------------------
    private val myConfig = MutableStateFlow(defaultConfig(DistributionKind.CONTINUOUS))
    val config: StateFlow<FitConfiguration> = myConfig.asStateFlow()

    // --- validate-on-edit ----------------------------------------------------
    private val myValidation = MutableStateFlow(validate(myConfig.value))
    val validation: StateFlow<ValidationResult> = myValidation.asStateFlow()

    // --- the working dataset collection --------------------------------------
    private val myCollection = MutableStateFlow<List<DatasetEntry>>(emptyList())
    val collection: StateFlow<List<DatasetEntry>> = myCollection.asStateFlow()

    private val mySelectedDatasets = MutableStateFlow<Set<String>>(emptySet())
    val selectedDatasets: StateFlow<Set<String>> = mySelectedDatasets.asStateFlow()

    private val myDataLoadStatus = MutableStateFlow<LoadStatus>(LoadStatus.Idle)
    val dataLoadStatus: StateFlow<LoadStatus> = myDataLoadStatus.asStateFlow()

    // --- run state -----------------------------------------------------------
    private val myRunState = MutableStateFlow<RunState>(RunState.Idle)
    val runState: StateFlow<RunState> = myRunState.asStateFlow()

    // --- document + run lifecycle (composed from KSLCore controllers) --------
    val currentFile: StateFlow<Path?> = documentLifecycle.currentFile
    val isDirty: StateFlow<Boolean> = documentLifecycle.isDirty
    val lastResult: StateFlow<FitResult?> = runLifecycle.lastResult

    // --- derived: single vs batch vs not-yet-runnable ------------------------
    val mode: StateFlow<RunMode> = mySelectedDatasets
        .map { it.toMode() }
        .stateIn(edtScope, SharingStarted.Eagerly, RunMode.NoData)

    /** Sets the analysis name (document label); not part of validation. */
    fun setAnalysisName(name: String) {
        if (name == myAnalysisName.value) return
        myAnalysisName.value = name
        documentLifecycle.markDirty()
        runLifecycle.markEdited()
    }

    /**
     * Switches the distribution kind. Because the available estimators differ
     * by kind, the estimator selection is reset to the new kind's defaults.
     */
    fun setKind(kind: DistributionKind) {
        if (kind == myConfig.value.kind) return
        myConfig.update {
            it.copy(kind = kind, estimatorIds = FittingCatalog.defaultEstimatorIds(kind))
        }
        onConfigChanged()
    }

    fun setAutomaticShifting(on: Boolean) {
        if (on == myConfig.value.automaticShifting) return
        myConfig.update { it.copy(automaticShifting = on) }
        onConfigChanged()
    }

    // --- estimator selection -------------------------------------------------

    fun toggleEstimator(id: String, included: Boolean) {
        val current = myConfig.value.estimatorIds
        val updated = if (included) current + id else current - id
        if (updated == current) return
        myConfig.update { it.copy(estimatorIds = updated) }
        onConfigChanged()
    }

    /** Resets the estimator selection to the defaults for the current kind. */
    fun setEstimatorDefaults() {
        myConfig.update { it.copy(estimatorIds = FittingCatalog.defaultEstimatorIds(it.kind)) }
        onConfigChanged()
    }

    fun selectAllEstimators() {
        val all = FittingCatalog.estimators
            .filter { it.kind == myConfig.value.kind }
            .mapTo(mutableSetOf()) { it.id }
        myConfig.update { it.copy(estimatorIds = all) }
        onConfigChanged()
    }

    fun selectNoEstimators() {
        if (myConfig.value.estimatorIds.isEmpty()) return
        myConfig.update { it.copy(estimatorIds = emptySet()) }
        onConfigChanged()
    }

    // --- dataset collection --------------------------------------------------

    /**
     * Imports [ref] off the EDT and appends every resulting dataset to the
     * collection (renaming on name clashes), selecting the new datasets.
     * [origin] is a short provenance label shown in the table (e.g. "inline"
     * or the file name).
     */
    fun addFrom(ref: DataSourceReference, origin: String) {
        myDataLoadStatus.value = LoadStatus.Loading
        edtScope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { importer.import(ref) } }
            result.onSuccess { datasets ->
                val added = mutableListOf<String>()
                myCollection.update { current ->
                    val names = current.mapTo(mutableSetOf()) { it.name }
                    val newEntries = datasets.map { d ->
                        val unique = uniqueName(d.name, names)
                        names += unique
                        added += unique
                        DatasetEntry(unique, d.data, origin)
                    }
                    current + newEntries
                }
                mySelectedDatasets.update { it + added }
                rebuildFromCollection()
                myDataLoadStatus.value = LoadStatus.Loaded(datasets.size)
            }.onFailure { t ->
                myDataLoadStatus.value = LoadStatus.Failed(t.message ?: t::class.simpleName ?: "import failed")
            }
        }
    }

    fun removeDataset(name: String) {
        if (myCollection.value.none { it.name == name }) return
        myCollection.update { it.filterNot { entry -> entry.name == name } }
        mySelectedDatasets.update { it - name }
        rebuildFromCollection()
    }

    fun clearDatasets() {
        myCollection.value = emptyList()
        mySelectedDatasets.value = emptySet()
        myDataLoadStatus.value = LoadStatus.Idle
        rebuildFromCollection()
    }

    fun setDatasetIncluded(name: String, included: Boolean) {
        mySelectedDatasets.update { if (included) it + name else it - name }
    }

    fun selectAllDatasets() {
        mySelectedDatasets.value = myCollection.value.mapTo(mutableSetOf()) { it.name }
    }

    fun selectNoDatasets() {
        mySelectedDatasets.value = emptySet()
    }

    /** Resets to a fresh, empty document. */
    fun newDocument() {
        myAnalysisName.value = "untitled"
        myConfig.value = defaultConfig(DistributionKind.CONTINUOUS)
        myCollection.value = emptyList()
        mySelectedDatasets.value = emptySet()
        myDataLoadStatus.value = LoadStatus.Idle
        myValidation.value = validate(myConfig.value)
        runLifecycle.reset()
        documentLifecycle.reset()
        myDocumentReset.tryEmit(Unit)
    }

    /** Cancels the EDT scope. The fitting session is closed here once wired. */
    fun dispose() {
        edtScope.cancel()
    }

    // --- internals -----------------------------------------------------------

    /** Mirrors the collection into `config.dataSource` as a self-contained Inline snapshot. */
    private fun rebuildFromCollection() {
        val map = myCollection.value.associate { it.name to it.data }
        myConfig.update { it.copy(dataSource = DataSourceReference.Inline(map)) }
        onConfigChanged()
    }

    /** Recompute validation and flag the document edited; call after any config change. */
    private fun onConfigChanged() {
        myValidation.value = validate(myConfig.value)
        documentLifecycle.markDirty()
        runLifecycle.markEdited()
    }

    private fun uniqueName(base: String, existing: Set<String>): String {
        val name = base.ifBlank { "dataset" }
        if (name !in existing) return name
        var i = 2
        while ("$name ($i)" in existing) i++
        return "$name ($i)"
    }

    private fun validate(config: FitConfiguration): ValidationResult =
        FitConfigurationValidator.validate(FitSpec.Single(config)) + clientChecks(config)

    /**
     * Client-side validation layered on top of the engine validator: rules the
     * GUI enforces but the substrate leaves to the caller. Requires at least
     * one estimator so a fit produces results.
     */
    private fun clientChecks(config: FitConfiguration): ValidationResult {
        val errors = mutableListOf<FieldError>()
        if (config.estimatorIds.isEmpty()) {
            errors += FieldError(
                path = "config.estimatorIds",
                message = "select at least one estimator",
                severity = ValidationSeverity.ERROR,
                code = "fit.estimator.none"
            )
        }
        return ValidationResult(errors = errors.toList())
    }

    private fun defaultConfig(kind: DistributionKind) = FitConfiguration(
        dataSource = DataSourceReference.Inline(emptyMap()),
        kind = kind,
        estimatorIds = FittingCatalog.defaultEstimatorIds(kind),
        scoringModelIds = FittingCatalog.defaultScoringModelIds()
    )

    private fun Set<String>.toMode(): RunMode = when (size) {
        0 -> RunMode.NoData
        1 -> RunMode.Single
        else -> RunMode.Batch(size)
    }
}
