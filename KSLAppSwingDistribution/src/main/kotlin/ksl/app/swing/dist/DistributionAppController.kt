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
import ksl.app.dist.DistributionModelingSession
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.BootstrapConfig
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.EvaluationMethod
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.config.NamedFitConfiguration
import ksl.app.dist.config.RankingMethod
import ksl.app.dist.data.DatasetImporter
import ksl.app.dist.session.FitEvent
import ksl.app.dist.session.FitHandle
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

/** What the next fit will be, derived from how many datasets are included. */
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

/** Per-dataset progress during a fit run, shown in the Fitting tab. */
enum class DatasetRunStatus { IDLE, QUEUED, RUNNING, DONE, FAILED }

/** One dataset in the working collection, tagged with where it came from. */
data class DatasetEntry(val name: String, val data: DoubleArray, val origin: String) {
    override fun equals(other: Any?): Boolean = other is DatasetEntry && other.name == name
    override fun hashCode(): Int = name.hashCode()
}

/**
 * Per-dataset fitting settings. Each dataset in a batch is fit with its own
 * kind, estimators, scoring models, ranking/evaluation methods, and automatic-
 * shift flag (the substrate's heterogeneous batch). Seeded from the defaults
 * when a dataset is added.
 */
data class DatasetFitSettings(
    val kind: DistributionKind = DistributionKind.CONTINUOUS,
    val includeInFit: Boolean = true,
    val automaticShift: Boolean = true,
    val estimatorIds: Set<String> = FittingCatalog.defaultEstimatorIds(DistributionKind.CONTINUOUS),
    val scoringModelIds: Set<String> = FittingCatalog.defaultScoringModelIds(),
    val rankingMethod: RankingMethod = RankingMethod.ORDINAL,
    val evaluationMethod: EvaluationMethod = EvaluationMethod.SCORING
)

/**
 * Owns all editable state for the distribution-fitting app as read-only
 * [StateFlow]s the panels bind to. The app is an editor over a collection of
 * datasets, each with its own [DatasetFitSettings]; at fit time the included
 * datasets are assembled into a `FitSpec` (single, or a heterogeneous batch).
 * Every mutator re-validates and marks the document dirty.
 */
class DistributionAppController(val appName: String) {

    /** EDT-confined scope; all widget-facing flow collection runs here. */
    val edtScope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    private val importer: DatasetImporter = DatasetImporter.default
    private val documentLifecycle = DocumentLifecycleController()
    private val runLifecycle = RunLifecycleController<FitResult>()
    private val session = DistributionModelingSession()
    private var currentHandle: FitHandle? = null

    /** User-wide settings (working directory, recent lists). Backed by ~/.ksl/settings.toml. */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /** This app's home folder under the active workspace — `<activeWorkspace>/<appName>/`. */
    val appWorkspace: Path
        get() = AppWorkspacePaths.appWorkspaceDir(settingsStore.activeWorkspace(), appName)

    /** Returns [appWorkspace], creating it if missing (e.g. as a file-dialog start dir). */
    fun ensureAppWorkspace(): Path {
        val dir = appWorkspace
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    /** Folder for the current analysis; holds its config and per-dataset work. Created if missing. */
    fun analysisDir(): Path {
        val dir = appWorkspace.resolve(sanitizePathSegment(myAnalysisName.value).ifBlank { "untitled" })
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    /** Per-dataset work folder within the current analysis; created if missing. */
    fun datasetOutputDir(name: String): Path {
        val dir = analysisDir().resolve(sanitizePathSegment(name).ifBlank { "dataset" })
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    /** Emitted on New (and later Open) so views can clear transient editor state. */
    private val myDocumentReset = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val documentReset: SharedFlow<Unit> = myDocumentReset.asSharedFlow()

    // --- analysis name (document label / report title / default filename) ----
    private val myAnalysisName = MutableStateFlow("untitled")
    val analysisName: StateFlow<String> = myAnalysisName.asStateFlow()

    // --- the working dataset collection + per-dataset settings ---------------
    private val myCollection = MutableStateFlow<List<DatasetEntry>>(emptyList())
    val collection: StateFlow<List<DatasetEntry>> = myCollection.asStateFlow()

    private val mySettings = MutableStateFlow<Map<String, DatasetFitSettings>>(emptyMap())
    val settings: StateFlow<Map<String, DatasetFitSettings>> = mySettings.asStateFlow()

    private val myDataLoadStatus = MutableStateFlow<LoadStatus>(LoadStatus.Idle)
    val dataLoadStatus: StateFlow<LoadStatus> = myDataLoadStatus.asStateFlow()

    // --- global fitting options ----------------------------------------------
    private val myBootstrap = MutableStateFlow<BootstrapConfig?>(null)
    val bootstrap: StateFlow<BootstrapConfig?> = myBootstrap.asStateFlow()

    // --- validate-on-edit ----------------------------------------------------
    private val myValidation = MutableStateFlow(computeValidation())
    val validation: StateFlow<ValidationResult> = myValidation.asStateFlow()

    // --- run state -----------------------------------------------------------
    private val myRunState = MutableStateFlow<RunState>(RunState.Idle)
    val runState: StateFlow<RunState> = myRunState.asStateFlow()

    private val myDatasetStatus = MutableStateFlow<Map<String, DatasetRunStatus>>(emptyMap())
    val datasetStatus: StateFlow<Map<String, DatasetRunStatus>> = myDatasetStatus.asStateFlow()

    // --- document + run lifecycle (composed from KSLCore controllers) --------
    val currentFile: StateFlow<Path?> = documentLifecycle.currentFile
    val isDirty: StateFlow<Boolean> = documentLifecycle.isDirty
    val lastResult: StateFlow<FitResult?> = runLifecycle.lastResult

    // --- derived: single vs batch vs not-yet-runnable ------------------------
    val mode: StateFlow<RunMode> = mySettings
        .map { settings -> settings.values.count { it.includeInFit }.toMode() }
        .stateIn(edtScope, SharingStarted.Eagerly, RunMode.NoData)

    /** Sets the analysis name (document label); not part of validation. */
    fun setAnalysisName(name: String) {
        if (name == myAnalysisName.value) return
        myAnalysisName.value = name
        documentLifecycle.markDirty()
        runLifecycle.markEdited()
    }

    // --- dataset collection --------------------------------------------------

    /**
     * Imports [ref] off the EDT and appends every resulting dataset to the
     * collection (renaming on clashes), seeding each new dataset with default
     * fit settings. [origin] is a short provenance label shown in the table.
     */
    fun addFrom(ref: DataSourceReference, origin: String) {
        myDataLoadStatus.value = LoadStatus.Loading
        edtScope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { importer.import(ref) } }
            result.onSuccess { datasets ->
                val names = myCollection.value.mapTo(mutableSetOf()) { it.name }
                val newEntries = datasets.map { d ->
                    val unique = uniqueName(d.name, names)
                    names += unique
                    DatasetEntry(unique, d.data, origin)
                }
                myCollection.update { it + newEntries }
                mySettings.update { current ->
                    current + newEntries.associate { it.name to DatasetFitSettings() }
                }
                onModelChanged()
                myDataLoadStatus.value = LoadStatus.Loaded(datasets.size)
            }.onFailure { t ->
                myDataLoadStatus.value = LoadStatus.Failed(t.message ?: t::class.simpleName ?: "import failed")
            }
        }
    }

    fun removeDataset(name: String) {
        if (myCollection.value.none { it.name == name }) return
        myCollection.update { it.filterNot { entry -> entry.name == name } }
        mySettings.update { it - name }
        myDatasetStatus.update { it - name }
        onModelChanged()
    }

    fun clearDatasets() {
        myCollection.value = emptyList()
        mySettings.value = emptyMap()
        myDatasetStatus.value = emptyMap()
        myDataLoadStatus.value = LoadStatus.Idle
        onModelChanged()
    }

    // --- per-dataset fitting settings ----------------------------------------

    fun setDatasetIncluded(name: String, included: Boolean) =
        updateSetting(name) { it.copy(includeInFit = included) }

    /** Changes a dataset's kind and reseeds its estimators to that kind's defaults. */
    fun setDatasetKind(name: String, kind: DistributionKind) =
        updateSetting(name) {
            if (it.kind == kind) it
            else it.copy(kind = kind, estimatorIds = FittingCatalog.defaultEstimatorIds(kind))
        }

    fun setDatasetShift(name: String, on: Boolean) =
        updateSetting(name) { it.copy(automaticShift = on) }

    fun setDatasetEstimators(name: String, ids: Set<String>) =
        updateSetting(name) { it.copy(estimatorIds = ids) }

    fun setDatasetScoring(
        name: String,
        ids: Set<String>,
        ranking: RankingMethod,
        evaluation: EvaluationMethod
    ) = updateSetting(name) {
        it.copy(scoringModelIds = ids, rankingMethod = ranking, evaluationMethod = evaluation)
    }

    /** Applies an estimator selection to every dataset currently of [kind]. */
    fun setEstimatorsForAllOfKind(kind: DistributionKind, ids: Set<String>) =
        updateAllSettings { if (it.kind == kind) it.copy(estimatorIds = ids) else it }

    /** Applies a scoring/ranking/evaluation selection to every dataset currently of [kind]. */
    fun setScoringForAllOfKind(
        kind: DistributionKind,
        ids: Set<String>,
        ranking: RankingMethod,
        evaluation: EvaluationMethod
    ) = updateAllSettings {
        if (it.kind == kind) {
            it.copy(scoringModelIds = ids, rankingMethod = ranking, evaluationMethod = evaluation)
        } else {
            it
        }
    }

    // --- bulk "apply to all" conveniences ------------------------------------

    fun setKindForAll(kind: DistributionKind) =
        updateAllSettings { it.copy(kind = kind, estimatorIds = FittingCatalog.defaultEstimatorIds(kind)) }

    fun setShiftForAll(on: Boolean) = updateAllSettings { it.copy(automaticShift = on) }

    fun resetEstimatorsForAll() =
        updateAllSettings { it.copy(estimatorIds = FittingCatalog.defaultEstimatorIds(it.kind)) }

    fun resetScoringForAll() = updateAllSettings {
        it.copy(
            scoringModelIds = FittingCatalog.defaultScoringModelIds(),
            rankingMethod = RankingMethod.ORDINAL,
            evaluationMethod = EvaluationMethod.SCORING
        )
    }

    /** Resets to a fresh, empty document. */
    fun newDocument() {
        myAnalysisName.value = "untitled"
        myCollection.value = emptyList()
        mySettings.value = emptyMap()
        myDatasetStatus.value = emptyMap()
        myBootstrap.value = null
        myDataLoadStatus.value = LoadStatus.Idle
        myValidation.value = computeValidation()
        runLifecycle.reset()
        documentLifecycle.reset()
        myDocumentReset.tryEmit(Unit)
    }

    /** Cancels the EDT scope and closes the fitting session. */
    fun dispose() {
        edtScope.cancel()
        runCatching { session.close() }
    }

    // --- run control ---------------------------------------------------------

    /** Submits the included datasets to the fitting session and tracks progress. */
    fun fit() {
        if (myRunState.value !is RunState.Idle) return
        if (!myValidation.value.isValid) return
        val orderedNames = includedEntries().map { it.name }
        val spec = assembleSpec() ?: return
        // Reset all prior statuses/results so stale marks don't linger.
        myDatasetStatus.value = orderedNames.associateWith { DatasetRunStatus.QUEUED }
        runLifecycle.setLastResult(null)
        myRunState.value = RunState.Running("starting…", 0, orderedNames.size)
        val handle = session.submit(spec)
        currentHandle = handle
        edtScope.launch { handle.events.collect { onFitEvent(it, orderedNames) } }
        edtScope.launch { onFitResult(handle.result.await()) }
    }

    fun cancel() {
        currentHandle?.cancel("Cancelled by user")
    }

    /** Clears all fit results and per-dataset run statuses (leaves the configuration intact). */
    fun clearResults() {
        if (myRunState.value !is RunState.Idle) return
        myDatasetStatus.value = emptyMap()
        runLifecycle.setLastResult(null)
    }

    private fun onFitEvent(event: FitEvent, orderedNames: List<String>) {
        when (event) {
            is FitEvent.FitStarted -> {
                setStatus(event.datasetName, DatasetRunStatus.RUNNING)
                myRunState.value = RunState.Running("fitting ${event.datasetName}…", 0, 1)
            }
            is FitEvent.BatchFitStarted -> {
                myRunState.value = RunState.Running("0 of ${event.datasetCount}", 0, event.datasetCount)
                orderedNames.firstOrNull()?.let { setStatus(it, DatasetRunStatus.RUNNING) }
            }
            is FitEvent.DatasetCompleted -> {
                setStatus(event.datasetName, if (event.success) DatasetRunStatus.DONE else DatasetRunStatus.FAILED)
                myRunState.value = RunState.Running("${event.index} of ${event.total}", event.index, event.total)
                orderedNames.getOrNull(event.index)?.let { setStatus(it, DatasetRunStatus.RUNNING) }
            }
            is FitEvent.FitCompleted,
            is FitEvent.BatchFitCompleted,
            is FitEvent.FitFailed,
            is FitEvent.FitCancelled -> Unit // terminal handled via the result deferred
        }
    }

    private fun onFitResult(result: FitResult) {
        runLifecycle.markRunCompleted(result)
        myRunState.value = RunState.Idle
        currentHandle = null
        when (result) {
            is FitResult.Completed -> setStatus(result.report.datasetName, DatasetRunStatus.DONE)
            is FitResult.BatchCompleted -> {
                result.report.results.forEach { setStatus(it.datasetName, DatasetRunStatus.DONE) }
                result.report.failures.forEach { setStatus(it.name, DatasetRunStatus.FAILED) }
            }
            is FitResult.Failed -> markUnfinished(DatasetRunStatus.FAILED)
            is FitResult.Cancelled -> markUnfinished(DatasetRunStatus.IDLE)
        }
    }

    private fun markUnfinished(status: DatasetRunStatus) {
        myDatasetStatus.update { current ->
            current.mapValues { (_, s) ->
                if (s == DatasetRunStatus.QUEUED || s == DatasetRunStatus.RUNNING) status else s
            }
        }
    }

    private fun setStatus(name: String, status: DatasetRunStatus) {
        myDatasetStatus.update { it + (name to status) }
    }

    /**
     * Assembles the included datasets into a runnable spec: `Single` when one
     * dataset is included, a heterogeneous `Batch` when several. Returns null
     * when nothing is included.
     */
    fun assembleSpec(): FitSpec? {
        val included = includedEntries()
        if (included.isEmpty()) return null
        val configs = included.map { entry ->
            val s = mySettings.value.getValue(entry.name)
            NamedFitConfiguration(
                name = entry.name,
                config = FitConfiguration(
                    dataSource = DataSourceReference.Inline(mapOf(entry.name to entry.data)),
                    kind = s.kind,
                    estimatorIds = s.estimatorIds,
                    scoringModelIds = s.scoringModelIds,
                    automaticShifting = s.automaticShift,
                    rankingMethod = s.rankingMethod,
                    evaluationMethod = s.evaluationMethod,
                    bootstrap = myBootstrap.value,
                    // The GUI renders the canonical report locally from the DTO + raw data
                    // (FitReports.single); the server-side HTML is a remote-only fallback.
                    includeStandardReport = false
                )
            )
        }
        return if (configs.size == 1) FitSpec.Single(configs.single().config) else FitSpec.Batch(configs)
    }

    // --- internals -----------------------------------------------------------

    private fun includedEntries(): List<DatasetEntry> =
        myCollection.value.filter { mySettings.value[it.name]?.includeInFit == true }

    private fun updateSetting(name: String, transform: (DatasetFitSettings) -> DatasetFitSettings) {
        val current = mySettings.value[name] ?: return
        mySettings.update { it + (name to transform(current)) }
        onModelChanged()
    }

    private fun updateAllSettings(transform: (DatasetFitSettings) -> DatasetFitSettings) {
        if (mySettings.value.isEmpty()) return
        mySettings.update { map -> map.mapValues { (_, s) -> transform(s) } }
        onModelChanged()
    }

    private fun onModelChanged() {
        myValidation.value = computeValidation()
        documentLifecycle.markDirty()
        runLifecycle.markEdited()
    }

    private fun computeValidation(): ValidationResult {
        if (myCollection.value.isEmpty()) {
            return ValidationResult(errors = listOf(error("datasets", "add at least one dataset", "fit.datasets.empty")))
        }
        val included = includedEntries()
        if (included.isEmpty()) {
            return ValidationResult(
                errors = listOf(error("datasets", "include at least one dataset to fit", "fit.datasets.noneIncluded"))
            )
        }
        val spec = assembleSpec() ?: return ValidationResult()
        return FitConfigurationValidator.validate(spec) + clientChecks(included)
    }

    /** Client-side rules the GUI enforces but the engine leaves to the caller. */
    private fun clientChecks(included: List<DatasetEntry>): ValidationResult {
        val errors = mutableListOf<FieldError>()
        included.forEach { entry ->
            val s = mySettings.value.getValue(entry.name)
            if (s.estimatorIds.isEmpty()) {
                errors += error(
                    "dataset[${entry.name}].estimators",
                    "select at least one estimator for '${entry.name}'",
                    "fit.estimator.none"
                )
            }
        }
        return ValidationResult(errors = errors.toList())
    }

    private fun error(path: String, message: String, code: String): FieldError =
        FieldError(path = path, message = message, severity = ValidationSeverity.ERROR, code = code)

    private fun sanitizePathSegment(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun uniqueName(base: String, existing: Set<String>): String {
        val name = base.ifBlank { "dataset" }
        if (name !in existing) return name
        var i = 2
        while ("$name ($i)" in existing) i++
        return "$name ($i)"
    }

    private fun Int.toMode(): RunMode = when (this) {
        0 -> RunMode.NoData
        1 -> RunMode.Single
        else -> RunMode.Batch(this)
    }
}
