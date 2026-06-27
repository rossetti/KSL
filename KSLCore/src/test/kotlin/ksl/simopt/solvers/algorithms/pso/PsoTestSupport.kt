package ksl.simopt.solvers.algorithms.pso

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition

/**
 *  Shared test fixtures for the particle swarm solver and its strategy components. A deterministic,
 *  in-memory evaluator computes a closed-form objective directly from the input values, so the tests
 *  need no simulation infrastructure.
 */
internal object PsoTestSupport {

    /**
     *  A box-constrained minimization problem over [lb, ub]^[dim] with continuous inputs named
     *  x1..x[dim]. The objective response is named "y". Continuous inputs (granularity 0) ensure an
     *  unbounded supply of distinct feasible points for swarm seeding.
     */
    fun boxProblem(dim: Int = 2, lb: Double = -10.0, ub: Double = 10.0): ProblemDefinition {
        val names = (1..dim).map { "x$it" }
        val pd = ProblemDefinition(
            problemName = "PSOTestProblem",
            modelIdentifier = "PSOTestModel",
            objFnResponseName = "y",
            inputNames = names
        )
        names.forEach { pd.inputVariable(it, lowerBound = lb, upperBound = ub) }
        return pd
    }

    /**
     *  A deterministic [EvaluatorIfc] that evaluates each requested design point with the supplied
     *  [objective] (smaller is better). The reported variance is a positive synthetic constant so
     *  that confidence-interval-based convergence checks have valid degrees of freedom.
     */
    class FunctionEvaluator(
        val problemDefinition: ProblemDefinition,
        val objective: (DoubleArray) -> Double
    ) : EvaluatorIfc {

        private var calls = 0
        private var designPoints = 0
        private var oracleReps = 0

        override val totalEvaluatorCalls: Int get() = calls
        override val totalDesignPointsEvaluated: Int get() = designPoints
        override val totalOracleReplications: Int get() = oracleReps
        override val totalCachedReplications: Int get() = 0
        override val cache: SolutionCacheIfc? get() = null

        override fun evaluate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
            calls++
            val result = LinkedHashMap<ModelInputs, Solution>()
            for (mi in evaluationRequest.modelInputs) {
                designPoints++
                oracleReps += mi.numReplications
                val inputMap = problemDefinition.toInputMap(mi.inputs.toMutableMap())
                val fx = objective(inputMap.inputValues)
                val count = maxOf(mi.numReplications.toDouble(), 2.0)
                val est = EstimatedResponse(problemDefinition.objFnResponseName, fx, 1.0, count)
                result[mi] = Solution(inputMap, est, emptyList(), calls)
            }
            return result
        }
    }

    /**
     *  Builds a [ParticleSwarmSolver] backed by a deterministic [FunctionEvaluator].
     */
    fun makeSolver(
        problemDefinition: ProblemDefinition,
        objective: (DoubleArray) -> Double,
        streamNum: Int = 1,
        swarmSize: Int = 20,
        maxIterations: Int = 100,
        replicationsPerEvaluation: Int = 1,
        inertiaSchedule: InertiaWeightScheduleIfc = LinearDecreasingInertia(horizon = maxIterations),
        boundaryHandler: BoundaryHandlerIfc = ClampToBounds(),
        velocityInitializer: VelocityInitializerIfc = ZeroVelocity()
    ): ParticleSwarmSolver {
        val evaluator = FunctionEvaluator(problemDefinition, objective)
        return ParticleSwarmSolver(
            problemDefinition = problemDefinition,
            evaluator = evaluator,
            streamNum = streamNum,
            swarmSize = swarmSize,
            inertiaSchedule = inertiaSchedule,
            boundaryHandler = boundaryHandler,
            velocityInitializer = velocityInitializer,
            maxIterations = maxIterations,
            replicationsPerEvaluation = replicationsPerEvaluation
        )
    }

    /** A sphere objective with optimum at [target] (minimum value 0). */
    fun sphere(target: DoubleArray): (DoubleArray) -> Double = { x ->
        var s = 0.0
        for (i in x.indices) {
            val d = x[i] - target[i]
            s += d * d
        }
        s
    }
}
