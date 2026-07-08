package ksl.simopt.solvers.algorithms.genetic

import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition

/**
 *  Shared test fixtures for the genetic algorithm solver and its operators. These helpers keep the
 *  operator and solver tests free of simulation infrastructure by supplying a deterministic,
 *  in-memory evaluator that simply computes a closed-form objective function on the input values.
 */
internal object GeneticTestSupport {

    /**
     *  A box-constrained minimization problem over [-10, 10]^[dim] with continuous inputs named
     *  x1..x[dim]. The objective response is named "y".
     */
    fun boxProblem(dim: Int = 2, lb: Double = -10.0, ub: Double = 10.0): ProblemDefinition {
        val names = (1..dim).map { "x$it" }
        val pd = ProblemDefinition(
            problemName = "GATestProblem",
            modelIdentifier = "GATestModel",
            objFnResponseName = "y",
            inputNames = names
        )
        names.forEach { pd.inputVariable(it, lowerBound = lb, upperBound = ub) }
        return pd
    }

    /**
     *  A deterministic [EvaluatorIfc] that evaluates each requested design point with the supplied
     *  [objective] function (smaller is better). It performs no simulation and uses no cache.
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
                // count must be >= 2 and the variance must be positive so that confidence-interval-based
                // convergence checks (e.g. InputsAndConfidenceIntervalEquality) have valid degrees of
                // freedom. The objective mean is deterministic; only the reported variance is synthetic.
                val count = maxOf(mi.numReplications.toDouble(), 2.0)
                val est = EstimatedResponse(problemDefinition.objFnResponseName, fx, 1.0, count)
                result[mi] = Solution(inputMap, est, emptyList(), calls)
            }
            return result
        }
    }

    /**
     *  Builds a [GeneticAlgorithmSolver] backed by a deterministic [FunctionEvaluator].
     */
    fun makeSolver(
        problemDefinition: ProblemDefinition,
        objective: (DoubleArray) -> Double,
        streamNum: Int = 1,
        populationSize: Int = 20,
        maxIterations: Int = 50,
        replicationsPerEvaluation: Int = 1,
        selectionOperator: SelectionOperatorIfc = TournamentSelection(),
        crossoverOperator: CrossoverOperatorIfc = BlendCrossover(),
        mutationOperator: MutationOperatorIfc = GaussianMutation(problemDefinition)
    ): GeneticAlgorithmSolver {
        val evaluator = FunctionEvaluator(problemDefinition, objective)
        return GeneticAlgorithmSolver(
            problemDefinition = problemDefinition,
            evaluator = evaluator,
            streamNum = streamNum,
            populationSize = populationSize,
            selectionOperator = selectionOperator,
            crossoverOperator = crossoverOperator,
            mutationOperator = mutationOperator,
            maximumIterations = maxIterations,
            replicationsPerEvaluation = replicationsPerEvaluation
        )
    }

    /**
     *  Builds a [Solution] for the supplied problem at the given input values with an explicitly
     *  assigned objective value (its penalized objective equals [fitness] since the test problems
     *  have no response constraints).
     */
    fun solutionAt(problemDefinition: ProblemDefinition, values: DoubleArray, fitness: Double): Solution {
        val inputMap = problemDefinition.toInputMap(values.copyOf())
        val est = EstimatedResponse(problemDefinition.objFnResponseName, fitness, 0.0, 2.0)
        return Solution(inputMap, est, emptyList(), 1)
    }

    /** A common sphere objective with optimum at [target] (minimum value 0). */
    fun sphere(target: DoubleArray): (DoubleArray) -> Double = { x ->
        var s = 0.0
        for (i in x.indices) {
            val d = x[i] - target[i]
            s += d * d
        }
        s
    }
}
