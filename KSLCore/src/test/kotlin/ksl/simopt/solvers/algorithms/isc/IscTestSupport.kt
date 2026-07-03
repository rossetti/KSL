package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition

/**
 *  Shared fixtures for the Industrial Strength COMPASS (ISC) component tests. These build small
 *  integer- or continuous-input [ProblemDefinition]s and a few hand-checkable geometry helpers so the
 *  geometry, sampling, and redundancy tests stay independent of the simulation infrastructure.
 */
internal object IscTestSupport {

    /**
     *  A box-constrained problem over `[lb, ub]^dim` with inputs named x1..x[dim]. The inputs are
     *  integer-ordered when [granularity] is positive, continuous when it is 0.0.
     */
    fun boxProblem(
        dim: Int = 2,
        lb: Double = 0.0,
        ub: Double = 10.0,
        granularity: Double = 1.0
    ): ProblemDefinition {
        val names = (1..dim).map { "x$it" }
        val pd = ProblemDefinition(
            problemName = "ISCTestProblem",
            modelIdentifier = "ISCTestModel",
            objFnResponseName = "y",
            inputNames = names
        )
        names.forEach { pd.inputVariable(it, lowerBound = lb, upperBound = ub, granularity = granularity) }
        return pd
    }

    /** A solution carrying a given replication [count] at the given objective value [fx]. */
    fun solutionWith(pd: ProblemDefinition, x: DoubleArray, fx: Double, count: Double): Solution {
        val inputMap = pd.toInputMap(x)
        val est = EstimatedResponse(pd.objFnResponseName, fx, 1.0, count)
        return Solution(inputMap, est, emptyList(), 1)
    }

    /** Re-export to keep the inequality enum local to the test source for readability. */
    val LE: InequalityType = InequalityType.LESS_THAN
    val GE: InequalityType = InequalityType.GREATER_THAN

    /** A sphere objective (minimization) with optimum at [target] and minimum value 0. */
    fun sphere(target: DoubleArray): (DoubleArray) -> Double = { x ->
        var s = 0.0
        for (i in x.indices) {
            val d = x[i] - target[i]
            s += d * d
        }
        s
    }

    /**
     *  A deterministic in-memory [EvaluatorIfc]: each design point is scored with [objective]
     *  (smaller is better) and reported with a positive synthetic variance so confidence-interval and
     *  sequential-selection logic have valid degrees of freedom.
     */
    class FunctionEvaluator(
        val problemDefinition: ProblemDefinition,
        val objective: (DoubleArray) -> Double,
        val variance: Double = 1.0
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
                val est = EstimatedResponse(problemDefinition.objFnResponseName, fx, variance, count)
                result[mi] = Solution(inputMap, est, emptyList(), calls)
            }
            return result
        }
    }
}
