package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.problem.StartingPointIfc
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * Represents an abstract base class for stochastic solvers.
 * This class provides foundational functionality for solvers
 * that utilize randomness during their optimization process.
 *
 * @constructor Creates a stochastic solver with the specified parameters.
 * @param problemDefinition the problem being solved
 * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
 * @param maxIterations The maximum number of iterations allowed for the solving process.
 * @param replicationsPerEvaluation Strategy to determine the number of replications to perform for each evaluation.
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name Optional name identifier for this instance of the solver.
 */
abstract class StochasticSolver(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    maxIterations: Int,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    streamNum: Int = 0,
    val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : Solver(problemDefinition, evaluator, maxIterations, replicationsPerEvaluation, name), RNStreamControlIfc {

    /**
     * rnStream provides a reference to the underlying stream of random numbers
     */
    val rnStream: RNStreamIfc = streamProvider.rnStream(streamNum)

    val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }

    /**
     *  Can be supplied to provide a method for specifying a feasible starting point.
     *  The default is to randomly generate a starting point
     */
    var startingPointGenerator: StartingPointIfc? = null

    /**
     *  The default implementation will produce an input-feasible
     *  starting point by acceptance sampling of the feasible region
     *  using the problem definition.
     */
    override fun startingPoint(): InputMap {
        return startingPointGenerator?.startingPoint(problemDefinition) ?: problemDefinition.startingPoint(rnStream)
    }

    /**
     *  The default implementation will produce an input-range feasible
     *  point. The point might not be feasible with respect to deterministic
     *  constraints. By default, the next point is generated using the
     *  [generateNeighbor()] function
     */
    override fun nextPoint(): InputMap {
        return generateNeighbor(currentPoint, rnStream)
    }


}