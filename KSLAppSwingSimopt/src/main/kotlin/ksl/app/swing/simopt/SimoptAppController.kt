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
import ksl.app.config.optimization.OptimizationOutputConfig
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationRunConfigurationToml
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

    private val myProblemSpec = MutableStateFlow<OptimizationProblemSpec?>(null)
    /** Optimization problem definition.  `null` until the Problem
     *  step has at least an objective + ≥ 1 decision variable.
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
        myProblemSpec.value = null
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

    /** Replace the problem specification.  Structural — drops
     *  [lastResult]. */
    fun setProblemSpec(spec: OptimizationProblemSpec?) {
        if (myProblemSpec.value == spec) return
        myProblemSpec.value = spec
        markDirtyStructural()
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
        myProblemSpec.value = config.problem
        mySolverSpec.value = config.solver
        myEvaluationSpec.value = config.evaluation
        myTrackingSpec.value = config.tracking
    }

    /** Encode the current document to TOML and write it to [path].
     *  Throws when the document is incomplete (model / problem /
     *  solver not yet specified) — callers should gate Save on
     *  [currentConfiguration] being non-null. */
    fun saveConfiguration(path: Path) {
        val config = currentConfiguration()
            ?: error("Cannot save: document is incomplete (model, problem, and solver must be set)")
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
     *  or return `null` when any required spec is missing.  Used by
     *  Save (which requires non-null) and by the Run step's
     *  validator. */
    fun currentConfiguration(): OptimizationRunConfiguration? {
        val model = myModelTemplate.value ?: return null
        val problem = myProblemSpec.value ?: return null
        val solver = mySolverSpec.value ?: return null
        return OptimizationRunConfiguration(
            output = myOutput.value,
            model = model,
            problem = problem,
            solver = solver,
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
        val solver = problem && mySolverSpec.value != null
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
