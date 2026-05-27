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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import ksl.app.bundle.BundleLoader
import ksl.app.bundle.BundleModelProvider
import ksl.app.bundle.LoadedBundle
import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.RVParameterOverride
import ksl.app.config.optimization.EvaluationSpec
import ksl.app.config.optimization.LinearConstraintSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationOutputConfig
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationRunConfigurationToml
import ksl.app.config.optimization.OptimizationType
import ksl.app.config.optimization.PenaltyFunctionSpec
import ksl.app.config.optimization.ResponseConstraintSpec
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.SolverTrackingSpec
import ksl.app.config.sanitizeAnalysisName
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.settings.UserSettingsStore
import ksl.app.swing.simopt.stepper.Step
import ksl.controls.ModelControlsExport
import ksl.controls.experiments.ExperimentRunParameters
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
     *  other apps' convention (spaces → underscores). */
    val appNameSanitized: String = appName.replace(" ", "_")

    /** Workspace subdirectory dedicated to this app.  Identical
     *  semantics to the Experiment / Scenario apps:
     *  `<active-workspace>/<appNameSanitized>/`. */
    val appWorkspace: Path
        get() = settingsStore.activeWorkspace().resolve(appNameSanitized)

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

    private val mySolverSpec = MutableStateFlow<SolverSpec?>(null)
    /** Algorithm choice + parameters.  `null` until the Algorithm
     *  step commits a `SolverSpec`.  Step.ALGORITHM completion gate. */
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

    private val myLoadedBundles = MutableStateFlow<List<LoadedBundle>>(emptyList())
    /** Bundles available for model selection.  Auto-populated from the
     *  classpath at construction time; grown by [loadBundleJar]. */
    val loadedBundles: StateFlow<List<LoadedBundle>> = myLoadedBundles.asStateFlow()

    private val myBundleProvider = MutableStateFlow<BundleModelProvider?>(null)
    /** `BundleModelProvider` over the current [loadedBundles].  `null`
     *  when no bundles are loaded. */
    val bundleProvider: StateFlow<BundleModelProvider?> = myBundleProvider.asStateFlow()

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

    private val myCurrentFile = MutableStateFlow<Path?>(null)
    /** Absolute path of the currently-loaded TOML file, or `null`
     *  for an unsaved document. */
    val currentFile: StateFlow<Path?> = myCurrentFile.asStateFlow()

    private val myIsDirty = MutableStateFlow(false)
    /** `true` when in-memory state differs from the on-disk file
     *  (or when there is no file yet and the document is non-empty). */
    val isDirty: StateFlow<Boolean> = myIsDirty.asStateFlow()

    private val myEditedSinceLastRun = MutableStateFlow(false)
    /** `true` when the document has been edited since the last
     *  successful run.  Drives the stale-results banner on the
     *  Execute / Results steps. */
    val editedSinceLastRun: StateFlow<Boolean> = myEditedSinceLastRun.asStateFlow()

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

    private val myLastResult = MutableStateFlow<RunResult.OptimizationCompleted?>(null)
    /** Result of the most recent successful run, or `null` if no
     *  run has completed or the result was cleared by an R1
     *  structural edit. */
    val lastResult: StateFlow<RunResult.OptimizationCompleted?> = myLastResult.asStateFlow()

    init {
        // Auto-discover classpath bundles so a packaged app shows
        // available models immediately.  Mirrors Experiment / Scenario
        // controllers.
        val classpathBundles = BundleLoader.loadFromClasspath()
        if (classpathBundles.isNotEmpty()) updateBundles(classpathBundles)
    }

    // ── Bundle management ──────────────────────────────────────────────────

    private fun updateBundles(bundles: List<LoadedBundle>) {
        myLoadedBundles.value = bundles
        myBundleProvider.value = if (bundles.isEmpty()) null else BundleModelProvider(bundles)
        // Re-resolve the descriptor: a previously-unresolvable ref
        // may now resolve because the bundle it points at just
        // arrived in the loaded set.
        refreshModelDescriptor()
    }

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

    /**
     *  Outcome of [loadBundleJar].
     */
    sealed class LoadBundleResult {
        /** At least one new bundle (with a new bundleId) was loaded. */
        data class Loaded(val newBundleIds: List<String>) : LoadBundleResult()

        /** The JAR loaded successfully but exposed no new bundles
         *  (either zero `KSLModelBundle` SPI entries or every entry's
         *  bundleId was already present in [loadedBundles]). */
        object NoBundles : LoadBundleResult()

        /** The loader threw — typically a malformed JAR or a class
         *  loader failure.  [reason] carries the exception message. */
        data class Failed(val reason: String) : LoadBundleResult()
    }

    /**
     *  Load every `KSLModelBundle` from the JAR at [jarPath] and
     *  append the discovered bundles to [loadedBundles].  Duplicates
     *  (bundleIds already present) are silently discarded.  Same
     *  shape as Experiment / Scenario controllers' loaders.
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

    // ── R1 lifecycle helpers ───────────────────────────────────────────────

    /** Mark the document dirty AND stale.  Called from every
     *  structural mutator.  Also drops [lastResult] since the
     *  document no longer matches what produced it. */
    private fun markDirtyStructural() {
        if (!myIsDirty.value) myIsDirty.value = true
        if (!myEditedSinceLastRun.value) myEditedSinceLastRun.value = true
        if (myLastResult.value != null) myLastResult.value = null
        refreshStepCompletion()
    }

    /** Mark the document dirty only — used for preferences (output,
     *  evaluation, tracking) that don't invalidate a prior run. */
    private fun markDirtyPreference() {
        if (!myIsDirty.value) myIsDirty.value = true
    }

    // ── Mutators ───────────────────────────────────────────────────────────

    /** Replace the document-wide output settings.  Preference — does
     *  not drop [lastResult]. */
    fun setOutput(spec: OptimizationOutputConfig) {
        if (myOutput.value == spec) return
        myOutput.value = spec
        markDirtyPreference()
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
        mySolverSpec.value = null
        myEvaluationSpec.value = EvaluationSpec()
        myTrackingSpec.value = SolverTrackingSpec()
        refreshModelDescriptor()
        markDirtyStructural()
    }

    private fun buildTemplateFor(ref: ModelReference): ModelRunTemplate {
        val descriptor: ModelDescriptor? = (ref as? ModelReference.ByBundleAndModelId)?.let { byRef ->
            val bundle = myLoadedBundles.value.firstOrNull { it.bundle.bundleId == byRef.bundleId }
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
                    problemName = myProblemName.value,
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

    /** Replace the solver specification.  Structural — drops
     *  [lastResult]. */
    fun setSolverSpec(spec: SolverSpec?) {
        if (mySolverSpec.value == spec) return
        mySolverSpec.value = spec
        markDirtyStructural()
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
        mySolverSpec.value = null
        myEvaluationSpec.value = EvaluationSpec()
        myTrackingSpec.value = SolverTrackingSpec()
        myCurrentFile.value = null
        myIsDirty.value = false
        myEditedSinceLastRun.value = false
        myLastResult.value = null
        myActiveStep.value = Step.initial
        refreshModelDescriptor()
        refreshStepCompletion()
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
        myCurrentFile.value = path
        myIsDirty.value = false
        myEditedSinceLastRun.value = false
        myLastResult.value = null
        myActiveStep.value = Step.initial
        refreshModelDescriptor()
        refreshStepCompletion()
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
        myProblemSpec.value = config.problem
        mySolverSpec.value = config.solver
        myEvaluationSpec.value = config.evaluation
        myTrackingSpec.value = config.tracking
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
        myCurrentFile.value = path
        myIsDirty.value = false
        if (myOutput.value.analysisName == "Untitled") {
            val stem = path.fileName.toString().substringBeforeLast('.')
            if (stem.isNotBlank()) {
                myOutput.value = myOutput.value.copy(
                    analysisName = sanitizeAnalysisName(stem)
                )
            }
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

    // ── Run lifecycle (stubs — wired in Phase O7b) ─────────────────────────

    /** Submit the document for execution.  **Stub in Phase O2** —
     *  Phase O7b will wire `OptimizationSolverFactory` →
     *  `KSLAppSession.submit(RunSpec.Optimization(...))`. */
    fun submit() {
        // Intentional no-op for the O2 skeleton.  The frame surfaces
        // a notification when the user clicks Run; see ExecuteStepPanel.
    }

    /** Cancel the in-flight run.  **Stub in Phase O2** — Phase O7b
     *  will wire `RunHandle.cancel(...)`. */
    fun cancel() {
        // Intentional no-op for the O2 skeleton.
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
        val execute = runSetup && myLastResult.value != null
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
        edtScope.cancel("SimoptAppController closed")
    }

    /** Outcome of [loadConfiguration]. */
    sealed class LoadResult {
        data class Success(val config: OptimizationRunConfiguration) : LoadResult()
        data class Failed(val reason: String) : LoadResult()
    }
}
