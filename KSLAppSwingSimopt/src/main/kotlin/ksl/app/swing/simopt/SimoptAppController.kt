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

package ksl.app.swing.simopt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import ksl.app.bundle.BundleModelProvider
import ksl.app.bundle.LoadedBundle
import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.RVParameterOverride
import ksl.app.config.analysisNameFromFileStem
import ksl.app.config.optimization.AlgorithmKind
import ksl.app.config.optimization.CESamplerSpec
import ksl.app.config.optimization.CoolingScheduleSpec
import ksl.app.config.optimization.EvaluationSpec
import ksl.app.config.optimization.LinearConstraintSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationOutputConfig
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationRunConfigurationToml
import ksl.app.config.optimization.OptimizationSolverFactory
import ksl.app.config.optimization.OptimizationType
import ksl.app.config.optimization.RandomRestartSpec
import ksl.app.config.optimization.TemperatureSpec
import ksl.app.config.optimization.PenaltyFunctionSpec
import ksl.app.config.optimization.ResponseConstraintSpec
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.SolverTrackingSpec
import ksl.app.config.sanitizeAnalysisName
import ksl.app.orchestrator.OptimizationOrchestrator
import ksl.app.editor.BundleLibraryController
import ksl.app.editor.DocumentLifecycleController
import ksl.app.editor.RunLifecycleController
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.settings.UserSettingsStore
import ksl.app.optimization.results.LatestBestSnapshot
import ksl.app.optimization.results.ResultsArtifactWriter
import ksl.app.optimization.results.ResultsStatus
import ksl.app.optimization.paths.OptimizationPaths
import ksl.app.swing.simopt.stepper.Step
import ksl.app.validation.FieldError
import ksl.app.validation.OptimizationConfigurationValidator
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import ksl.controls.ModelControlsExport
import ksl.controls.experiments.ExperimentRunParameters
import ksl.simopt.solvers.Solver
import ksl.simulation.ModelDescriptor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * State + lifecycle façade for the SimOpt App.
 *
 * Modelled on `ExperimentAppController` and `ScenarioAppController` —
 * the same controller/frame split via `StateFlow`s, the same R1
 * lifecycle (structural edits drop `lastResult`; preferences mark
 * dirty only), the same identity-coupling to the loaded document.
 *
 * Phase O2 lands the skeleton: every `StateFlow` is declared and
 * every mutator marks dirty appropriately, but the run lifecycle
 * (`submit` / `cancel`) is intentionally stubbed.  Phase O7b wires
 * the live `KSLAppSession` submission path.
 *
 * @property appName user-facing application name; surfaces in the
 *           frame title and in the workspace subdirectory layout
 */
class SimoptAppController(
    val appName: String
) : AutoCloseable {

    /** Scope for EDT-confined coroutine work — collectors driving
     *  Swing updates, save/load tasks, event-flow forwarders. */
    val edtScope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    /** User-wide settings (workspace, recent list). */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /** Sanitised [appName] for filesystem-segment use.  Mirrors the
     *  other apps' convention (spaces → underscores).  Delegates to
     *  [AppWorkspacePaths.sanitizeAppName]. */
    val appNameSanitized: String = AppWorkspacePaths.sanitizeAppName(appName)

    /** Workspace subdirectory dedicated to this app.  Identical
     *  semantics to the Experiment / Scenario apps:
     *  `<active-workspace>/<appNameSanitized>/`.  Delegates to
     *  [AppWorkspacePaths.appWorkspaceDir]. */
    val appWorkspace: Path
        get() = AppWorkspacePaths.appWorkspaceDir(settingsStore.activeWorkspace(), appName)

    // ── Document state ─────────────────────────────────────────────────────
    //
    // Shape mirrors OptimizationRunConfiguration.  Nullable specs
    // (modelTemplate / problemSpec / solverSpec) start at null on a
    // fresh document and are populated as the user walks the steps.

    private val myOutput = MutableStateFlow(OptimizationOutputConfig())
    /** Document-wide output settings (analysis name + host-resolved
     *  output directory). */
    val output: StateFlow<OptimizationOutputConfig> = myOutput.asStateFlow()

    private val myModelTemplate = MutableStateFlow<ModelRunTemplate?>(null)
    /** Baseline model-construction template.  `null` on a fresh
     *  document; populated by the Model step once the user picks a
     *  bundle + model.  Step.MODEL completion gate. */
    val modelTemplate: StateFlow<ModelRunTemplate?> = myModelTemplate.asStateFlow()

    // ── Problem-spec pieces ────────────────────────────────────────────────
    //
    // The Problem step authors a [OptimizationProblemSpec] incrementally.
    // The substrate spec's `init {}` requires both a non-blank
    // objectiveResponseName AND a non-empty inputs list, so we cannot
    // pre-construct a "partial" spec — instead, every piece lives in its
    // own StateFlow and [problemSpec] is published as a derived view
    // that becomes non-null only when the required pieces are present.

    private val myObjectiveResponseName = MutableStateFlow<String?>(null)
    /** Name of the model response being optimized.  `null` until the
     *  user picks one on the Problem step. */
    val objectiveResponseName: StateFlow<String?> = myObjectiveResponseName.asStateFlow()

    private val myOptimizationType = MutableStateFlow(OptimizationType.MINIMIZE)
    /** Minimize or maximize.  Defaults to MINIMIZE. */
    val optimizationType: StateFlow<OptimizationType> = myOptimizationType.asStateFlow()

    private val myProblemName = MutableStateFlow<String?>(null)
    /** Optional human-readable problem name (e.g. "InventoryOpt"). */
    val problemName: StateFlow<String?> = myProblemName.asStateFlow()

    private val myIndifferenceZoneParameter = MutableStateFlow(0.0)
    /** Smallest objective-function difference considered practically
     *  meaningful.  Must be >= 0 and finite.  Default 0.0. */
    val indifferenceZoneParameter: StateFlow<Double> = myIndifferenceZoneParameter.asStateFlow()

    private val myObjectiveGranularity = MutableStateFlow(0.0)
    /** Granularity applied to the objective function value.  0.0 means
     *  full precision.  Default 0.0. */
    val objectiveGranularity: StateFlow<Double> = myObjectiveGranularity.asStateFlow()

    private val myInputs = MutableStateFlow<List<OptimizationInputSpec>>(emptyList())
    /** Ordered list of decision variables.  Step.PROBLEM completion
     *  requires this to be non-empty AND [objectiveResponseName] to be
     *  non-null. */
    val inputs: StateFlow<List<OptimizationInputSpec>> = myInputs.asStateFlow()

    private val mySelectedInputIndex = MutableStateFlow(-1)
    /** Index of the currently-selected decision variable in [inputs],
     *  or `-1` when nothing is selected.  Auto-shifts on add / delete
     *  / reorder. */
    val selectedInputIndex: StateFlow<Int> = mySelectedInputIndex.asStateFlow()

    private val myResponseNames = MutableStateFlow<List<String>>(emptyList())
    /** Additional response names referenced by response constraints
     *  (the objective response is implied and need not be repeated).
     *  Phase O5 lands the chip-row editor; O4 only carries the flow
     *  so TOML round-trip preserves the list. */
    val responseNames: StateFlow<List<String>> = myResponseNames.asStateFlow()

    private val myLinearConstraints = MutableStateFlow<List<LinearConstraintSpec>>(emptyList())
    /** Linear constraints over the decision variables. */
    val linearConstraints: StateFlow<List<LinearConstraintSpec>> = myLinearConstraints.asStateFlow()

    private val mySelectedLinearConstraintIndex = MutableStateFlow(-1)
    /** Index of the currently-selected linear constraint, or `-1`
     *  when nothing is selected.  Auto-shifts on add / delete / reorder. */
    val selectedLinearConstraintIndex: StateFlow<Int> = mySelectedLinearConstraintIndex.asStateFlow()

    private val myResponseConstraints = MutableStateFlow<List<ResponseConstraintSpec>>(emptyList())
    /** Constraints on simulation responses. */
    val responseConstraints: StateFlow<List<ResponseConstraintSpec>> = myResponseConstraints.asStateFlow()

    private val mySelectedResponseConstraintIndex = MutableStateFlow(-1)
    /** Index of the currently-selected response constraint, or `-1`
     *  when nothing is selected.  Auto-shifts on add / delete / reorder. */
    val selectedResponseConstraintIndex: StateFlow<Int> = mySelectedResponseConstraintIndex.asStateFlow()

    private val myDefaultLinearPenalty = MutableStateFlow<PenaltyFunctionSpec>(
        PenaltyFunctionSpec.DynamicPolynomial()
    )
    /** Problem-level default penalty function for linear constraints. */
    val defaultLinearPenalty: StateFlow<PenaltyFunctionSpec> = myDefaultLinearPenalty.asStateFlow()

    private val myDefaultResponsePenalty = MutableStateFlow<PenaltyFunctionSpec>(
        PenaltyFunctionSpec.WithMemory()
    )
    /** Problem-level default penalty function for response constraints. */
    val defaultResponsePenalty: StateFlow<PenaltyFunctionSpec> = myDefaultResponsePenalty.asStateFlow()

    private val myProblemSpec = MutableStateFlow<OptimizationProblemSpec?>(null)
    /** Optimization problem definition (consolidated view).  **Derived**
     *  — recomputed from the pieces on every mutator and on TOML load.
     *  `null` until the Problem step has at least an objective + ≥ 1
     *  decision variable; otherwise carries the full validated spec.
     *  Step.PROBLEM completion gate. */
    val problemSpec: StateFlow<OptimizationProblemSpec?> = myProblemSpec.asStateFlow()

    // ── Solver-spec pieces ─────────────────────────────────────────────────
    //
    // SolverSpec is a sealed type with four variants (SHC / SA / CE /
    // RSpline) sharing a common header (maxIterations, streamNum,
    // randomRestart, etc.) plus algorithm-specific fields.  The
    // controller decomposes the spec into a selector ([algorithmKind])
    // plus per-piece StateFlows so the GUI can edit fields
    // incrementally and so switching algorithms preserves the
    // sibling-algorithm's state.
    //
    // [solverSpec] is published as a derived view built from the
    // pieces by [recomputeSolverSpec].  All algorithm-specific fields
    // carry concrete substrate-aligned defaults so the user always
    // sees a populated form when they pick an algorithm.

    private val myAlgorithmKind = MutableStateFlow<AlgorithmKind?>(null)
    /** Which algorithm is in effect.  `null` until the user commits
     *  one (programmatically via [setAlgorithmKind] or via TOML load). */
    val algorithmKind: StateFlow<AlgorithmKind?> = myAlgorithmKind.asStateFlow()

    private val myCommonMaxIterations = MutableStateFlow(100)
    /** Shared across all algorithms.  Default 100. */
    val commonMaxIterations: StateFlow<Int> = myCommonMaxIterations.asStateFlow()

    private val myCommonStreamNum = MutableStateFlow(0)
    /** Shared across all algorithms.  0 = "next available stream". */
    val commonStreamNum: StateFlow<Int> = myCommonStreamNum.asStateFlow()

    private val myCommonSolverName = MutableStateFlow<String?>(null)
    /** Shared across all algorithms.  Optional human-readable name. */
    val commonSolverName: StateFlow<String?> = myCommonSolverName.asStateFlow()

    private val myCommonStartingPoint = MutableStateFlow<Map<String, Double>?>(null)
    /** Optional decision-variable-keyed starting point.  Not GUI-editable
     *  in Phase O6 (deferred to a later polish phase); round-trips
     *  through TOML untouched.  `null` = "use solver default". */
    val commonStartingPoint: StateFlow<Map<String, Double>?> = myCommonStartingPoint.asStateFlow()

    private val myCommonReplicationsPerEvaluation = MutableStateFlow(30)
    /** Used by SHC / SA / CE.  RSpline drives replications through a
     *  growth schedule and ignores this field.  Default 30 — a
     *  sensible standalone value the user can adjust. */
    val commonReplicationsPerEvaluation: StateFlow<Int> = myCommonReplicationsPerEvaluation.asStateFlow()

    // SA-specific pieces
    private val mySaTemperature = MutableStateFlow<TemperatureSpec>(TemperatureSpec.AutoCalibrate())
    val saTemperature: StateFlow<TemperatureSpec> = mySaTemperature.asStateFlow()

    private val mySaCoolingSchedule = MutableStateFlow<CoolingScheduleSpec>(
        CoolingScheduleSpec.Exponential(initialTemperature = 100.0)
    )
    val saCoolingSchedule: StateFlow<CoolingScheduleSpec> = mySaCoolingSchedule.asStateFlow()

    private val mySaStoppingTemperature = MutableStateFlow(0.001)
    val saStoppingTemperature: StateFlow<Double> = mySaStoppingTemperature.asStateFlow()

    // CE-specific pieces
    private val myCeSampler = MutableStateFlow<CESamplerSpec>(CESamplerSpec.Normal())
    val ceSampler: StateFlow<CESamplerSpec> = myCeSampler.asStateFlow()

    private val myCeElitePct = MutableStateFlow(0.1)
    /** Substrate `CrossEntropySolver.defaultElitePct` is 0.1; we seed
     *  the same value as the GUI default so the user sees an explicit
     *  number and commits an explicit number in the document. */
    val ceElitePct: StateFlow<Double> = myCeElitePct.asStateFlow()

    private val myCeSampleSize = MutableStateFlow(50)
    /** Substrate's `recommendCESampleSize()` formula yields ~35 at
     *  the default elitePct + half-width; we seed a round 50 as the
     *  GUI default. */
    val ceSampleSize: StateFlow<Int> = myCeSampleSize.asStateFlow()

    // RSpline-specific pieces
    private val myRsplineInitialNumReps = MutableStateFlow(2)
    val rsplineInitialNumReps: StateFlow<Int> = myRsplineInitialNumReps.asStateFlow()

    private val myRsplineGrowthRate = MutableStateFlow(1.5)
    val rsplineGrowthRate: StateFlow<Double> = myRsplineGrowthRate.asStateFlow()

    private val myRsplineMaxNumReplications = MutableStateFlow(30)
    val rsplineMaxNumReplications: StateFlow<Int> = myRsplineMaxNumReplications.asStateFlow()

    // Random-restart wrapper (lives on every variant)
    private val myRandomRestart = MutableStateFlow<RandomRestartSpec?>(null)
    /** Optional random-restart wrapper.  `null` = no restart. */
    val randomRestart: StateFlow<RandomRestartSpec?> = myRandomRestart.asStateFlow()

    private val mySolverSpec = MutableStateFlow<SolverSpec?>(null)
    /** Algorithm choice + parameters (consolidated view).  **Derived**
     *  — recomputed from the pieces on every mutator and on TOML load.
     *  `null` when [algorithmKind] is null.  Step.ALGORITHM completion
     *  gate. */
    val solverSpec: StateFlow<SolverSpec?> = mySolverSpec.asStateFlow()

    private val myEvaluationSpec = MutableStateFlow(EvaluationSpec())
    /** Cross-cutting evaluator/solver settings.  Always non-null
     *  (defaults are well-defined). */
    val evaluationSpec: StateFlow<EvaluationSpec> = myEvaluationSpec.asStateFlow()

    private val myTrackingSpec = MutableStateFlow(SolverTrackingSpec())
    /** Optional CSV / console trace settings.  Always non-null
     *  (defaults are disabled).  Treated as a *preference* — edits
     *  mark dirty but do NOT drop [lastResult]. */
    val trackingSpec: StateFlow<SolverTrackingSpec> = myTrackingSpec.asStateFlow()

    // ── Bundle library ─────────────────────────────────────────────────────

    /**
     *  Substrate-owned bundle-library bookkeeping.  Composed
     *  (E.5.8); this controller delegates [loadedBundles],
     *  [bundleProvider], [loadBundleJar], and bundle-ID lookups to
     *  this object.  The [onBundlesChanged] callback wires the
     *  Simopt-specific [refreshModelDescriptor] fan-out so a
     *  previously-unresolvable [ModelReference.ByBundleAndModelId]
     *  re-resolves the moment its bundle arrives.
     */
    private val bundleLibrary = BundleLibraryController(
        onBundlesChanged = ::refreshModelDescriptor
    )

    /** Bundles available for model selection.  Auto-populated from the
     *  classpath at construction time; grown by [loadBundleJar]. */
    val loadedBundles: StateFlow<List<LoadedBundle>> = bundleLibrary.loadedBundles

    /** `BundleModelProvider` over the current [loadedBundles].  `null`
     *  when no bundles are loaded. */
    val bundleProvider: StateFlow<BundleModelProvider?> = bundleLibrary.bundleProvider

    private val myCurrentModelDescriptor = MutableStateFlow<ModelDescriptor?>(null)
    /**
     *  Descriptor (controls, RV parameters, response names, run
     *  defaults) for the currently-selected model.  Populated when
     *  [modelTemplate] is a [ModelReference.ByBundleAndModelId] whose
     *  bundle is present in [loadedBundles].  `null` for non-bundle
     *  refs (`ByProviderId` / `Embedded` / `ByJar` — the controller
     *  doesn't carry their introspection paths) and for refs whose
     *  bundle isn't loaded yet.
     *
     *  Phase O4 reads this to populate the decision-variable picker;
     *  Phase O5 reads `responseNames`; Phase O7a reads
     *  `experimentRunDefaults` to display "model default: …" labels
     *  on the Run Setup overrides panel.
     */
    val currentModelDescriptor: StateFlow<ModelDescriptor?> = myCurrentModelDescriptor.asStateFlow()

    // ── Stepper state ──────────────────────────────────────────────────────

    private val myActiveStep = MutableStateFlow(Step.initial)
    /** The step whose body is currently visible in the frame. */
    val activeStep: StateFlow<Step> = myActiveStep.asStateFlow()

    private val myStepCompletion = MutableStateFlow(initialStepCompletion())
    /** Per-step completion map.  A step is complete when its
     *  required state is present (see [refreshStepCompletion]).  A
     *  step is *unlocked* when every prior step is complete; the
     *  stepper widget derives unlock state from this map via
     *  [canAdvanceTo]. */
    val stepCompletion: StateFlow<Map<Step, Boolean>> = myStepCompletion.asStateFlow()

    // ── Document lifecycle ─────────────────────────────────────────────────

    /**
     *  Substrate-owned file + dirty bookkeeping.  Composed (E.5.4);
     *  this controller delegates [currentFile], [isDirty],
     *  [markSaved], and the file/dirty mutations inside
     *  [newDocument] / [loadConfiguration] (plus the shared first
     *  line of [markDirtyStructural] and [markDirtyPreference]) to
     *  this object.  The Simopt-specific [editedSinceLastRun]
     *  cross-flow, the structural-vs-preference fan-out (last-result
     *  clearing, validation refreshers, model-aware-stale flag), and
     *  the [markSaved] analysis-name-derivation block stay on the
     *  controller.
     */
    private val documentLifecycle = DocumentLifecycleController()

    /** Absolute path of the currently-loaded TOML file, or `null`
     *  for an unsaved document. */
    val currentFile: StateFlow<Path?> = documentLifecycle.currentFile

    /** `true` when in-memory state differs from the on-disk file
     *  (or when there is no file yet and the document is non-empty). */
    val isDirty: StateFlow<Boolean> = documentLifecycle.isDirty

    /**
     *  Substrate-owned run-lifecycle bookkeeping.  Composed
     *  (E.5.6); this controller delegates [lastResult] and
     *  [editedSinceLastRun] to this object.  Simopt-specific
     *  cross-flows on each edit — [modelAwareStale], the structural-
     *  vs-preference refresher fan-out (validation, step completion),
     *  and the [latestIteration] / [activeSolver] flows around the
     *  run lifecycle — stay on the controller.
     */
    private val runLifecycle = RunLifecycleController<RunResult.OptimizationCompleted>()

    /** `true` when the document has been edited since the last
     *  successful run.  Drives the stale-results banner on the
     *  Execute / Results steps. */
    val editedSinceLastRun: StateFlow<Boolean> = runLifecycle.editedSinceLastRun

    // ── Runtime ────────────────────────────────────────────────────────────

    private val myRunning = MutableStateFlow(false)
    /** `true` while an optimization is in flight. */
    val runningFlow: StateFlow<Boolean> = myRunning.asStateFlow()

    private val myEventFlow = MutableSharedFlow<RunEvent>(
        replay = 0,
        extraBufferCapacity = 256
    )
    /** Live stream of run events forwarded from the active
     *  `KSLAppSession` submission.  Phase O7b wires the source;
     *  Phase O2 leaves the flow empty. */
    val eventFlow: SharedFlow<RunEvent> = myEventFlow.asSharedFlow()

    /** Result of the most recent successful run, or `null` if no
     *  run has completed or the result was cleared by an R1
     *  structural edit. */
    val lastResult: StateFlow<RunResult.OptimizationCompleted?> = runLifecycle.lastResult

    private val myLatestIteration = MutableStateFlow<RunEvent.IterationCompleted?>(null)
    /**
     *  Most recent [RunEvent.IterationCompleted] from the in-flight
     *  run.  Phase O7b's live-progress panels read iteration count,
     *  best inputs, best estimated objective, and solver-specific
     *  state from this single event — the full history is returned in
     *  [lastResult] when the run terminates, so we don't accumulate
     *  it live.
     *
     *  Reset to `null` on each new [submit] and on document load /
     *  reset.
     */
    val latestIteration: StateFlow<RunEvent.IterationCompleted?> =
        myLatestIteration.asStateFlow()

    private val myActiveSolver = MutableStateFlow<Solver?>(null)
    /**
     *  Reference to the [ksl.simopt.solvers.Solver] currently
     *  executing (or last built; cleared when the run terminates).
     *  Lets the Execute step query solver-side state that isn't
     *  surfaced through [RunEvent] (e.g. `maximumNumberIterations`).
     *  `null` whenever no run is in flight.
     */
    val activeSolver: StateFlow<Solver?> = myActiveSolver.asStateFlow()

    // ── Validation (hoisted from PreRunValidationPanel) ───────────────────
    //
    // Document validation recomputes synchronously on every document
    // edit; model-aware validation is gated behind an explicit
    // [runModelAwareValidationNow] call (it builds a probe model).
    // Hoisting these flows lets the Execute step's Run button gate
    // on the same source of truth that the validation panel renders.

    private val myDocumentValidation = MutableStateFlow(ValidationResult())
    /**
     *  Live document-only validation result.  Recomputed on every
     *  document edit via [refreshDocumentValidation].  When
     *  [currentConfiguration] is `null` (no model selected), this
     *  flow carries a single `MISSING_MODEL` error.
     */
    val documentValidation: StateFlow<ValidationResult> =
        myDocumentValidation.asStateFlow()

    private val myModelAwareValidation = MutableStateFlow<ValidationResult?>(null)
    /**
     *  Cached model-aware validation result.  `null` until
     *  [runModelAwareValidationNow] is called; reset to its previous
     *  value with [modelAwareStale] = `true` on every document edit.
     */
    val modelAwareValidation: StateFlow<ValidationResult?> =
        myModelAwareValidation.asStateFlow()

    private val myModelAwareStale = MutableStateFlow(true)
    /**
     *  `true` whenever the document has been edited since the last
     *  [runModelAwareValidationNow].  Drives the "model-aware result
     *  is stale" banner on the Execute step's validation surface.
     */
    val modelAwareStale: StateFlow<Boolean> = myModelAwareStale.asStateFlow()

    private val myRunOutputDir = MutableStateFlow(initialRunOutputDir())
    /**
     *  Resolved filesystem path the **next** [submit] will write into.
     *
     *  Default: `<appWorkspace>/output/<analysisName>/run-NNN/`, where
     *  `NNN` is the next unused three-digit number under the analysis
     *  directory (see [OptimizationPaths.nextRunSubdir]).  This means
     *  consecutive runs land in distinct folders by default — a fresh
     *  SHC run followed by a fresh SA run end up in `run-001/` and
     *  `run-002/` respectively, never overwriting each other.
     *
     *  Mutable via [setRunOutputDir].  After a successful [submit]
     *  the value is recomputed to the next auto-numbered slot — so
     *  any one-shot override consumed by that run is replaced.
     *
     *  Recomputed when the analysis name changes (output config edit)
     *  so the path always reflects the live document.
     */
    val runOutputDir: StateFlow<Path> = myRunOutputDir.asStateFlow()

    private val myLastCompletedRunDir = MutableStateFlow<Path?>(null)
    /**
     *  Directory where the most recent run's artifacts were
     *  written.  Populated when [submit] resolves to any terminal
     *  state (completed, cancelled, or failed) — the
     *  [ResultsArtifactWriter] writes at least a summary TOML for
     *  every terminal state.  `null` until the first terminal
     *  resolution.
     *
     *  Drives the Results step's artifact-list "Open" buttons,
     *  which need to point at the run directory that just produced
     *  artifacts — not at the auto-advanced *next* run directory
     *  exposed by [runOutputDir].
     */
    val lastCompletedRunDir: StateFlow<Path?> = myLastCompletedRunDir.asStateFlow()

    /** Held privately so [submit] and [cancel] (and [close]) can see
     *  the in-flight handle.  `null` whenever no run is active. */
    private var currentRunHandle: RunHandle? = null

    /** Event-collection Job; cancelled in the terminal branch so the
     *  collector doesn't outlive the run. */
    private var currentRunJob: Job? = null

    /** Snapshot of the in-flight run's metadata captured at submit
     *  time so the terminal observer can write a useful partial
     *  summary even when the run cancels / fails before producing
     *  a completed result.  `null` whenever no run is active. */
    private var currentRunSnapshot: RunStartSnapshot? = null

    private data class RunStartSnapshot(
        val runId: String,
        val startTimeIso: String,
        val config: OptimizationRunConfiguration,
        val runDir: Path
    )

    init {
        // Auto-discover classpath bundles so a packaged app shows
        // available models immediately.  Mirrors Experiment / Scenario
        // controllers.
        bundleLibrary.discoverFromClasspath()
        // Seed validation so the Execute step sees a populated flow
        // even before the first user edit.
        refreshDocumentValidation()
    }

    // ── Bundle management ──────────────────────────────────────────────────

    /**
     *  Resolve [modelTemplate]'s reference against the loaded bundles
     *  and publish the descriptor.  Sets [currentModelDescriptor] to
     *  `null` when:
     *  - the template is `null` (no model picked yet),
     *  - the ref is a non-bundle variant (`ByProviderId` / `Embedded`
     *    / `ByJar`) — no introspection path from this controller,
     *  - the ref is `ByBundleAndModelId` but the bundle isn't loaded
     *    or the descriptor lookup throws.
     */
    private fun refreshModelDescriptor() {
        val ref = myModelTemplate.value?.modelReference as? ModelReference.ByBundleAndModelId
        val descriptor: ModelDescriptor? = if (ref == null) null else {
            val bundle = bundleLibrary.findBundle(ref.bundleId)
            try {
                bundle?.descriptorFor(ref.modelId)
            } catch (_: Throwable) {
                null
            }
        }
        if (myCurrentModelDescriptor.value != descriptor) {
            myCurrentModelDescriptor.value = descriptor
            // The descriptor is the preferred source of
            // `modelIdentifier` for the problem spec; when it
            // changes (e.g. bundle just loaded), re-emit the
            // consolidated problem spec so the identifier reflects
            // what the runtime will actually build.
            recomputeProblemSpec()
        }
    }

    /**
     *  Load every `KSLModelBundle` from the JAR at [jarPath] and
     *  append the discovered bundles to [loadedBundles].  Duplicates
     *  (bundleIds already present) are silently discarded.  Same
     *  shape as Experiment / Scenario controllers' loaders.
     */
    fun loadBundleJar(jarPath: Path): BundleLibraryController.LoadBundleResult =
        bundleLibrary.loadJar(jarPath)

    // ── R1 lifecycle helpers ───────────────────────────────────────────────

    /** Mark the document dirty AND stale.  Called from every
     *  structural mutator.  Also drops [lastResult] since the
     *  document no longer matches what produced it. */
    private fun markDirtyStructural() {
        documentLifecycle.markDirty()
        runLifecycle.markEdited()
        runLifecycle.setLastResult(null)
        refreshStepCompletion()
        refreshDocumentValidation()
        if (!myModelAwareStale.value) myModelAwareStale.value = true
    }

    /** Mark the document dirty only — used for preferences (output,
     *  evaluation, tracking) that don't invalidate a prior run. */
    private fun markDirtyPreference() {
        documentLifecycle.markDirty()
        refreshDocumentValidation()
        if (!myModelAwareStale.value) myModelAwareStale.value = true
    }

    // ── Mutators ───────────────────────────────────────────────────────────

    /** Replace the document-wide output settings.  Preference — does
     *  not drop [lastResult]. */
    fun setOutput(spec: OptimizationOutputConfig) {
        if (myOutput.value == spec) return
        val analysisNameChanged = myOutput.value.analysisName != spec.analysisName
        myOutput.value = spec
        markDirtyPreference()
        // A different analysis name implies a different analysis
        // directory — recompute the next-run path so the GUI shows
        // the correct destination.
        if (analysisNameChanged) refreshAutoRunOutputDir()
    }

    /** Convenience setter for the toolbar's analysis-name field. */
    fun setAnalysisName(name: String) {
        setOutput(myOutput.value.copy(analysisName = name))
    }

    /** Replace the baseline model-construction template.  Structural
     *  — drops [lastResult].  Passing `null` clears the model and
     *  cascades through downstream specs (`problemSpec` / `solverSpec`
     *  become moot but are NOT auto-cleared; the user must clear
     *  them explicitly via [resetConfiguration] or the Model step's
     *  switch-and-clear prompt).  Also refreshes
     *  [currentModelDescriptor] against [loadedBundles]. */
    fun setModelTemplate(template: ModelRunTemplate?) {
        if (myModelTemplate.value == template) return
        myModelTemplate.value = template
        refreshModelDescriptor()
        markDirtyStructural()
    }

    /**
     *  Convenience over [setModelTemplate] when the user picks a
     *  model from the GUI dropdowns.  Builds a fresh
     *  [ModelRunTemplate] using the descriptor's
     *  [ksl.controls.experiments.ExperimentRunDefaults] as the
     *  starting [ExperimentRunParameters].  When [ref] points at an
     *  unloaded bundle (or a non-bundle reference), the template is
     *  still installed but [currentModelDescriptor] stays `null` and
     *  the Model-step picker switches to its "unresolved" card.
     */
    fun setModelReference(ref: ModelReference) {
        val template = buildTemplateFor(ref)
        if (myModelTemplate.value == template) return
        myModelTemplate.value = template
        refreshModelDescriptor()
        // Model identifier on the problem spec is derived from the
        // live model reference, so any change here must re-emit
        // problemSpec with a fresh identifier.
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /**
     *  Switch the active model AND clear all model-dependent
     *  document state (problem, solver, evaluation, tracking) — used
     *  by the GUI when the user confirms a model switch that would
     *  leave behind stale references to the prior model's controls /
     *  responses.  The output config + analysis name survive (they
     *  aren't model-specific).
     */
    fun setModelReferenceAndClear(ref: ModelReference) {
        val template = buildTemplateFor(ref)
        myModelTemplate.value = template
        setProblemSpec(null)             // fan-out clear of every problem-piece
        setSolverSpec(null)              // fan-out clear of every solver-piece
        myEvaluationSpec.value = EvaluationSpec()
        myTrackingSpec.value = SolverTrackingSpec()
        refreshModelDescriptor()
        markDirtyStructural()
    }

    private fun buildTemplateFor(ref: ModelReference): ModelRunTemplate {
        val descriptor: ModelDescriptor? = (ref as? ModelReference.ByBundleAndModelId)?.let { byRef ->
            val bundle = bundleLibrary.findBundle(byRef.bundleId)
            try { bundle?.descriptorFor(byRef.modelId) } catch (_: Throwable) { null }
        }
        val runParameters = runParametersFor(descriptor, modelName = descriptorModelName(descriptor, ref))
        val controls = ModelControlsExport(modelName = descriptorModelName(descriptor, ref))
        return ModelRunTemplate(
            modelReference = ref,
            modelConfiguration = null,
            runParameters = runParameters,
            controls = controls,
            rvOverrides = emptyList()
        )
    }

    private fun descriptorModelName(descriptor: ModelDescriptor?, ref: ModelReference): String =
        descriptor?.modelName ?: when (ref) {
            is ModelReference.ByBundleAndModelId -> ref.modelId
            is ModelReference.ByProviderId -> ref.providerId
            is ModelReference.Embedded -> ref.modelName
            is ModelReference.ByJar -> ref.builderClassName ?: "Model"
        }

    private fun runParametersFor(
        descriptor: ModelDescriptor?,
        modelName: String
    ): ExperimentRunParameters {
        val defaults = descriptor?.experimentRunDefaults
        return ExperimentRunParameters(
            experimentName = modelName,
            experimentId = 1,
            numberOfReplications = defaults?.numberOfReplications ?: 1,
            numChunks = defaults?.numChunks ?: 1,
            runName = modelName,
            startingRepId = defaults?.startingRepId ?: 1,
            lengthOfReplication = defaults?.lengthOfReplication ?: Double.POSITIVE_INFINITY,
            lengthOfReplicationWarmUp = defaults?.lengthOfReplicationWarmUp ?: 0.0,
            replicationInitializationOption = defaults?.replicationInitializationOption ?: true,
            maximumAllowedExecutionTimePerReplication =
                defaults?.maximumAllowedExecutionTimePerReplication ?: 0.minutes,
            resetStartStreamOption = defaults?.resetStartStreamOption ?: false,
            advanceNextSubStreamOption = defaults?.advanceNextSubStreamOption ?: true,
            antitheticOption = defaults?.antitheticOption ?: false,
            numberOfStreamAdvancesPriorToRunning = defaults?.numberOfStreamAdvancesPriorToRunning ?: 0,
            garbageCollectAfterReplicationFlag = defaults?.garbageCollectAfterReplicationFlag ?: false
        )
    }

    /** Update the baseline replication length on the current model
     *  template.  No-op when no model is set.  Structural — drops
     *  [lastResult].  The solver-step `replicationsPerEvaluation`
     *  is independent of this value; this field controls the
     *  baseline run-parameter setting saved on
     *  `ModelRunTemplate.runParameters`. */
    fun setLengthOfReplication(value: Double) {
        require(value > 0.0 && value.isFinite()) {
            "lengthOfReplication must be > 0 and finite; was $value"
        }
        val template = myModelTemplate.value ?: return
        if (template.runParameters.lengthOfReplication == value) return
        val updated = template.copy(
            runParameters = template.runParameters.copy(lengthOfReplication = value)
        )
        myModelTemplate.value = updated
        markDirtyStructural()
    }

    /** Update the baseline warm-up length.  No-op when no model is
     *  set.  Structural — drops [lastResult]. */
    fun setLengthOfReplicationWarmUp(value: Double) {
        require(value >= 0.0 && value.isFinite()) {
            "lengthOfReplicationWarmUp must be >= 0 and finite; was $value"
        }
        val template = myModelTemplate.value ?: return
        if (template.runParameters.lengthOfReplicationWarmUp == value) return
        val updated = template.copy(
            runParameters = template.runParameters.copy(lengthOfReplicationWarmUp = value)
        )
        myModelTemplate.value = updated
        markDirtyStructural()
    }

    /** Update the baseline replication count.  No-op when no model
     *  is set.  Structural — drops [lastResult].  This value is the
     *  baseline `numberOfReplications` saved on
     *  `ModelRunTemplate.runParameters`; the algorithm step's
     *  `replicationsPerEvaluation` is independent (a future phase
     *  may default the algorithm field from this baseline when the
     *  user has not explicitly set it). */
    fun setNumberOfReplications(value: Int) {
        require(value >= 1) {
            "numberOfReplications must be >= 1; was $value"
        }
        val template = myModelTemplate.value ?: return
        if (template.runParameters.numberOfReplications == value) return
        val updated = template.copy(
            runParameters = template.runParameters.copy(numberOfReplications = value)
        )
        myModelTemplate.value = updated
        markDirtyStructural()
    }

    /** Replace the baseline controls.  No-op when no model is set.
     *  Structural — drops [lastResult]. */
    fun setBaselineControls(controls: ModelControlsExport) {
        val template = myModelTemplate.value ?: return
        if (template.controls == controls) return
        val updated = template.copy(controls = controls)
        myModelTemplate.value = updated
        markDirtyStructural()
    }

    /** Replace the baseline RV-parameter overrides.  No-op when no
     *  model is set.  Structural — drops [lastResult]. */
    fun setBaselineRvOverrides(overrides: List<RVParameterOverride>) {
        val template = myModelTemplate.value ?: return
        if (template.rvOverrides == overrides) return
        val updated = template.copy(rvOverrides = overrides)
        myModelTemplate.value = updated
        markDirtyStructural()
    }

    /** Replace the problem specification by fanning out [spec] into
     *  the per-piece StateFlows ([objectiveResponseName], [inputs],
     *  [responseNames], etc.).  Used by TOML load and by tests /
     *  programmatic callers that want to commit a complete spec in
     *  one call.  Structural — drops [lastResult].
     *
     *  Passing `null` clears every piece back to defaults (the
     *  consolidated [problemSpec] then publishes null on the next
     *  recompute). */
    fun setProblemSpec(spec: OptimizationProblemSpec?) {
        if (spec == null) {
            myObjectiveResponseName.value = null
            myOptimizationType.value = OptimizationType.MINIMIZE
            myProblemName.value = null
            myIndifferenceZoneParameter.value = 0.0
            myObjectiveGranularity.value = 0.0
            myInputs.value = emptyList()
            mySelectedInputIndex.value = -1
            myResponseNames.value = emptyList()
            myLinearConstraints.value = emptyList()
            mySelectedLinearConstraintIndex.value = -1
            myResponseConstraints.value = emptyList()
            mySelectedResponseConstraintIndex.value = -1
            myDefaultLinearPenalty.value = PenaltyFunctionSpec.DynamicPolynomial()
            myDefaultResponsePenalty.value = PenaltyFunctionSpec.WithMemory()
        } else {
            myObjectiveResponseName.value = spec.objectiveResponseName
            myOptimizationType.value = spec.optimizationType
            myProblemName.value = spec.problemName
            myIndifferenceZoneParameter.value = spec.indifferenceZoneParameter
            myObjectiveGranularity.value = spec.objectiveGranularity
            myInputs.value = spec.inputs
            mySelectedInputIndex.value = if (spec.inputs.isEmpty()) -1 else 0
            myResponseNames.value = spec.responseNames
            myLinearConstraints.value = spec.linearConstraints
            myResponseConstraints.value = spec.responseConstraints
            myDefaultLinearPenalty.value = spec.defaultLinearPenalty
            myDefaultResponsePenalty.value = spec.defaultResponsePenalty
        }
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Set the objective response name.  Pass `null` to clear it.
     *  Structural — drops [lastResult]. */
    fun setObjectiveResponseName(name: String?) {
        val coerced = name?.takeIf { it.isNotBlank() }
        if (myObjectiveResponseName.value == coerced) return
        myObjectiveResponseName.value = coerced
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Set the optimization direction. */
    fun setOptimizationType(type: OptimizationType) {
        if (myOptimizationType.value == type) return
        myOptimizationType.value = type
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Set the optional problem name.  Pass `null` or a blank string
     *  to clear it. */
    fun setProblemName(name: String?) {
        val coerced = name?.takeIf { it.isNotBlank() }
        if (myProblemName.value == coerced) return
        myProblemName.value = coerced
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Set the indifference-zone parameter (Δ).  Must be >= 0 and
     *  finite. */
    fun setIndifferenceZoneParameter(value: Double) {
        require(value >= 0.0 && value.isFinite()) {
            "indifferenceZoneParameter must be >= 0 and finite; was $value"
        }
        if (myIndifferenceZoneParameter.value == value) return
        myIndifferenceZoneParameter.value = value
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Set the objective-function granularity.  Must be >= 0 and
     *  finite. */
    fun setObjectiveGranularity(value: Double) {
        require(value >= 0.0 && value.isFinite()) {
            "objectiveGranularity must be >= 0 and finite; was $value"
        }
        if (myObjectiveGranularity.value == value) return
        myObjectiveGranularity.value = value
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Append a new decision variable.  Rejects duplicate names with
     *  [IllegalArgumentException].  Selects the new row. */
    fun addInput(spec: OptimizationInputSpec) {
        require(myInputs.value.none { it.name == spec.name }) {
            "Decision-variable name '${spec.name}' already exists in the document"
        }
        val updated = myInputs.value + spec
        myInputs.value = updated
        mySelectedInputIndex.value = updated.lastIndex
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Replace the decision variable at [index].  Rejects an index
     *  out of range and rejects a name collision with any *other*
     *  existing input. */
    fun updateInput(index: Int, updated: OptimizationInputSpec) {
        val list = myInputs.value
        require(index in list.indices) {
            "updateInput: index $index out of range 0..${list.lastIndex}"
        }
        require(list.withIndex().none { (i, x) -> i != index && x.name == updated.name }) {
            "Decision-variable name '${updated.name}' already exists in the document"
        }
        val newList = list.toMutableList().also { it[index] = updated }
        myInputs.value = newList
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Delete the decision variable at [index].  Shifts
     *  [selectedInputIndex] to keep the selection sane. */
    fun deleteInput(index: Int) {
        val list = myInputs.value
        require(index in list.indices) {
            "deleteInput: index $index out of range 0..${list.lastIndex}"
        }
        val newList = list.toMutableList().also { it.removeAt(index) }
        myInputs.value = newList
        // Selection: prefer to keep the same row if possible; otherwise
        // clamp downward; -1 when the list is now empty.
        val selected = mySelectedInputIndex.value
        mySelectedInputIndex.value = when {
            newList.isEmpty() -> -1
            selected < index -> selected
            selected == index -> (index - 1).coerceAtLeast(0)
            else -> selected - 1
        }
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Move the decision variable at [index] one slot earlier.
     *  No-op when [index] is 0. */
    fun moveInputUp(index: Int) {
        val list = myInputs.value
        require(index in list.indices) {
            "moveInputUp: index $index out of range 0..${list.lastIndex}"
        }
        if (index == 0) return
        val newList = list.toMutableList()
        val tmp = newList[index - 1]
        newList[index - 1] = newList[index]
        newList[index] = tmp
        myInputs.value = newList
        if (mySelectedInputIndex.value == index) mySelectedInputIndex.value = index - 1
        else if (mySelectedInputIndex.value == index - 1) mySelectedInputIndex.value = index
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Move the decision variable at [index] one slot later.
     *  No-op when [index] is the last index. */
    fun moveInputDown(index: Int) {
        val list = myInputs.value
        require(index in list.indices) {
            "moveInputDown: index $index out of range 0..${list.lastIndex}"
        }
        if (index == list.lastIndex) return
        val newList = list.toMutableList()
        val tmp = newList[index + 1]
        newList[index + 1] = newList[index]
        newList[index] = tmp
        myInputs.value = newList
        if (mySelectedInputIndex.value == index) mySelectedInputIndex.value = index + 1
        else if (mySelectedInputIndex.value == index + 1) mySelectedInputIndex.value = index
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Set the [selectedInputIndex].  Caller passes -1 to clear the
     *  selection. */
    fun setSelectedInputIndex(index: Int) {
        val list = myInputs.value
        val clamped = when {
            index < 0 -> -1
            index >= list.size -> list.lastIndex
            else -> index
        }
        if (mySelectedInputIndex.value != clamped) mySelectedInputIndex.value = clamped
    }

    /** Replace the declared response-names list.  Structural — drops
     *  [lastResult]. */
    fun setResponseNames(names: List<String>) {
        if (myResponseNames.value == names) return
        myResponseNames.value = names
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Idempotently add [name] to the declared response-names list.
     *  Rejects blank with [IllegalArgumentException]; no-op when [name]
     *  is already declared. */
    fun addResponseName(name: String) {
        require(name.isNotBlank()) { "Response name must be non-blank" }
        if (name in myResponseNames.value) return
        myResponseNames.value = myResponseNames.value + name
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Remove [name] from the declared response-names list.  No-op
     *  when [name] is not present. */
    fun removeResponseName(name: String) {
        if (name !in myResponseNames.value) return
        myResponseNames.value = myResponseNames.value.filterNot { it == name }
        recomputeProblemSpec()
        markDirtyStructural()
    }

    // ── Linear constraints ────────────────────────────────────────────────

    /** Append a new linear constraint.  Selects the new row. */
    fun addLinearConstraint(spec: LinearConstraintSpec) {
        val updated = myLinearConstraints.value + spec
        myLinearConstraints.value = updated
        mySelectedLinearConstraintIndex.value = updated.lastIndex
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Replace the linear constraint at [index].  Rejects an index
     *  out of range. */
    fun updateLinearConstraint(index: Int, updated: LinearConstraintSpec) {
        val list = myLinearConstraints.value
        require(index in list.indices) {
            "updateLinearConstraint: index $index out of range 0..${list.lastIndex}"
        }
        val newList = list.toMutableList().also { it[index] = updated }
        myLinearConstraints.value = newList
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Delete the linear constraint at [index].  Shifts
     *  [selectedLinearConstraintIndex] to keep the selection sane. */
    fun deleteLinearConstraint(index: Int) {
        val list = myLinearConstraints.value
        require(index in list.indices) {
            "deleteLinearConstraint: index $index out of range 0..${list.lastIndex}"
        }
        val newList = list.toMutableList().also { it.removeAt(index) }
        myLinearConstraints.value = newList
        val selected = mySelectedLinearConstraintIndex.value
        mySelectedLinearConstraintIndex.value = when {
            newList.isEmpty() -> -1
            selected < index -> selected
            selected == index -> (index - 1).coerceAtLeast(0)
            else -> selected - 1
        }
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Move the linear constraint at [index] one slot earlier.  No-op
     *  at index 0. */
    fun moveLinearConstraintUp(index: Int) {
        val list = myLinearConstraints.value
        require(index in list.indices) {
            "moveLinearConstraintUp: index $index out of range 0..${list.lastIndex}"
        }
        if (index == 0) return
        val newList = list.toMutableList()
        val tmp = newList[index - 1]; newList[index - 1] = newList[index]; newList[index] = tmp
        myLinearConstraints.value = newList
        if (mySelectedLinearConstraintIndex.value == index) mySelectedLinearConstraintIndex.value = index - 1
        else if (mySelectedLinearConstraintIndex.value == index - 1) mySelectedLinearConstraintIndex.value = index
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Move the linear constraint at [index] one slot later.  No-op
     *  at the last index. */
    fun moveLinearConstraintDown(index: Int) {
        val list = myLinearConstraints.value
        require(index in list.indices) {
            "moveLinearConstraintDown: index $index out of range 0..${list.lastIndex}"
        }
        if (index == list.lastIndex) return
        val newList = list.toMutableList()
        val tmp = newList[index + 1]; newList[index + 1] = newList[index]; newList[index] = tmp
        myLinearConstraints.value = newList
        if (mySelectedLinearConstraintIndex.value == index) mySelectedLinearConstraintIndex.value = index + 1
        else if (mySelectedLinearConstraintIndex.value == index + 1) mySelectedLinearConstraintIndex.value = index
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Set the linear-constraint selection. */
    fun setSelectedLinearConstraintIndex(index: Int) {
        val list = myLinearConstraints.value
        val clamped = when {
            index < 0 -> -1
            index >= list.size -> list.lastIndex
            else -> index
        }
        if (mySelectedLinearConstraintIndex.value != clamped) mySelectedLinearConstraintIndex.value = clamped
    }

    // ── Response constraints ──────────────────────────────────────────────

    /** Append a new response constraint.  If [spec]'s response name
     *  is not yet in [responseNames], it is auto-declared (idempotent)
     *  so that the consolidated [problemSpec] passes the substrate's
     *  init check.  Selects the new row. */
    fun addResponseConstraint(spec: ResponseConstraintSpec) {
        if (spec.name !in myResponseNames.value) {
            myResponseNames.value = myResponseNames.value + spec.name
        }
        val updated = myResponseConstraints.value + spec
        myResponseConstraints.value = updated
        mySelectedResponseConstraintIndex.value = updated.lastIndex
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Replace the response constraint at [index].  Rejects an index
     *  out of range.  Auto-declares the new name when needed. */
    fun updateResponseConstraint(index: Int, updated: ResponseConstraintSpec) {
        val list = myResponseConstraints.value
        require(index in list.indices) {
            "updateResponseConstraint: index $index out of range 0..${list.lastIndex}"
        }
        if (updated.name !in myResponseNames.value) {
            myResponseNames.value = myResponseNames.value + updated.name
        }
        val newList = list.toMutableList().also { it[index] = updated }
        myResponseConstraints.value = newList
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Delete the response constraint at [index]. */
    fun deleteResponseConstraint(index: Int) {
        val list = myResponseConstraints.value
        require(index in list.indices) {
            "deleteResponseConstraint: index $index out of range 0..${list.lastIndex}"
        }
        val newList = list.toMutableList().also { it.removeAt(index) }
        myResponseConstraints.value = newList
        val selected = mySelectedResponseConstraintIndex.value
        mySelectedResponseConstraintIndex.value = when {
            newList.isEmpty() -> -1
            selected < index -> selected
            selected == index -> (index - 1).coerceAtLeast(0)
            else -> selected - 1
        }
        recomputeProblemSpec()
        markDirtyStructural()
    }

    fun moveResponseConstraintUp(index: Int) {
        val list = myResponseConstraints.value
        require(index in list.indices) {
            "moveResponseConstraintUp: index $index out of range 0..${list.lastIndex}"
        }
        if (index == 0) return
        val newList = list.toMutableList()
        val tmp = newList[index - 1]; newList[index - 1] = newList[index]; newList[index] = tmp
        myResponseConstraints.value = newList
        if (mySelectedResponseConstraintIndex.value == index) mySelectedResponseConstraintIndex.value = index - 1
        else if (mySelectedResponseConstraintIndex.value == index - 1) mySelectedResponseConstraintIndex.value = index
        recomputeProblemSpec()
        markDirtyStructural()
    }

    fun moveResponseConstraintDown(index: Int) {
        val list = myResponseConstraints.value
        require(index in list.indices) {
            "moveResponseConstraintDown: index $index out of range 0..${list.lastIndex}"
        }
        if (index == list.lastIndex) return
        val newList = list.toMutableList()
        val tmp = newList[index + 1]; newList[index + 1] = newList[index]; newList[index] = tmp
        myResponseConstraints.value = newList
        if (mySelectedResponseConstraintIndex.value == index) mySelectedResponseConstraintIndex.value = index + 1
        else if (mySelectedResponseConstraintIndex.value == index + 1) mySelectedResponseConstraintIndex.value = index
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Set the response-constraint selection. */
    fun setSelectedResponseConstraintIndex(index: Int) {
        val list = myResponseConstraints.value
        val clamped = when {
            index < 0 -> -1
            index >= list.size -> list.lastIndex
            else -> index
        }
        if (mySelectedResponseConstraintIndex.value != clamped) mySelectedResponseConstraintIndex.value = clamped
    }

    // ── Penalty defaults ──────────────────────────────────────────────────

    /** Set the problem-level default penalty function for linear
     *  constraints.  Structural — drops [lastResult]. */
    fun setDefaultLinearPenalty(spec: PenaltyFunctionSpec) {
        if (myDefaultLinearPenalty.value == spec) return
        myDefaultLinearPenalty.value = spec
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Set the problem-level default penalty function for response
     *  constraints.  Structural — drops [lastResult]. */
    fun setDefaultResponsePenalty(spec: PenaltyFunctionSpec) {
        if (myDefaultResponsePenalty.value == spec) return
        myDefaultResponsePenalty.value = spec
        recomputeProblemSpec()
        markDirtyStructural()
    }

    /** Recompute [problemSpec] from the per-piece flows.  Publishes
     *  `null` when required pieces are missing or the assembled spec
     *  fails the substrate's `init {}` invariant (defensive — the
     *  per-piece mutators above enforce most invariants directly). */
    private fun recomputeProblemSpec() {
        val obj = myObjectiveResponseName.value
        val ins = myInputs.value
        val next: OptimizationProblemSpec? = if (obj == null || ins.isEmpty()) {
            null
        } else {
            try {
                OptimizationProblemSpec(
                    // Auto-derived default when the user hasn't
                    // typed a problem name on the Problem step.
                    // Without a default, the substrate's
                    // `Identity(null)` fallback would produce an
                    // uninformative `"ID_<counter>"` name in reports
                    // and summary.toml.  See `deriveProblemName`.
                    problemName = effectiveProblemName(),
                    // Substrate runtime requires a non-blank modelIdentifier
                    // matching the built `Model.modelIdentifier` (see
                    // `SimulationProvider.simulate`).  We read it from
                    // the descriptor when available — that's the same
                    // model the runtime will build — and fall back to a
                    // reference-derived stub otherwise.
                    modelIdentifier = deriveModelIdentifier(),
                    objectiveResponseName = obj,
                    inputs = ins,
                    responseNames = myResponseNames.value,
                    optimizationType = myOptimizationType.value,
                    indifferenceZoneParameter = myIndifferenceZoneParameter.value,
                    objectiveGranularity = myObjectiveGranularity.value,
                    linearConstraints = myLinearConstraints.value,
                    responseConstraints = myResponseConstraints.value,
                    defaultLinearPenalty = myDefaultLinearPenalty.value,
                    defaultResponsePenalty = myDefaultResponsePenalty.value
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        if (myProblemSpec.value != next) myProblemSpec.value = next
        refreshStepCompletion()
    }

    /** Produce a non-blank `modelIdentifier` for the problem spec.
     *
     *  Order of preference:
     *  1. `currentModelDescriptor.value.modelIdentifier` — the
     *     identifier of the actual `Model` the runtime will build
     *     (matches what `SimulationProvider.simulate` will check
     *     against).  Used whenever the descriptor is available.
     *  2. A natural identifier derived from the model reference,
     *     used only when no descriptor has been resolved yet
     *     (descriptor lookup may fail for transient bundle-load
     *     races; document load before bundles are present; etc.).
     *
     *  Returns `null` only when no model is set.  The
     *  `OptimizationProblemSpec` init accepts a `null` field but
     *  rejects a blank string.
     */
    // Thin wrappers over the substrate naming helpers in
    // `ksl.app.optimization.naming` — bind the controller's live
    // StateFlow values to the substrate functions' explicit
    // parameters so the controller can pass its document state
    // through without rewriting the derivation logic.
    private fun deriveModelIdentifier(): String? =
        ksl.app.optimization.naming.deriveModelIdentifier(
            descriptor = myCurrentModelDescriptor.value,
            modelReference = myModelTemplate.value?.modelReference
        )

    private fun effectiveProblemName(): String =
        ksl.app.optimization.naming.deriveProblemName(
            explicitProblemName = myProblemName.value,
            descriptor = myCurrentModelDescriptor.value,
            modelReference = myModelTemplate.value?.modelReference
        )

    private fun effectiveSolverName(): String? =
        ksl.app.optimization.naming.deriveSolverName(
            explicitSolverName = myCommonSolverName.value,
            algorithmKind = myAlgorithmKind.value
        )

    /** Replace the solver specification by fanning out [spec] into
     *  the per-piece StateFlows ([algorithmKind], [commonMaxIterations],
     *  algorithm-specific flows, etc.).  Used by TOML load and by
     *  tests / programmatic callers that commit a complete spec in
     *  one call.  Structural — drops [lastResult].
     *
     *  Passing `null` resets every piece to defaults and clears the
     *  algorithm-kind selector. */
    fun setSolverSpec(spec: SolverSpec?) {
        when (spec) {
            null -> {
                myAlgorithmKind.value = null
                myCommonMaxIterations.value = 100
                myCommonStreamNum.value = 0
                myCommonSolverName.value = null
                myCommonStartingPoint.value = null
                myCommonReplicationsPerEvaluation.value = 30
                mySaTemperature.value = TemperatureSpec.AutoCalibrate()
                mySaCoolingSchedule.value = CoolingScheduleSpec.Exponential(initialTemperature = 100.0)
                mySaStoppingTemperature.value = 0.001
                myCeSampler.value = CESamplerSpec.Normal()
                myCeElitePct.value = 0.1
                myCeSampleSize.value = 50
                myRsplineInitialNumReps.value = 2
                myRsplineGrowthRate.value = 1.5
                myRsplineMaxNumReplications.value = 30
                myRandomRestart.value = null
            }
            is SolverSpec.StochasticHillClimbing -> {
                myAlgorithmKind.value = AlgorithmKind.STOCHASTIC_HILL_CLIMBING
                myCommonMaxIterations.value = spec.maxIterations
                myCommonStreamNum.value = spec.streamNum
                myCommonSolverName.value = spec.name
                myCommonStartingPoint.value = spec.startingPoint
                myCommonReplicationsPerEvaluation.value = spec.replicationsPerEvaluation
                myRandomRestart.value = spec.randomRestart
            }
            is SolverSpec.SimulatedAnnealing -> {
                myAlgorithmKind.value = AlgorithmKind.SIMULATED_ANNEALING
                myCommonMaxIterations.value = spec.maxIterations
                myCommonStreamNum.value = spec.streamNum
                myCommonSolverName.value = spec.name
                myCommonStartingPoint.value = spec.startingPoint
                myCommonReplicationsPerEvaluation.value = spec.replicationsPerEvaluation
                mySaTemperature.value = spec.temperature
                mySaCoolingSchedule.value = spec.coolingSchedule
                mySaStoppingTemperature.value = spec.stoppingTemperature
                myRandomRestart.value = spec.randomRestart
            }
            is SolverSpec.CrossEntropy -> {
                myAlgorithmKind.value = AlgorithmKind.CROSS_ENTROPY
                myCommonMaxIterations.value = spec.maxIterations
                myCommonStreamNum.value = spec.streamNum
                myCommonSolverName.value = spec.name
                myCommonStartingPoint.value = spec.startingPoint
                myCommonReplicationsPerEvaluation.value = spec.replicationsPerEvaluation
                myCeSampler.value = spec.sampler
                // Substrate field is nullable ("use built-in default"); the
                // GUI surfaces concrete defaults so the user always sees a
                // value.  Pre-load the user's prior explicit value when
                // present; otherwise keep the GUI's seeded default.
                spec.elitePct?.let { myCeElitePct.value = it }
                spec.ceSampleSize?.let { myCeSampleSize.value = it }
                myRandomRestart.value = spec.randomRestart
            }
            is SolverSpec.RSpline -> {
                myAlgorithmKind.value = AlgorithmKind.R_SPLINE
                myCommonMaxIterations.value = spec.maxIterations
                myCommonStreamNum.value = spec.streamNum
                myCommonSolverName.value = spec.name
                myCommonStartingPoint.value = spec.startingPoint
                myRsplineInitialNumReps.value = spec.initialNumReps
                myRsplineGrowthRate.value = spec.sampleSizeGrowthRate
                myRsplineMaxNumReplications.value = spec.maxNumReplications
                myRandomRestart.value = spec.randomRestart
            }
        }
        recomputeSolverSpec()
        markDirtyStructural()
    }

    // ── Solver-spec piece mutators ─────────────────────────────────────────

    /** Set the active algorithm.  Pre-populates the spec by triggering
     *  a recompute with the current per-piece values; the user
     *  immediately sees a fully-defaulted form. */
    fun setAlgorithmKind(kind: AlgorithmKind?) {
        if (myAlgorithmKind.value == kind) return
        myAlgorithmKind.value = kind
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setCommonMaxIterations(value: Int) {
        require(value > 0) { "maxIterations must be > 0; was $value" }
        if (myCommonMaxIterations.value == value) return
        myCommonMaxIterations.value = value
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setCommonStreamNum(value: Int) {
        require(value >= 0) { "streamNum must be >= 0; was $value" }
        if (myCommonStreamNum.value == value) return
        myCommonStreamNum.value = value
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setCommonSolverName(name: String?) {
        val coerced = name?.takeIf { it.isNotBlank() }
        if (myCommonSolverName.value == coerced) return
        myCommonSolverName.value = coerced
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setCommonStartingPoint(point: Map<String, Double>?) {
        if (myCommonStartingPoint.value == point) return
        myCommonStartingPoint.value = point
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setCommonReplicationsPerEvaluation(value: Int) {
        require(value > 0) { "replicationsPerEvaluation must be > 0; was $value" }
        if (myCommonReplicationsPerEvaluation.value == value) return
        myCommonReplicationsPerEvaluation.value = value
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setSaTemperature(spec: TemperatureSpec) {
        if (mySaTemperature.value == spec) return
        mySaTemperature.value = spec
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setSaCoolingSchedule(spec: CoolingScheduleSpec) {
        if (mySaCoolingSchedule.value == spec) return
        mySaCoolingSchedule.value = spec
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setSaStoppingTemperature(value: Double) {
        require(value > 0.0 && value.isFinite()) {
            "stoppingTemperature must be > 0 and finite; was $value"
        }
        if (mySaStoppingTemperature.value == value) return
        mySaStoppingTemperature.value = value
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setCeSampler(spec: CESamplerSpec) {
        if (myCeSampler.value == spec) return
        myCeSampler.value = spec
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setCeElitePct(value: Double) {
        require(value > 0.0 && value < 1.0) {
            "elitePct must be strictly in (0, 1); was $value"
        }
        if (myCeElitePct.value == value) return
        myCeElitePct.value = value
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setCeSampleSize(value: Int) {
        require(value >= 1) { "ceSampleSize must be >= 1; was $value" }
        if (myCeSampleSize.value == value) return
        myCeSampleSize.value = value
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setRsplineInitialNumReps(value: Int) {
        require(value > 0) { "initialNumReps must be > 0; was $value" }
        if (myRsplineInitialNumReps.value == value) return
        myRsplineInitialNumReps.value = value
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setRsplineGrowthRate(value: Double) {
        require(value > 0.0 && value.isFinite()) {
            "sampleSizeGrowthRate must be > 0 and finite; was $value"
        }
        if (myRsplineGrowthRate.value == value) return
        myRsplineGrowthRate.value = value
        recomputeSolverSpec()
        markDirtyStructural()
    }

    fun setRsplineMaxNumReplications(value: Int) {
        require(value > 0) { "maxNumReplications must be > 0; was $value" }
        if (myRsplineMaxNumReplications.value == value) return
        myRsplineMaxNumReplications.value = value
        recomputeSolverSpec()
        markDirtyStructural()
    }

    /** Set the random-restart wrapper.  `null` = no restart. */
    fun setRandomRestart(spec: RandomRestartSpec?) {
        if (myRandomRestart.value == spec) return
        myRandomRestart.value = spec
        recomputeSolverSpec()
        markDirtyStructural()
    }

    /** Recompute [solverSpec] from the per-piece flows.  Publishes
     *  `null` when [algorithmKind] is null or the assembled spec
     *  fails the substrate's `init {}` invariant (defensive — the
     *  per-piece mutators above enforce most invariants directly). */
    private fun recomputeSolverSpec() {
        val kind = myAlgorithmKind.value
        val next: SolverSpec? = if (kind == null) null else try {
            when (kind) {
                AlgorithmKind.STOCHASTIC_HILL_CLIMBING -> SolverSpec.StochasticHillClimbing(
                    startingPoint = myCommonStartingPoint.value,
                    maxIterations = myCommonMaxIterations.value,
                    randomRestart = myRandomRestart.value,
                    streamNum = myCommonStreamNum.value,
                    name = effectiveSolverName(),
                    replicationsPerEvaluation = myCommonReplicationsPerEvaluation.value
                )
                AlgorithmKind.SIMULATED_ANNEALING -> SolverSpec.SimulatedAnnealing(
                    startingPoint = myCommonStartingPoint.value,
                    maxIterations = myCommonMaxIterations.value,
                    randomRestart = myRandomRestart.value,
                    streamNum = myCommonStreamNum.value,
                    name = effectiveSolverName(),
                    replicationsPerEvaluation = myCommonReplicationsPerEvaluation.value,
                    temperature = mySaTemperature.value,
                    coolingSchedule = mySaCoolingSchedule.value,
                    stoppingTemperature = mySaStoppingTemperature.value
                )
                AlgorithmKind.CROSS_ENTROPY -> SolverSpec.CrossEntropy(
                    startingPoint = myCommonStartingPoint.value,
                    maxIterations = myCommonMaxIterations.value,
                    randomRestart = myRandomRestart.value,
                    streamNum = myCommonStreamNum.value,
                    name = effectiveSolverName(),
                    replicationsPerEvaluation = myCommonReplicationsPerEvaluation.value,
                    sampler = myCeSampler.value,
                    elitePct = myCeElitePct.value,
                    ceSampleSize = myCeSampleSize.value
                )
                AlgorithmKind.R_SPLINE -> SolverSpec.RSpline(
                    startingPoint = myCommonStartingPoint.value,
                    maxIterations = myCommonMaxIterations.value,
                    randomRestart = myRandomRestart.value,
                    streamNum = myCommonStreamNum.value,
                    name = effectiveSolverName(),
                    initialNumReps = myRsplineInitialNumReps.value,
                    sampleSizeGrowthRate = myRsplineGrowthRate.value,
                    maxNumReplications = myRsplineMaxNumReplications.value
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }
        if (mySolverSpec.value != next) mySolverSpec.value = next
        refreshStepCompletion()
    }

    /** Replace the evaluation settings.  Preference — does not drop
     *  [lastResult]. */
    fun setEvaluationSpec(spec: EvaluationSpec) {
        if (myEvaluationSpec.value == spec) return
        myEvaluationSpec.value = spec
        markDirtyPreference()
    }

    /** Replace the tracking settings.  Preference — does not drop
     *  [lastResult]. */
    fun setTrackingSpec(spec: SolverTrackingSpec) {
        if (myTrackingSpec.value == spec) return
        myTrackingSpec.value = spec
        markDirtyPreference()
    }

    // ── Stepper navigation ─────────────────────────────────────────────────

    /** Step the user can advance to.  A step is reachable iff every
     *  earlier step in the enum order is complete.  [Step.MODEL] is
     *  always reachable.  See `Step.kt` for the per-step completion
     *  semantics. */
    fun canAdvanceTo(step: Step): Boolean {
        val completion = myStepCompletion.value
        for (other in Step.entries) {
            if (other == step) return true
            if (completion[other] != true) return false
        }
        return true
    }

    /** Move the active step to [step].  No-op when [step] is not
     *  currently reachable (frame widgets should disable click on
     *  locked pills, but this method is defensive). */
    fun jumpToStep(step: Step) {
        if (!canAdvanceTo(step)) return
        if (myActiveStep.value == step) return
        myActiveStep.value = step
    }

    // ── Document operations ────────────────────────────────────────────────

    /** Reset the document to a fresh blank state.  Clears every
     *  spec, the file binding, dirty flags, and the active step.
     *  Output preferences (analysis name) also reset to defaults. */
    fun newDocument() {
        myOutput.value = OptimizationOutputConfig()
        myModelTemplate.value = null
        // Clear problem-spec pieces directly (avoid the fan-out shim's
        // markDirtyStructural — we want the document to land clean).
        myObjectiveResponseName.value = null
        myOptimizationType.value = OptimizationType.MINIMIZE
        myProblemName.value = null
        myIndifferenceZoneParameter.value = 0.0
        myObjectiveGranularity.value = 0.0
        myInputs.value = emptyList()
        mySelectedInputIndex.value = -1
        myResponseNames.value = emptyList()
        myLinearConstraints.value = emptyList()
        myResponseConstraints.value = emptyList()
        myDefaultLinearPenalty.value = PenaltyFunctionSpec.DynamicPolynomial()
        myDefaultResponsePenalty.value = PenaltyFunctionSpec.WithMemory()
        myProblemSpec.value = null
        // Clear solver-spec pieces directly (avoid the fan-out shim's
        // markDirtyStructural — same reason as problem-spec above).
        myAlgorithmKind.value = null
        myCommonMaxIterations.value = 100
        myCommonStreamNum.value = 0
        myCommonSolverName.value = null
        myCommonStartingPoint.value = null
        myCommonReplicationsPerEvaluation.value = 30
        mySaTemperature.value = TemperatureSpec.AutoCalibrate()
        mySaCoolingSchedule.value = CoolingScheduleSpec.Exponential(initialTemperature = 100.0)
        mySaStoppingTemperature.value = 0.001
        myCeSampler.value = CESamplerSpec.Normal()
        myCeElitePct.value = 0.1
        myCeSampleSize.value = 50
        myRsplineInitialNumReps.value = 2
        myRsplineGrowthRate.value = 1.5
        myRsplineMaxNumReplications.value = 30
        myRandomRestart.value = null
        mySolverSpec.value = null
        myEvaluationSpec.value = EvaluationSpec()
        myTrackingSpec.value = SolverTrackingSpec()
        documentLifecycle.reset()
        runLifecycle.reset()
        myLatestIteration.value = null
        myActiveStep.value = Step.initial
        // Validation cache resets: empty document fails the live
        // MISSING_MODEL check, and the model-aware cache is stale.
        myModelAwareValidation.value = null
        myModelAwareStale.value = true
        refreshModelDescriptor()
        refreshStepCompletion()
        refreshDocumentValidation()
        refreshAutoRunOutputDir()
    }

    /** Alias for [newDocument] — matches the Experiment / Scenario
     *  controllers' naming. */
    fun resetConfiguration() = newDocument()

    /** Load a TOML document into the controller.  Returns a
     *  [LoadResult.Success] carrying the decoded configuration on
     *  success and a [LoadResult.Failed] with the parser's message
     *  on failure.  Side effects (clearing dirty, binding the file)
     *  only occur on success. */
    fun loadConfiguration(path: Path): LoadResult {
        val text = try {
            path.toFile().readText()
        } catch (e: Exception) {
            return LoadResult.Failed("Could not read file: ${e.message}")
        }
        val config = try {
            OptimizationRunConfigurationToml.decode(text)
        } catch (e: Exception) {
            return LoadResult.Failed("Could not parse TOML: ${e.message}")
        }
        installLoaded(config)
        documentLifecycle.markSaved(path)
        runLifecycle.reset()
        myLatestIteration.value = null
        myActiveStep.value = Step.initial
        // Loaded doc fully replaces the prior validation snapshot.
        myModelAwareValidation.value = null
        myModelAwareStale.value = true
        refreshModelDescriptor()
        refreshStepCompletion()
        refreshDocumentValidation()
        refreshAutoRunOutputDir()
        return LoadResult.Success(config)
    }

    private fun installLoaded(config: OptimizationRunConfiguration) {
        myOutput.value = config.output
        myModelTemplate.value = config.model
        // Fan out config.problem into the per-piece flows.  A null
        // problem section (in-progress draft) leaves every piece at
        // its initial default — matching the controller's fresh-doc
        // state for the Problem step.
        val p = config.problem
        if (p != null) {
            myObjectiveResponseName.value = p.objectiveResponseName
            myOptimizationType.value = p.optimizationType
            myProblemName.value = p.problemName
            myIndifferenceZoneParameter.value = p.indifferenceZoneParameter
            myObjectiveGranularity.value = p.objectiveGranularity
            myInputs.value = p.inputs
            mySelectedInputIndex.value = if (p.inputs.isEmpty()) -1 else 0
            myResponseNames.value = p.responseNames
            myLinearConstraints.value = p.linearConstraints
            mySelectedLinearConstraintIndex.value = if (p.linearConstraints.isEmpty()) -1 else 0
            myResponseConstraints.value = p.responseConstraints
            mySelectedResponseConstraintIndex.value = if (p.responseConstraints.isEmpty()) -1 else 0
            myDefaultLinearPenalty.value = p.defaultLinearPenalty
            myDefaultResponsePenalty.value = p.defaultResponsePenalty
        } else {
            myObjectiveResponseName.value = null
            myOptimizationType.value = OptimizationType.MINIMIZE
            myProblemName.value = null
            myIndifferenceZoneParameter.value = 0.0
            myObjectiveGranularity.value = 0.0
            myInputs.value = emptyList()
            mySelectedInputIndex.value = -1
            myResponseNames.value = emptyList()
            myLinearConstraints.value = emptyList()
            mySelectedLinearConstraintIndex.value = -1
            myResponseConstraints.value = emptyList()
            mySelectedResponseConstraintIndex.value = -1
            myDefaultLinearPenalty.value = PenaltyFunctionSpec.DynamicPolynomial()
            myDefaultResponsePenalty.value = PenaltyFunctionSpec.WithMemory()
        }
        // Rebuild the consolidated spec from the per-piece flows we
        // just populated — this picks up the derived modelIdentifier
        // (see `deriveModelIdentifier`) so a loaded document whose
        // persisted problem omitted the field still satisfies the
        // substrate's non-blank requirement at submit time.
        recomputeProblemSpec()
        // Fan out config.solver into the per-piece flows (or reset to
        // defaults when null).  Use the dedicated setSolverSpec
        // fan-out helper rather than just stashing the consolidated
        // value, so the per-piece flows reflect the loaded values
        // and the Algorithm step's editor surfaces them on open.
        // Since the document is just being loaded we then re-clear
        // the dirty flag below.
        installSolverPieces(config.solver)
        mySolverSpec.value = config.solver
        myEvaluationSpec.value = config.evaluation
        myTrackingSpec.value = config.tracking
    }

    /** Internal helper called only by [installLoaded]: pushes a loaded
     *  [SolverSpec] (or null) into the per-piece flows without going
     *  through the public mutator (which would mark the document
     *  dirty).  Mirrors the problem-spec fan-out pattern. */
    private fun installSolverPieces(spec: SolverSpec?) {
        when (spec) {
            null -> {
                myAlgorithmKind.value = null
                myCommonMaxIterations.value = 100
                myCommonStreamNum.value = 0
                myCommonSolverName.value = null
                myCommonStartingPoint.value = null
                myCommonReplicationsPerEvaluation.value = 30
                mySaTemperature.value = TemperatureSpec.AutoCalibrate()
                mySaCoolingSchedule.value = CoolingScheduleSpec.Exponential(initialTemperature = 100.0)
                mySaStoppingTemperature.value = 0.001
                myCeSampler.value = CESamplerSpec.Normal()
                myCeElitePct.value = 0.1
                myCeSampleSize.value = 50
                myRsplineInitialNumReps.value = 2
                myRsplineGrowthRate.value = 1.5
                myRsplineMaxNumReplications.value = 30
                myRandomRestart.value = null
            }
            is SolverSpec.StochasticHillClimbing -> {
                myAlgorithmKind.value = AlgorithmKind.STOCHASTIC_HILL_CLIMBING
                myCommonMaxIterations.value = spec.maxIterations
                myCommonStreamNum.value = spec.streamNum
                myCommonSolverName.value = spec.name
                myCommonStartingPoint.value = spec.startingPoint
                myCommonReplicationsPerEvaluation.value = spec.replicationsPerEvaluation
                myRandomRestart.value = spec.randomRestart
            }
            is SolverSpec.SimulatedAnnealing -> {
                myAlgorithmKind.value = AlgorithmKind.SIMULATED_ANNEALING
                myCommonMaxIterations.value = spec.maxIterations
                myCommonStreamNum.value = spec.streamNum
                myCommonSolverName.value = spec.name
                myCommonStartingPoint.value = spec.startingPoint
                myCommonReplicationsPerEvaluation.value = spec.replicationsPerEvaluation
                mySaTemperature.value = spec.temperature
                mySaCoolingSchedule.value = spec.coolingSchedule
                mySaStoppingTemperature.value = spec.stoppingTemperature
                myRandomRestart.value = spec.randomRestart
            }
            is SolverSpec.CrossEntropy -> {
                myAlgorithmKind.value = AlgorithmKind.CROSS_ENTROPY
                myCommonMaxIterations.value = spec.maxIterations
                myCommonStreamNum.value = spec.streamNum
                myCommonSolverName.value = spec.name
                myCommonStartingPoint.value = spec.startingPoint
                myCommonReplicationsPerEvaluation.value = spec.replicationsPerEvaluation
                myCeSampler.value = spec.sampler
                spec.elitePct?.let { myCeElitePct.value = it }
                spec.ceSampleSize?.let { myCeSampleSize.value = it }
                myRandomRestart.value = spec.randomRestart
            }
            is SolverSpec.RSpline -> {
                myAlgorithmKind.value = AlgorithmKind.R_SPLINE
                myCommonMaxIterations.value = spec.maxIterations
                myCommonStreamNum.value = spec.streamNum
                myCommonSolverName.value = spec.name
                myCommonStartingPoint.value = spec.startingPoint
                myRsplineInitialNumReps.value = spec.initialNumReps
                myRsplineGrowthRate.value = spec.sampleSizeGrowthRate
                myRsplineMaxNumReplications.value = spec.maxNumReplications
                myRandomRestart.value = spec.randomRestart
            }
        }
    }

    /** Encode the current document to TOML and write it to [path].
     *  Throws when no model has been selected yet — callers gate Save
     *  on [currentConfiguration] being non-null.  Documents with only
     *  a model (no problem / no solver) are valid in-progress drafts
     *  and save successfully. */
    fun saveConfiguration(path: Path) {
        val config = currentConfiguration()
            ?: error("Cannot save: no model selected.  Pick a model on the Model step first.")
        Files.createDirectories(path.parent)
        path.toFile().writeText(OptimizationRunConfigurationToml.encode(config))
        markSaved(path)
    }

    /** Mark the document as saved at [path].  Also auto-fills the
     *  output's [OptimizationOutputConfig.analysisName] from the
     *  file stem when still at the default "Untitled". */
    fun markSaved(path: Path) {
        documentLifecycle.markSaved(path)
        analysisNameFromFileStem(
            path = path,
            currentName = myOutput.value.analysisName,
            sanitizer = ::sanitizeAnalysisName
        )?.let { newName ->
            myOutput.value = myOutput.value.copy(analysisName = newName)
        }
    }

    /** Compose the live document from the controller's StateFlows,
     *  or return `null` when no model is set.  The returned config
     *  may carry `null` [OptimizationRunConfiguration.problem] and /
     *  or [OptimizationRunConfiguration.solver] for in-progress
     *  drafts; the file-save path accepts those.  Submit-time
     *  consumers (the solver factory + validator) reject the partial
     *  shape with clear errors. */
    fun currentConfiguration(): OptimizationRunConfiguration? {
        val model = myModelTemplate.value ?: return null
        return OptimizationRunConfiguration(
            output = myOutput.value,
            model = model,
            problem = myProblemSpec.value,
            solver = mySolverSpec.value,
            evaluation = myEvaluationSpec.value,
            tracking = myTrackingSpec.value
        )
    }

    // ── Run lifecycle ──────────────────────────────────────────────────────

    /**
     *  Submit the document for execution.
     *
     *  Builds a [ksl.simopt.solvers.Solver] via
     *  [OptimizationSolverFactory], attaches CSV / console trackers
     *  per [trackingSpec], and hands the solver to
     *  [OptimizationOrchestrator].  Events are forwarded onto
     *  [eventFlow]; [latestIteration] is overwritten on each
     *  `IterationCompleted`; [lastResult] is populated when the run
     *  terminates with [RunResult.OptimizationCompleted].
     *
     *  No-op when:
     *  - a run is already in flight,
     *  - [currentConfiguration] returns `null` (no model selected),
     *  - the document carries a `null` problem or solver section
     *    (the factory rejects in-progress drafts).
     *
     *  Build / factory failures (e.g. an unknown decision-variable
     *  name) flow back as [RunResult.Failed]; the controller keeps
     *  [lastResult] = `null`.
     */
    fun submit() {
        if (myRunning.value) return
        val config = currentConfiguration() ?: return
        if (config.problem == null || config.solver == null) return

        val provider = bundleLibrary.bundleProvider.value
        val solver: Solver = try {
            OptimizationSolverFactory(provider).build(config)
        } catch (t: Throwable) {
            // Treat factory failures as a terminal "failed" run so the
            // UI can show the message.  We don't have a real
            // RunHandle to emit on, so we set running=false and leave
            // lastResult null.  The notification surface in the
            // Execute step handles the user-visible toast.
            return
        }

        // Snapshot the run output directory now so any subsequent
        // edits or `refreshAutoRunOutputDir()` calls don't move the
        // target out from under the active run.  We do NOT create
        // the directory here — each writer (tracker attach,
        // future HTML report, etc.) creates its own parents
        // lazily, so a run with no enabled artifacts leaves no
        // empty directory behind.
        val runDir = myRunOutputDir.value

        attachTrackers(solver, config, runDir)

        val handle: RunHandle = OptimizationOrchestrator().submit(solver = solver)

        val startMillis = System.currentTimeMillis()
        currentRunSnapshot = RunStartSnapshot(
            runId = handle.runId,
            startTimeIso = java.time.Instant.ofEpochMilli(startMillis).toString(),
            config = config,
            runDir = runDir
        )

        currentRunHandle = handle
        myActiveSolver.value = solver
        myLatestIteration.value = null
        runLifecycle.setLastResult(null)
        myRunning.value = true

        // Event forwarder: lives on edtScope so Swing collectors see
        // updates on the EDT without an explicit cross-thread hop.
        currentRunJob = edtScope.launch {
            handle.events.collect { event ->
                myEventFlow.emit(event)
                if (event is RunEvent.IterationCompleted) {
                    myLatestIteration.value = event
                }
            }
        }

        // Terminal-state observer.
        edtScope.launch {
            val result = try {
                handle.result.await()
            } catch (_: Throwable) {
                null
            }
            val snapshot = currentRunSnapshot
            if (result is RunResult.OptimizationCompleted) {
                // A successful completion means the result matches
                // the document — bind the result AND clear the stale
                // flag in one atomic substrate call.
                runLifecycle.markRunCompleted(result)
                if (snapshot != null) {
                    // Capture the live Solver and its SolverResult
                    // *before* we null myActiveSolver below — both
                    // are only meaningful while the Solver instance
                    // is reachable.  The Solver reference itself
                    // drives `Solver.configurationProperties` (read
                    // by the report's "Solver configuration" section
                    // and by summary.toml's [solverConfiguration]
                    // block); the SolverResult drives the
                    // run-summary / evaluator-metrics / best-solution
                    // tables via the framework's `solverResult(...)`
                    // DSL extension.
                    val capturedSolver = myActiveSolver.value
                    val capturedSolverResult = capturedSolver?.solverResult
                    runCatching {
                        if (capturedSolver != null && capturedSolverResult != null) {
                            ResultsArtifactWriter.writeCompleted(
                                config = snapshot.config,
                                result = result,
                                solverResult = capturedSolverResult,
                                solverInstance = capturedSolver,
                                runDir = snapshot.runDir
                            )
                        }
                    }
                }
            } else if (snapshot != null) {
                // Partial summary for cancelled / failed runs —
                // the run output directory carries a TOML record
                // of what happened, with the best-so-far snapshot
                // (if any iteration fired) and the reason.
                val (status, reason) = when (result) {
                    is RunResult.Cancelled -> ResultsStatus.CANCELLED to result.reason
                    is RunResult.Failed -> ResultsStatus.FAILED to
                        (result.error::class.simpleName ?: "Failed")
                    null -> ResultsStatus.FAILED to "Terminal observer caught an exception"
                    else -> ResultsStatus.FAILED to "Unexpected terminal result ${result::class.simpleName}"
                }
                val endMillis = System.currentTimeMillis()
                val startMillis = java.time.Instant.parse(snapshot.startTimeIso).toEpochMilli()
                val latestBest = myLatestIteration.value?.let {
                    LatestBestSnapshot(
                        iteration = it.iteration,
                        estimatedObjective = it.estimatedObjectiveValue,
                        bestInputs = HashMap(it.bestInputs)
                    )
                }
                // Same capture-before-clear principle as the
                // completed branch — the partial summary records the
                // solver configuration so the user can correlate a
                // cancelled run with the parameters it was using.
                val capturedSolver = myActiveSolver.value
                runCatching {
                    ResultsArtifactWriter.writeIncomplete(
                        config = snapshot.config,
                        status = status,
                        runId = snapshot.runId,
                        startTimeIso = snapshot.startTimeIso,
                        endTimeIso = java.time.Instant.ofEpochMilli(endMillis).toString(),
                        elapsedMillis = endMillis - startMillis,
                        latestBest = latestBest,
                        statusReason = reason,
                        runDir = snapshot.runDir,
                        solverInstance = capturedSolver
                    )
                }
            }
            // Expose the just-written directory so the Results
            // step's artifact list points at it (the next `runOutputDir`
            // refresh below will advance to a fresh run-NNN that has
            // no artifacts yet).
            if (snapshot != null) {
                myLastCompletedRunDir.value = snapshot.runDir
            }
            currentRunJob?.cancel()
            currentRunJob = null
            currentRunHandle = null
            currentRunSnapshot = null
            myActiveSolver.value = null
            myRunning.value = false
            // Advance to the next auto run-NNN slot so a follow-up
            // submit doesn't reuse the directory we just wrote.  This
            // also overrides any one-shot `setRunOutputDir` the user
            // may have set for this completed run.
            refreshAutoRunOutputDir()
            refreshStepCompletion()
        }
    }

    /**
     *  Cancel the in-flight run.  No-op when no run is active.  The
     *  underlying [RunHandle.cancel] signals the solver to stop at
     *  its next iteration boundary; the terminal-state observer (set
     *  up by [submit]) clears running state once cancellation
     *  resolves.
     */
    fun cancel() {
        currentRunHandle?.cancel("Cancelled by user")
    }

    /**
     *  Override the [runOutputDir] for the next [submit].
     *
     *  Use case: the GUI's `[Change…]` button on the Execute step
     *  opens a directory chooser and commits the picked path here.
     *  The chosen path replaces the auto-numbered default for the
     *  next submit only — after a successful run, [runOutputDir]
     *  resets to the next `run-NNN` slot under the current analysis
     *  directory.
     */
    fun setRunOutputDir(path: Path) {
        if (myRunOutputDir.value == path) return
        myRunOutputDir.value = path
    }

    /**
     *  Recompute [runOutputDir] from the live analysis name using
     *  [OptimizationPaths.nextRunSubdir].  Called when the analysis name
     *  changes and after each successful run so the displayed
     *  destination matches the next-available `run-NNN` slot.
     */
    private fun refreshAutoRunOutputDir() {
        myRunOutputDir.value = computeAutoRunOutputDir()
    }

    private fun initialRunOutputDir(): Path = computeAutoRunOutputDir()

    private fun computeAutoRunOutputDir(): Path {
        val analysisDir = OptimizationPaths.outputDir(appWorkspace, myOutput.value.analysisName)
        return OptimizationPaths.nextRunSubdir(analysisDir)
    }

    /**
     *  Re-run the model-aware validator and refresh the cached
     *  result + stale flag.  Synchronous — builds a probe model on
     *  the calling thread.  Called by the Execute step's
     *  "Re-check against model" button.
     */
    fun runModelAwareValidationNow() {
        val config = currentConfiguration()
        val result = if (config == null) {
            ValidationResult(
                errors = listOf(
                    FieldError(
                        path = "model",
                        message = "Select a model on the Model step first.",
                        severity = ValidationSeverity.ERROR,
                        code = "MISSING_MODEL"
                    )
                )
            )
        } else {
            try {
                OptimizationConfigurationValidator.validateForRun(config, bundleLibrary.bundleProvider.value)
            } catch (t: Throwable) {
                ValidationResult(
                    errors = listOf(
                        FieldError(
                            path = "model",
                            message = "Model-aware validation threw: ${t.message}",
                            severity = ValidationSeverity.ERROR,
                            code = "VALIDATOR_EXCEPTION"
                        )
                    )
                )
            }
        }
        myModelAwareValidation.value = result
        myModelAwareStale.value = false
    }

    /** Recompute [documentValidation] from the live config.
     *  Cheap — no model build.  Called from every dirty-marker
     *  and after each lifecycle transition (load / reset). */
    private fun refreshDocumentValidation() {
        val config = currentConfiguration()
        myDocumentValidation.value = if (config == null) {
            ValidationResult(
                errors = listOf(
                    FieldError(
                        path = "model",
                        message = "Select a model on the Model step before checking.",
                        severity = ValidationSeverity.ERROR,
                        code = "MISSING_MODEL"
                    )
                )
            )
        } else {
            OptimizationConfigurationValidator.validate(config)
        }
    }

    /** Delegate to the substrate's
     *  [ksl.app.optimization.tracking.OptimizationTrackerAttacher].
     *  This wrapper exists so the call site at submit() can stay
     *  short and the substrate version remains the single source
     *  of truth for tracker-variant selection (plain vs. nested
     *  for `RandomRestartSolver`). */
    private fun attachTrackers(
        solver: Solver,
        config: OptimizationRunConfiguration,
        runDir: Path
    ) {
        ksl.app.optimization.tracking.OptimizationTrackerAttacher.attach(
            solver = solver,
            trackingSpec = config.tracking,
            runDir = runDir,
            solverSpec = config.solver
        )
    }

    // ── Step completion derivation ─────────────────────────────────────────

    /** Recompute [stepCompletion] from the document StateFlows.
     *  Called from every structural mutator and from the load path. */
    private fun refreshStepCompletion() {
        myStepCompletion.value = computeStepCompletion()
    }

    private fun initialStepCompletion(): Map<Step, Boolean> = computeStepCompletion()

    private fun computeStepCompletion(): Map<Step, Boolean> {
        val model = myModelTemplate.value != null
        val problem = model && myProblemSpec.value != null
        // CONSTRAINTS is OPTIONAL — auto-completes the moment PROBLEM
        // is complete.  Algorithm gating therefore does not block on
        // constraint authoring, but the rail still shows the step so
        // users can visit it to author constraints + penalty defaults.
        val constraints = problem
        val solver = constraints && mySolverSpec.value != null
        // RUN_SETUP is complete in O2 as soon as ALGORITHM is complete.
        // Phase O7a tightens this to require validation pass against
        // the live model.
        val runSetup = solver
        val execute = runSetup && runLifecycle.lastResult.value != null
        // RESULTS is "complete" the moment a run exists — it's the
        // terminal step.
        val results = execute
        return mapOf(
            Step.MODEL to model,
            Step.PROBLEM to problem,
            Step.CONSTRAINTS to constraints,
            Step.ALGORITHM to solver,
            Step.RUN_SETUP to runSetup,
            Step.EXECUTE to execute,
            Step.RESULTS to results
        )
    }

    override fun close() {
        currentRunHandle?.cancel("SimoptAppController closed")
        currentRunHandle = null
        currentRunJob?.cancel()
        currentRunJob = null
        // E.5.8: close loaded bundles via the substrate — Simopt
        // previously skipped this step (Scenario + Experiment had
        // it inline).  Picks up uniform bundle-classloader
        // cleanup as a side-benefit of the substrate extraction.
        bundleLibrary.close()
        edtScope.cancel("SimoptAppController closed")
    }

    /** Outcome of [loadConfiguration]. */
    sealed class LoadResult {
        data class Success(val config: OptimizationRunConfiguration) : LoadResult()
        data class Failed(val reason: String) : LoadResult()
    }
}
