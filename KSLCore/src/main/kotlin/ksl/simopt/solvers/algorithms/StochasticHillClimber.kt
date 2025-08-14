package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * A class that implements the Stochastic Hill Climbing optimization algorithm.
 * This algorithm searches for an optimal solution by iteratively evaluating
 * and moving to neighborhood solutions, as determined by the evaluator and the
 * problem definition. It uses a stochastic approach to explore the solution space
 * by leveraging a random number stream.
 *
 * @constructor Creates a StochasticHillClimber instance with the provided evaluator,
 * maximum iterations, replications per evaluation strategy, and an optional random
 * number stream and name.
 *
 * @param evaluator An evaluator object that provides the problem definition and
 * performs solution evaluation.
 * @param maxIterations The maximum number of iterations the algorithm is allowed
 * to execute.
 * @param replicationsPerEvaluation An instance of `ReplicationPerEvaluationIfc`
 * defining the strategy for determining the number of replications per evaluation.
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name An optional name for this solver instance.
 */
open class StochasticHillClimber @JvmOverloads constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : StochasticSolver(problemDefinition, evaluator, maxIterations, replicationsPerEvaluation, streamNum, streamProvider, name) {

    /**
     * Constructs an instance of StochasticHillClimber with specified parameters.
     *
     * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
     * @param maxIterations The maximum number of iterations allowed for the hill climbing process.
     * @param replicationsPerEvaluation The number of replications to perform for each evaluation of a solution.
     * @param streamNum the random number stream number, defaults to 0, which means the next stream
     * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
     * @param name Optional name identifier for this instance of StochasticHillClimber.
     */
    @JvmOverloads
    constructor(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        maxIterations: Int = defaultMaxNumberIterations,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(problemDefinition, evaluator, maxIterations, FixedReplicationsPerEvaluation(replicationsPerEvaluation),
        streamNum, streamProvider, name)

    /**  Randomly generates the next point using nextPoint().
     *   Evaluates the point and gets the solution.
     *   If the solution is better than the current solution, it becomes the current solution.
     *
     */
    override fun mainIteration() {
        // generate a random neighbor of the current solution
        val nextPoint = nextPoint()
        // evaluate the solution
        val nextSolution = requestEvaluation(nextPoint)
        if (compare(nextSolution, currentSolution) < 0) {
            currentSolution = nextSolution
        }
    }
}