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

package ksl.service.capability.run

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.OutputConfig
import ksl.app.config.RVParameterOverride
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.toDesignedExperiment
import ksl.controls.ModelControlsExport
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationOutputConfig
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationType
import ksl.app.config.optimization.SolverSpec
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.validation.OptimizationConfigurationValidator
import ksl.app.validation.RunConfigurationValidator
import ksl.app.validation.ValidationResult
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.Factor
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import ksl.service.job.JobHandleView
import ksl.service.job.asJobView
import java.nio.file.Files

/**
 * One factor in a two-level factorial experiment: a display [name], the model
 * input [controlKey] it drives, and the [low]/[high] levels.
 */
@Serializable
data class ExperimentFactorSpec(
    val name: String,
    val controlKey: String,
    val low: Double,
    val high: Double,
)

/**
 * The service-core surface for *executing* a bundled model — capability A's live
 * path (strategic plan §5; the run-capability submit path that the DTO mapping
 * was built for). It owns a [KSLAppSession] over a model provider and turns a
 * `(modelId, optional overrides)` request into a submitted single run, returned
 * as a capability-agnostic [JobHandleView] so it flows through the same
 * `JobManager` and transport layer as any other job.
 *
 * Build it from a [BundleRegistry] via [fromRegistry]; the provider resolves a
 * model by its (bundle-flattened) id.
 */
class RunService(
    modelProvider: ModelProviderIfc,
    scope: CoroutineScope? = null,
    private val runDeadline: kotlin.time.Duration? = null,
) : AutoCloseable {

    // When a deadline is configured, stamp it as the per-replication cap on every
    // model this service builds — the single, builder-independent enforcement
    // point shared by the run, experiment, and optimization paths. The watchdog
    // (guardWithDeadline) owns the job-level decision; this cap guarantees each
    // replication yields control so that decision can be honored.
    private val provider: ModelProviderIfc =
        if (runDeadline != null) DeadlineModelProvider(modelProvider, runDeadline) else modelProvider

    private val session = KSLAppSession(provider, scope)

    // Scope for the job-deadline watchdogs (A5). Each guarded run gets a racing
    // coroutine that cancels the whole job if it outlives the configured deadline.
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Builds the [RunConfiguration] document a [submitSingle] call would run, from
     * flattened overrides. Exposed so a caller can content-key the document
     * (`ResultKeys.forRunConfig`) and run it through [submitRunConfig] — making a
     * flattened single run share the cache with the equivalent document run.
     */
    fun singleRunConfig(
        modelId: String,
        numberOfReplications: Int? = null,
        lengthOfReplication: Double? = null,
        controlOverrides: ModelControlsExport = ModelControlsExport(modelName = ""),
        rvOverrides: List<RVParameterOverride> = emptyList(),
        streamAdvances: Int? = null,
        antithetic: Boolean? = null,
        enableKSLDatabase: Boolean = false,
    ): RunConfiguration {
        // A 0 advance is folded to null so a "replicationSet = 0" request stays
        // byte-identical to a plain run (same encoded document → same cache key →
        // same incremental-reuse behavior). Only a positive advance / an explicit
        // antithetic flag perturbs the streams.
        val advances = streamAdvances?.takeIf { it > 0 }
        val overrides =
            if (numberOfReplications != null || lengthOfReplication != null || advances != null || antithetic != null) {
                ExperimentRunOverrides(
                    numberOfReplications = numberOfReplications,
                    lengthOfReplication = lengthOfReplication,
                    numberOfStreamAdvancesPriorToRunning = advances,
                    antitheticOption = antithetic,
                )
            } else null

        val scenario = ScenarioSpec(
            name = modelId,
            modelReference = ModelReference.ByProviderId(modelId),
            runOverrides = overrides,
            controlOverrides = controlOverrides,
            rvOverrides = rvOverrides,
        )
        return RunConfiguration(
            scenarios = listOf(scenario),
            // Headless: no report files; the structured result is returned inline
            // (strategic plan §9.1). The KSL database is an opt-in artifact for the db_* tools.
            outputConfig = OutputConfig(reports = emptySet(), enableKSLDatabase = enableKSLDatabase),
        )
    }

    /**
     * Submits a single run of [modelId], optionally overriding the replication
     * count, run length, numeric controls, and RV parameters (any null/empty
     * leaves the model's own default). [controlOverrides] and [rvOverrides] are
     * typically produced by [RunInputs.bind] from an agent's input map.
     */
    fun submitSingle(
        modelId: String,
        numberOfReplications: Int? = null,
        lengthOfReplication: Double? = null,
        controlOverrides: ModelControlsExport = ModelControlsExport(modelName = ""),
        rvOverrides: List<RVParameterOverride> = emptyList(),
    ): JobHandleView<RunEvent, RunResult> =
        submitRunConfig(singleRunConfig(modelId, numberOfReplications, lengthOfReplication, controlOverrides, rvOverrides))

    /**
     * Submits a simulation-optimization run: minimize (or maximize) a model
     * response over a set of numeric decision variables, using a stochastic
     * hill-climbing solver. Returns a [JobHandleView] whose terminal result is a
     * `RunResult.OptimizationCompleted` (best solution + iteration history).
     *
     * @param objectiveResponse the model response to optimize
     * @param inputs the decision variables (control key, bounds, granularity)
     * @param maxIterations solver iteration budget
     * @param replicationsPerEvaluation replications per candidate evaluation
     * @param maximize maximize the objective instead of minimizing
     */
    fun submitOptimization(
        modelId: String,
        objectiveResponse: String,
        inputs: List<OptimizationInputSpec>,
        maxIterations: Int,
        replicationsPerEvaluation: Int,
        maximize: Boolean = false,
    ): JobHandleView<RunEvent, RunResult> =
        submitOptimizationConfig(
            optimizationRunConfig(modelId, objectiveResponse, inputs, maxIterations, replicationsPerEvaluation, maximize),
        )

    /**
     * Builds the [OptimizationRunConfiguration] document a [submitOptimization]
     * call would run, from flattened arguments. Exposed so a flattened
     * optimization can content-key the document (`ResultKeys.forOptimizationConfig`)
     * and run it through [submitOptimizationConfig], sharing the cache with the
     * equivalent document run.
     */
    fun optimizationRunConfig(
        modelId: String,
        objectiveResponse: String,
        inputs: List<OptimizationInputSpec>,
        maxIterations: Int,
        replicationsPerEvaluation: Int,
        maximize: Boolean = false,
    ): OptimizationRunConfiguration {
        // Build the model once to read its run parameters for the optimization template.
        val runParameters = provider.provideModel(modelId).extractRunParameters()
        val template = ModelRunTemplate(
            modelReference = ModelReference.ByProviderId(modelId),
            runParameters = runParameters,
        )
        val problem = OptimizationProblemSpec(
            modelIdentifier = modelId,
            objectiveResponseName = objectiveResponse,
            inputs = inputs,
            optimizationType = if (maximize) OptimizationType.MAXIMIZE else OptimizationType.MINIMIZE,
        )
        val solver = SolverSpec.StochasticHillClimbing(
            maxIterations = maxIterations,
            replicationsPerEvaluation = replicationsPerEvaluation,
        )
        return OptimizationRunConfiguration(
            output = OptimizationOutputConfig(analysisName = "opt-$modelId"),
            model = template,
            problem = problem,
            solver = solver,
        )
    }

    /**
     * Submits a two-level factorial designed experiment over [factors] (each
     * binding a model input key to low/high levels). Returns a [JobHandleView]
     * whose terminal result is a `RunResult.BatchCompleted` — one snapshot per
     * design point.
     *
     * Uses the concurrent [ParallelDesignedExperiment] (a fresh model per design
     * point): the orchestrator captures each design point's across-replication
     * snapshot in memory (the result does not depend on the experiment's internal
     * database, which is suppressed). The parallel path also honors cooperative
     * cancellation between design points and replications, so the server's job
     * deadline ([guardWithDeadline]) can actually stop a runaway experiment — the
     * sequential `DesignedExperiment` is a tight blocking loop and cannot.
     */
    fun submitExperiment(
        modelId: String,
        factors: List<ExperimentFactorSpec>,
        numRepsPerDesignPoint: Int? = null,
    ): JobHandleView<RunEvent, RunResult> {
        require(factors.size >= 2) { "a two-level factorial experiment needs at least two factors" }
        // The same TwoLevelFactor instances key both the design and the binding map.
        val twoLevelFactors = factors.map { TwoLevelFactor(it.name, low = it.low, high = it.high) }
        val design = TwoLevelFactorialDesign(twoLevelFactors.toSet())
        val factorSettings: Map<Factor, String> =
            twoLevelFactors.mapIndexed { i, factor -> factor to factors[i].controlKey }.toMap()

        // Build a fresh model per design point. The provider stamps the job
        // deadline as each model's per-replication cap (the granularity backstop).
        val builder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?,
            ): Model = provider.provideModel(modelId, modelConfiguration, experimentRunParameters)
        }
        val workspace = Files.createTempDirectory("ksl-experiment")
        val experiment = ParallelDesignedExperiment(
            name = "exp-$modelId",
            modelBuilder = builder,
            factorSettings = factorSettings,
            design = design,
            pathToOutputDirectory = workspace,
            // Headless server: the result is built from in-memory snapshots, so
            // suppress the experiment's incidental SQLite database side output.
            kslDb = null,
            useDesignPointOutputDirs = false,
        )
        // RunSpec.Experiment's workload is the experiment object; the config is
        // metadata only (the orchestrator empties its scenarios).
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(name = modelId, modelReference = ModelReference.ByProviderId(modelId)),
            ),
            outputConfig = OutputConfig(reports = emptySet()),
        )
        val handle = session.submit(RunSpec.Experiment(config, experiment, numRepsPerDesignPoint))
            .asJobView().guardWithDeadline()
        // Reclaim the temp workspace once the experiment finishes (any outcome).
        handle.result.invokeOnCompletion { runCatching { workspace.toFile().deleteRecursively() } }
        return handle
    }

    // ----- document submission (the document-centric path, Phase 8.2) -----

    /** Validates a full run document against the provider; `isValid` gates submission. */
    fun validateRunConfig(config: RunConfiguration): ValidationResult =
        RunConfigurationValidator.validateForRun(config, provider)

    /** Validates a full optimization document against the provider. */
    fun validateOptimizationConfig(config: OptimizationRunConfiguration): ValidationResult =
        OptimizationConfigurationValidator.validateForRun(config, provider)

    /**
     * Submits a complete [RunConfiguration] document as authored (one scenario →
     * a single run, many → a scenario batch). The substrate already accepts this
     * document directly; this is the document-centric counterpart to the
     * flattened [submitSingle].
     */
    fun submitRunConfig(config: RunConfiguration): JobHandleView<RunEvent, RunResult> {
        // The per-replication cap is stamped on the model by the provider
        // (DeadlineModelProvider); the watchdog below owns the job-level decision.
        val spec = if (config.scenarios.size > 1) RunSpec.Scenarios(config) else RunSpec.Single(config)
        return session.submit(spec).asJobView().guardWithDeadline()
    }

    /**
     * Arms the job's wall-clock deadline on [this] handle when the server
     * configures one ([runDeadline]). The deadline is a property of the whole
     * job (an experiment = a set of replications): a watchdog races the job's
     * result and, if the deadline elapses first, cancels the run, which the run
     * loop turns into a `RunResult.Cancelled` at its next replication boundary
     * (`Runner.ensureActive`). The companion per-replication cap (stamped by
     * [DeadlineModelProvider]) is the enforcement backstop — it bounds how long a
     * single replication can withhold that boundary, so the job-level cancellation is
     * honored even when one replication would otherwise never return. The
     * watchdog stops itself the moment the job completes on its own — and because
     * cancellation surfaces as `Cancelled` (not `Completed`), a timed-out job is
     * never mistaken for a clean result.
     */
    private fun JobHandleView<RunEvent, RunResult>.guardWithDeadline(): JobHandleView<RunEvent, RunResult> {
        val deadline = runDeadline ?: return this
        val handle = this
        watchdogScope.launch {
            // withTimeoutOrNull returns null iff the deadline elapsed before the result arrived.
            val finished = withTimeoutOrNull(deadline) { handle.result.await(); true }
            if (finished == null) handle.cancel("exceeded the server job deadline of $deadline")
        }
        return this
    }

    /**
     * Submits a complete [OptimizationRunConfiguration] document as authored and
     * arms the job-deadline watchdog (A5). The solver honors cancellation between
     * iterations (`onCancelHook -> stopIterations`), so a long-running
     * optimization is stopped at the deadline and reported `Cancelled` — overshoot
     * is bounded by the iteration in flight.
     *
     * The model the evaluator runs is built through this service's provider, so it
     * carries the per-replication cap ([DeadlineModelProvider]) — stamped
     * post-build, independent of whether the model's builder honors
     * `experimentRunParameters`. That bounds even a single never-returning
     * evaluation replication, while the watchdog bounds the job between iterations.
     */
    fun submitOptimizationConfig(config: OptimizationRunConfiguration): JobHandleView<RunEvent, RunResult> =
        session.submit(RunSpec.Optimization(config)).asJobView().guardWithDeadline()

    /**
     * Submits a complete [ExperimentConfiguration] document (the factor-centric
     * counterpart to the flattened [submitExperiment]). It is the "hosting
     * controller" the substrate's `toDesignedExperiment` anticipates: resolves the
     * document's `modelReference` to a model builder over the provider, gives the
     * experiment a workspace, materializes the runnable `DesignedExperimentIfc`,
     * and submits it as a `RunSpec.Experiment` — so its terminal result is the
     * same `RunResult.BatchCompleted` (one snapshot per design point) the
     * flattened path produces. Only `ModelReference.ByProviderId` is supported.
     */
    fun submitExperimentConfig(config: ExperimentConfiguration): JobHandleView<RunEvent, RunResult> {
        val modelId = (config.modelReference as? ModelReference.ByProviderId)?.providerId
            ?: throw IllegalArgumentException("experiment documents must use modelReference type 'byProviderId'")
        // Adapt the provider into the factory the design needs: CONCURRENT builds
        // a fresh model per design point, SEQUENTIAL builds once.
        val builder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?,
            ): Model = provider.provideModel(modelId, modelConfiguration, experimentRunParameters)
        }
        val workspace = Files.createTempDirectory("ksl-experiment")
        val experiment = config.toDesignedExperiment(builder, workspace)
        // RunSpec.Experiment's workload is the experiment object; the config is
        // metadata only (the orchestrator empties its scenarios).
        val metadata = RunConfiguration(
            scenarios = listOf(ScenarioSpec(name = modelId, modelReference = ModelReference.ByProviderId(modelId))),
            outputConfig = OutputConfig(reports = emptySet()),
        )
        val handle = session.submit(RunSpec.Experiment(metadata, experiment)).asJobView().guardWithDeadline()
        // Reclaim the experiment's temp workspace once it finishes (success, failure,
        // or cancellation). The result is built from in-memory snapshots and the DB
        // is off, so nothing needs the directory afterward — leaving it would leak a
        // temp dir per experiment.
        handle.result.invokeOnCompletion { runCatching { workspace.toFile().deleteRecursively() } }
        return handle
    }

    override fun close() {
        watchdogScope.cancel()
        session.close()
    }

    companion object {
        /**
         * Substream advances for independent run [replicationSet] given the run's
         * [effectiveReplications]. Each set occupies a non-overlapping block of
         * substreams (`k × reps`), so distinct sets are independent yet reproducible;
         * set 0 → 0 (the canonical run). One replication consumes one substream per
         * stream, so a block of `reps` substreams exactly covers a run — a bare
         * advance of 1 would overlap `reps − 1` substreams and be correlated, not
         * independent, which is why callers pass an intent-level set index here.
         */
        fun streamAdvancesFor(replicationSet: Int, effectiveReplications: Int): Int {
            require(replicationSet >= 0) { "replicationSet must be >= 0; got $replicationSet" }
            return replicationSet * effectiveReplications.coerceAtLeast(1)
        }

        /**
         * A [RunService] over [registry]'s bundles, via a [RegistryModelProvider]
         * that resolves against the registry's *current* set — so models added at
         * runtime (Phase 8.6) are runnable without rebuilding the session.
         */
        fun fromRegistry(
            registry: BundleRegistry,
            scope: CoroutineScope? = null,
            runDeadline: kotlin.time.Duration? = null,
        ): RunService =
            RunService(RegistryModelProvider(registry), scope, runDeadline)
    }
}
