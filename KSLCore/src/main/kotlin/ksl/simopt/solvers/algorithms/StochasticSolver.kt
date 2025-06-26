package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * Represents an abstract base class for stochastic solvers.
 * This class provides foundational functionality for solvers
 * that utilize randomness during their optimization process.
 *
 * @constructor Creates a stochastic solver with the specified parameters.
 * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
 * @param maxIterations The maximum number of iterations allowed for the solving process.
 * @param replicationsPerEvaluation Strategy to determine the number of replications to perform for each evaluation.
 * @param rnStream The random number stream used during the solving process. Defaults to KSLRandom's default RNStream implementation.
 * @param name Optional name identifier for this instance of the solver.
 */
abstract class StochasticSolver(
    evaluator: EvaluatorIfc,
    maxIterations: Int,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    val rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
    name: String? = null
) : Solver(evaluator, maxIterations, replicationsPerEvaluation, name),
    RNStreamControlIfc by rnStream {

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
        maxIterations: Int,
        replicationsPerEvaluation: Int,
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
        name: String? = null
    ) : this(evaluator, maxIterations, FixedReplicationsPerEvaluation(replicationsPerEvaluation), rnStream, name)

    override fun startingPoint(): InputMap {
        return problemDefinition.startingPoint(rnStream)
    }

    override fun nextPoint(): InputMap {
        return generateNeighbor(currentPoint, rnStream)
    }

}