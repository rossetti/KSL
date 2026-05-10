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

package ksl.app.config.optimization

import ksl.controls.experiments.ExperimentRunParameters
import ksl.simopt.cache.MemorySimulationRunCache
import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.problem.DynamicPolynomialPenalty
import ksl.simopt.problem.PenaltyFunctionIfc
import ksl.simopt.problem.PenaltyFunctionWithMemory
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.CENormalSampler
import ksl.simopt.solvers.algorithms.CESamplerIfc
import ksl.simopt.solvers.algorithms.CoolingScheduleIfc
import ksl.simopt.solvers.algorithms.ExponentialCoolingSchedule
import ksl.simopt.solvers.algorithms.LinearCoolingSchedule
import ksl.simopt.solvers.algorithms.LogarithmicCoolingSchedule
import ksl.simopt.solvers.algorithms.TemperatureConfiguration
import ksl.simulation.ModelProviderIfc
import ksl.simopt.problem.InequalityType as EngineInequalityType
import ksl.simopt.problem.OptimizationType as EngineOptimizationType

/**
 * Builds a runnable [Solver] from an [OptimizationRunConfiguration].
 *
 * `OptimizationSolverFactory` is the runtime translation layer from the
 * persisted, app-layer optimization spec to live engine objects.  It:
 *
 * 1. constructs a [ProblemDefinition] from
 *    [OptimizationRunConfiguration.problem], including penalty-function
 *    defaults and per-constraint overrides;
 * 2. wraps [OptimizationRunConfiguration.model] in a
 *    [ConfiguredModelBuilder] that applies the template's controls and
 *    RV overrides at evaluator-build time;
 * 3. creates a [SolutionCacheIfc] and (optional) [SimulationRunCacheIfc]
 *    from [OptimizationRunConfiguration.evaluation];
 * 4. dispatches on the [SolverSpec] sealed variant to the matching
 *    [Solver] companion-object factory method (with random-restart
 *    wrapping when [SolverSpec.randomRestart] is non-null);
 * 5. applies the cross-cutting [EvaluationSpec] settings on the returned
 *    solver instance.
 *
 * Validation should be performed by
 * [ksl.app.validation.OptimizationConfigurationValidator] *before* calling
 * [build]; this factory throws [IllegalArgumentException] /
 * [IllegalStateException] from the underlying engine APIs if the
 * configuration is internally inconsistent (e.g. unknown decision-variable
 * names against the model).  The engine itself runs
 * [ProblemDefinition.validateProblemDefinition] at evaluator-construction
 * time, so this factory does not duplicate that check.
 *
 * @property provider model provider required when
 *           [ksl.app.config.ModelReference.ByProviderId] is used; unused
 *           for [ksl.app.config.ModelReference.ByJar]
 */
class OptimizationSolverFactory(
    private val provider: ModelProviderIfc? = null
) {

    /**
     * Build a runnable [Solver] from [config].  See class KDoc for the
     * translation pipeline.
     */
    fun build(config: OptimizationRunConfiguration): Solver {
        val problem = buildProblemDefinition(config.problem)
        val builder = ConfiguredModelBuilder(config.model, provider)
        val solutionCache = makeSolutionCache(config.evaluation)
        val simulationRunCache = makeSimulationRunCache(config.evaluation)
        val solver = dispatch(
            spec               = config.solver,
            problem            = problem,
            builder            = builder,
            solutionCache      = solutionCache,
            simulationRunCache = simulationRunCache,
            templateRunParameters = config.model.runParameters
        )
        applyEvaluationSettings(solver, config.evaluation)
        return solver
    }

    // ── Problem definition ───────────────────────────────────────────────────

    private fun buildProblemDefinition(spec: OptimizationProblemSpec): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName       = spec.problemName,
            // engine modelIdentifier is non-null String; spec value is optional —
            // null spec values fall back to "" per the Step 6 plan
            modelIdentifier   = spec.modelIdentifier ?: "",
            objFnResponseName = spec.objectiveResponseName,
            inputNames        = spec.inputs.map { it.name },
            responseNames     = spec.responseNames,
            optimizationType  = spec.optimizationType.toEngine(),
            indifferenceZoneParameter = spec.indifferenceZoneParameter,
            objFnGranularity          = spec.objectiveGranularity
        )
        spec.inputs.forEach {
            pd.inputVariable(it.name, it.lowerBound, it.upperBound, it.granularity)
        }
        spec.linearConstraints.forEach {
            pd.linearConstraint(
                equation        = it.coefficients,
                rhsValue        = it.rhsValue,
                inequalityType  = it.inequalityType.toEngine(),
                penaltyFunction = it.penaltyFunction?.toEngine()
            )
        }
        spec.responseConstraints.forEach {
            pd.responseConstraint(
                name            = it.name,
                rhsValue        = it.rhsValue,
                inequalityType  = it.inequalityType.toEngine(),
                target          = it.target,
                tolerance       = it.tolerance,
                penaltyFunction = it.penaltyFunction?.toEngine()
            )
        }
        pd.defaultLinearPenalty   = spec.defaultLinearPenalty.toEngine()
        pd.defaultResponsePenalty = spec.defaultResponsePenalty.toEngine()
        return pd
    }

    // ── Cache wiring ────────────────────────────────────────────────────────

    private fun makeSolutionCache(eval: EvaluationSpec): SolutionCacheIfc {
        // The engine factories don't accept a null SolutionCacheIfc; a disabled
        // cache is implemented by toggling allowCacheLookups / allowCachePuts
        // off on a MemorySolutionCache.
        val cache = MemorySolutionCache()
        if (!eval.useSolutionCache) {
            cache.allowCacheLookups = false
            cache.allowCachePuts = false
        }
        return cache
    }

    private fun makeSimulationRunCache(eval: EvaluationSpec): SimulationRunCacheIfc? =
        if (eval.useSimulationRunCache) MemorySimulationRunCache() else null

    // ── Solver dispatch ──────────────────────────────────────────────────────

    private fun dispatch(
        spec: SolverSpec,
        problem: ProblemDefinition,
        builder: ConfiguredModelBuilder,
        solutionCache: SolutionCacheIfc,
        simulationRunCache: SimulationRunCacheIfc?,
        templateRunParameters: ExperimentRunParameters
    ): Solver {
        val starting: MutableMap<String, Double>? = spec.startingPoint?.toMutableMap()
        return when (spec) {
            is SolverSpec.StochasticHillClimbing ->
                if (spec.randomRestart == null)
                    Solver.createStochasticHillClimbingSolver(
                        problemDefinition         = problem,
                        modelBuilder              = builder,
                        startingPoint             = starting,
                        maxIterations             = spec.maxIterations,
                        replicationsPerEvaluation = spec.replicationsPerEvaluation,
                        solutionCache             = solutionCache,
                        simulationRunCache        = simulationRunCache,
                        experimentRunParameters   = templateRunParameters
                    )
                else
                    Solver.createRandomRestartStochasticHillClimbingSolver(
                        problemDefinition         = problem,
                        modelBuilder              = builder,
                        maxNumRestarts            = spec.randomRestart.maxNumRestarts,
                        startingPoint             = starting,
                        maxIterations             = spec.maxIterations,
                        replicationsPerEvaluation = spec.replicationsPerEvaluation,
                        solutionCache             = solutionCache,
                        simulationRunCache        = simulationRunCache,
                        experimentRunParameters   = templateRunParameters
                    )

            is SolverSpec.SimulatedAnnealing -> {
                val temp = spec.temperature.toEngine()
                val schedule = spec.coolingSchedule.toEngine()
                if (spec.randomRestart == null)
                    Solver.createSimulatedAnnealingSolver(
                        problemDefinition         = problem,
                        modelBuilder              = builder,
                        startingPoint             = starting,
                        temperatureConfiguration  = temp,
                        coolingSchedule           = schedule,
                        stoppingTemperature       = spec.stoppingTemperature,
                        maxIterations             = spec.maxIterations,
                        replicationsPerEvaluation = spec.replicationsPerEvaluation,
                        solutionCache             = solutionCache,
                        simulationRunCache        = simulationRunCache,
                        experimentRunParameters   = templateRunParameters
                    )
                else
                    Solver.createRandomRestartSimulatedAnnealingSolver(
                        problemDefinition         = problem,
                        modelBuilder              = builder,
                        maxNumRestarts            = spec.randomRestart.maxNumRestarts,
                        startingPoint             = starting,
                        temperatureConfiguration  = temp,
                        coolingSchedule           = schedule,
                        stoppingTemperature       = spec.stoppingTemperature,
                        maxIterations             = spec.maxIterations,
                        replicationsPerEvaluation = spec.replicationsPerEvaluation,
                        solutionCache             = solutionCache,
                        simulationRunCache        = simulationRunCache,
                        experimentRunParameters   = templateRunParameters
                    )
            }

            is SolverSpec.CrossEntropy -> {
                val sampler: CESamplerIfc = spec.sampler.toEngine(problem)
                val solver = if (spec.randomRestart == null)
                    Solver.createCrossEntropySolver(
                        problemDefinition         = problem,
                        modelBuilder              = builder,
                        ceSampler                 = sampler,
                        startingPoint             = starting,
                        maxIterations             = spec.maxIterations,
                        replicationsPerEvaluation = spec.replicationsPerEvaluation,
                        solutionCache             = solutionCache,
                        simulationRunCache        = simulationRunCache,
                        experimentRunParameters   = templateRunParameters
                    )
                else
                    Solver.createRandomRestartCrossEntropySolver(
                        problemDefinition         = problem,
                        modelBuilder              = builder,
                        maxNumRestarts            = spec.randomRestart.maxNumRestarts,
                        startingPoint             = starting,
                        ceSampler                 = sampler,
                        maxIterations             = spec.maxIterations,
                        replicationsPerEvaluation = spec.replicationsPerEvaluation,
                        solutionCache             = solutionCache,
                        simulationRunCache        = simulationRunCache,
                        experimentRunParameters   = templateRunParameters
                    )
                // CE-specific post-construction settings (defaults preserved when null)
                applyCrossEntropyExtras(solver, spec)
                solver
            }

            is SolverSpec.RSpline ->
                if (spec.randomRestart == null)
                    Solver.createRsplineSolver(
                        problemDefinition         = problem,
                        modelBuilder              = builder,
                        initialNumReps            = spec.initialNumReps,
                        sampleSizeGrowthRate      = spec.sampleSizeGrowthRate,
                        maxNumReplications        = spec.maxNumReplications,
                        startingPoint             = starting,
                        maxIterations             = spec.maxIterations,
                        solutionCache             = solutionCache,
                        simulationRunCache        = simulationRunCache,
                        experimentRunParameters   = templateRunParameters
                    )
                else
                    Solver.createRandomRestartRsplineSolver(
                        problemDefinition         = problem,
                        modelBuilder              = builder,
                        maxNumRestarts            = spec.randomRestart.maxNumRestarts,
                        startingPoint             = starting,
                        initialNumReps            = spec.initialNumReps,
                        sampleSizeGrowthRate      = spec.sampleSizeGrowthRate,
                        maxNumReplications        = spec.maxNumReplications,
                        maxIterations             = spec.maxIterations,
                        solutionCache             = solutionCache,
                        simulationRunCache        = simulationRunCache,
                        experimentRunParameters   = templateRunParameters
                    )
        }
    }

    private fun applyCrossEntropyExtras(
        solver: Solver,
        spec: SolverSpec.CrossEntropy
    ) {
        // The factory may return a RandomRestartSolver wrapping a CrossEntropySolver;
        // CE-specific settings live on the inner solver in that case.
        val ce: ksl.simopt.solvers.algorithms.CrossEntropySolver = when (solver) {
            is ksl.simopt.solvers.algorithms.CrossEntropySolver -> solver
            is ksl.simopt.solvers.algorithms.RandomRestartSolver ->
                solver.restartingSolver as ksl.simopt.solvers.algorithms.CrossEntropySolver
            else -> return
        }
        spec.elitePct?.let { ce.elitePct = it }
        spec.ceSampleSize?.let { ce.ceSampleSize = it }
    }

    // ── Apply cross-cutting EvaluationSpec settings ─────────────────────────

    private fun applyEvaluationSettings(solver: Solver, eval: EvaluationSpec) {
        // For RandomRestartSolver wrappers the outer solver carries these settings;
        // they are also on the inner solver via Solver base class for non-restart paths.
        solver.snapShotFrequency = eval.snapshotFrequency
        solver.ensureProblemFeasibleRequests = eval.ensureProblemFeasibleRequests
        eval.maxFeasibleSamplingIterations?.let { solver.maxFeasibleSamplingIterations = it }
        eval.solutionPrecision?.let { solver.solutionPrecision = it }
    }

    // ── Sub-spec → engine translation ────────────────────────────────────────

    private fun OptimizationType.toEngine(): EngineOptimizationType = when (this) {
        OptimizationType.MINIMIZE -> EngineOptimizationType.MINIMIZE
        OptimizationType.MAXIMIZE -> EngineOptimizationType.MAXIMIZE
    }

    private fun InequalityType.toEngine(): EngineInequalityType = when (this) {
        InequalityType.LESS_THAN    -> EngineInequalityType.LESS_THAN
        InequalityType.GREATER_THAN -> EngineInequalityType.GREATER_THAN
    }

    private fun TemperatureSpec.toEngine(): TemperatureConfiguration = when (this) {
        is TemperatureSpec.Fixed         -> TemperatureConfiguration.Fixed(temperature)
        is TemperatureSpec.AutoCalibrate -> TemperatureConfiguration.AutoCalibrate(targetProbability, sampleSize)
    }

    private fun CoolingScheduleSpec.toEngine(): CoolingScheduleIfc = when (this) {
        is CoolingScheduleSpec.Linear ->
            LinearCoolingSchedule(initialTemperature, stoppingTemperature, maxIterations)
        is CoolingScheduleSpec.Exponential ->
            ExponentialCoolingSchedule(initialTemperature, coolingRate)
        is CoolingScheduleSpec.Logarithmic ->
            LogarithmicCoolingSchedule(initialTemperature)
    }

    private fun CESamplerSpec.toEngine(problem: ProblemDefinition): CESamplerIfc = when (this) {
        is CESamplerSpec.Normal -> CENormalSampler(
            problemDefinition               = problem,
            meanSmoother                    = meanSmoother,
            sdSmoother                      = sdSmoother,
            coefficientOfVariationThreshold = coefficientOfVariationThreshold,
            streamNum                       = streamNum
        )
    }

    private fun PenaltyFunctionSpec.toEngine(): PenaltyFunctionIfc = when (this) {
        is PenaltyFunctionSpec.WithMemory ->
            PenaltyFunctionWithMemory(basePenalty, iterationExponent, violationExponent)
        is PenaltyFunctionSpec.DynamicPolynomial ->
            DynamicPolynomialPenalty(basePenalty, iterationExponent, violationExponent)
    }
}
