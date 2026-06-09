package ksl.simopt.evaluator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import ksl.controls.experiments.ConcurrentSimulationRunner
import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.SimulationDispatcher

/**
 * A `SimulationOracleIfc` that executes the points of an `EvaluationRequest` concurrently,
 * each on its own freshly built `Model`, honoring the request's common-random-numbers (CRN)
 * vs. independent stream choice through per-point run parameters.
 *
 * A single-point request has nothing to parallelize, so it is short-circuited to one reused
 * model (no build, no coroutine) via an embedded `SimulationProvider`. Point-by-point solvers
 * therefore pay no extra cost in parallel mode; only multi-point batches (e.g. a Cross-Entropy
 * population or an R-SPLINE simplex/neighborhood) fan out across models.
 *
 * The supplied `modelBuilder` MUST return an independent, freshly built `Model` on each call to
 * `build(...)` (a bundle-backed builder does). A builder that returns a shared or cached instance
 * is a data race under concurrent execution and must not be used here.
 *
 * Each point runs a full experiment on its own model, mirroring the proven
 * `ParallelDesignedExperiment` pattern; per-point conversion to a `ResponseMap` happens
 * sequentially on the calling thread after the parallel phase completes. Built models are kept
 * silent (no summary printing, no CSV) and never touch their (now lazy) `outputDirectory`, so a
 * long run neither interleaves stdout nor accumulates output-file handles.
 *
 * @param modelBuilder builds a fresh model per point; must yield independent instances
 * @param modelConfiguration opaque configuration forwarded to `modelBuilder.build(...)`
 * @param baseRunParameters baseline run parameters shared by all points; if null, taken from the template model
 * @param templateModel an already-built model to adopt as the template and single-point reuse model; if null, one is built
 * @param simulationRunCache optional cache used only by the single-point (sequential) path
 * @param shortCircuitSinglePoint when true (default), size-1 requests run on the reused model instead of the parallel path
 * @param dispatcher the bounded dispatcher governing worker concurrency; defaults to a dedicated view sized to the processors
 */
class ParallelSimulationProvider @JvmOverloads constructor(
    private val modelBuilder: ModelBuilderIfc,
    private val modelConfiguration: Map<String, String>? = null,
    baseRunParameters: ExperimentRunParametersIfc? = null,
    templateModel: Model? = null,
    simulationRunCache: SimulationRunCacheIfc? = null,
    private val shortCircuitSinglePoint: Boolean = true,
    private val dispatcher: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(SimulationDispatcher.availableProcessors),
) : SimulationOracleIfc {

    // Provides the model identifier (request validation) and baseline run parameters, and serves
    // as the single reused model for the short-circuit. A caller that already built a model
    // (e.g. the validation model in Evaluator.createProblemEvaluator) can pass it in to avoid a
    // second build.
    private val myTemplateModel: Model =
        (templateModel ?: modelBuilder.build(modelConfiguration, baseRunParameters)).also { silence(it) }

    /** The identifier of the model produced by the builder, used to validate incoming requests. */
    val modelIdentifier: String = myTemplateModel.modelIdentifier

    private val myBaseRunParameters: ExperimentRunParameters = myTemplateModel.extractRunParameters()

    // Reused, single-threaded oracle for the single-point short-circuit. Runs on the calling
    // thread against the one template model, exactly like the sequential SimulationProvider.
    private val mySequentialDelegate = SimulationProvider(myTemplateModel, simulationRunCache)

    override fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        require(modelIdentifier == evaluationRequest.modelIdentifier) {
            "The model identifier from the request must match the provider's model identifier."
        }
        if (shortCircuitSinglePoint && evaluationRequest.modelInputs.size == 1) {
            // Nothing to parallelize: run on the reused model on the calling thread.
            return mySequentialDelegate.simulate(evaluationRequest)
        }
        return runParallel(evaluationRequest)
    }

    private fun runParallel(request: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        val plans = buildPlans(request.modelInputs, request.crnOption)
        // Phase A — parallel. runBlocking is confined to this synchronous boundary; the solver
        // call chain remains non-suspending. coroutineScope gives structured cancellation.
        val outcomes: List<Pair<ModelInputs, Result<SimulationRun>>> = runBlocking {
            coroutineScope {
                plans.map { plan -> async(dispatcher) { runOnePoint(plan) } }.awaitAll()
            }
        }
        // Phase B — sequential on the calling thread: convert each SimulationRun to a ResponseMap.
        return outcomes.associate { (modelInputs, runResult) ->
            modelInputs to runResult.fold(
                onSuccess = { simulationRun ->
                    SimulationProvider.captureResultFromSimulationRun(modelInputs, simulationRun)
                },
                onFailure = { e -> Result.failure(e) }
            )
        }
    }

    private fun buildPlans(inputs: List<ModelInputs>, crnOption: Boolean): List<PointPlan> {
        var nextAdvance = 0
        return inputs.mapIndexed { index, modelInputs ->
            // CRN: every point starts from the same stream block (advance 0).
            // Independent: cumulative replication-count advances => non-overlapping stream blocks.
            val advances = if (crnOption) {
                0
            } else {
                nextAdvance.also { nextAdvance += modelInputs.numReplications }
            }
            val runParameters = myBaseRunParameters.copy(
                experimentName = "${modelInputs.modelIdentifier}_DP_${index}_${modelInputs.requestTime}",
                numberOfReplications = modelInputs.numReplications,
                numberOfStreamAdvancesPriorToRunning = advances,
                resetStartStreamOption = true
            )
            PointPlan(modelInputs, runParameters)
        }
    }

    private suspend fun runOnePoint(plan: PointPlan): Pair<ModelInputs, Result<SimulationRun>> {
        return try {
            val model = modelBuilder.build(modelConfiguration)
            silence(model)
            val simulationRun = ConcurrentSimulationRunner(model).simulate(
                modelIdentifier = plan.modelInputs.modelIdentifier,
                inputs = plan.modelInputs.inputs,
                experimentRunParameters = plan.runParameters
            )
            plan.modelInputs to Result.success(simulationRun)
        } catch (e: CancellationException) {
            throw e   // cooperative cancellation: let coroutineScope cancel siblings
        } catch (e: RuntimeException) {
            plan.modelInputs to Result.failure(e)   // per-point failure; siblings unaffected
        }
    }

    private data class PointPlan(
        val modelInputs: ModelInputs,
        val runParameters: ExperimentRunParameters
    )

    private companion object {
        /**
         * Suppresses all reporting on a model so concurrent runs stay silent and never trigger the
         * (lazy) output directory: no summary print, and no CSV reporting of any kind.
         */
        fun silence(model: Model) {
            model.autoPrintSummaryReport = false
            model.autoCSVReports = false
            model.autoReplicationCSVReports = false
            model.autoExperimentCSVReports = false
        }
    }
}
