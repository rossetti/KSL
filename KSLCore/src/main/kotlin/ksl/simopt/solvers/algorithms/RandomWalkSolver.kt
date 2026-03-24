package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * A class that implements an unbiased Random Walk solver.
 * This algorithm unconditionally explores the solution space by randomly generating
 * and moving to a neighborhood solution at each iteration. It does not attempt to
 * optimize, but rather freely wanders the landscape. It is primarily useful for
 * landscape analysis, baseline benchmarking, or estimating hyperparameters
 * for other algorithms (like Simulated Annealing).
 */
class RandomWalkSolver(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    maxIterations: Int,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String = "RandomWalk"
) : StochasticSolver(problemDefinition, evaluator, maxIterations,
    replicationsPerEvaluation,
    streamNum, streamProvider, name
) {

    /**
     * Randomly generates the next point using nextPoint().
     * Evaluates the point and unconditionally accepts it as the current solution.
     */
    override fun mainIteration() {
        // Generate a random neighbor of the current solution
        val nextPoint = nextPoint()

        // Evaluate the solution
        val nextSolution = requestEvaluation(nextPoint)

        // UNCONDITIONAL ACCEPTANCE:
        // Unlike Hill Climbing or SA, we always move to the neighbor
        currentSolution = nextSolution
    }

}