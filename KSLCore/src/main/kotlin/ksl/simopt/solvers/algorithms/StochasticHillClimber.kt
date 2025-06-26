package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.utilities.random.rng.RNStreamIfc
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
 * @param rnStream An optional random number stream used for stochastic behavior.
 * Defaults to `KSLRandom.defaultRNStream()`.
 * @param name An optional name for this solver instance.
 */
open class StochasticHillClimber(
    evaluator: EvaluatorIfc,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
    name: String? = null
) : StochasticSolver(evaluator, maxIterations, replicationsPerEvaluation, rnStream, name) {

    /**
     * Constructs an instance of StochasticHillClimber with specified parameters.
     *
     * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
     * @param maxIterations The maximum number of iterations allowed for the hill climbing process.
     * @param replicationsPerEvaluation The number of replications to perform for each evaluation of a solution.
     * @param rnStream The random number stream used during the hill climbing process. Defaults to KSLRandom's default RNStream implementation.
     * @param name Optional name identifier for this instance of StochasticHillClimber.
     */
    constructor(
        evaluator: EvaluatorIfc,
        maxIterations: Int = defaultMaxNumberIterations,
        replicationsPerEvaluation: Int,
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
        name: String? = null
    ) : this(evaluator, maxIterations, FixedReplicationsPerEvaluation(replicationsPerEvaluation), rnStream, name)

}