package ksl.simopt.solvers.algorithms

import ksl.simopt.solvers.Solver
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * A class that implements the Random Restart optimization algorithm.
 * This algorithm repeatedly runs the solver with a different starting point
 * until it finds a solution.
 * @param solver The solver to be used for the randomized restarts.
 * @param maxNumRestarts The maximum number of restarts to be performed.
 * @param streamNum The random number stream number to be used for this solver.
 * @param streamProvider The random number stream provider to be used for this solver.
 * @param name Optional name identifier for this instance of the solver.
 */
class RandomRestartSolver(
    val solver: Solver,
    maxNumRestarts: Int = defaultMaxRestarts,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : StochasticSolver(
    solver.problemDefinition, solver.evaluator, maxNumRestarts,
    solver.replicationsPerEvaluation, streamNum, streamProvider, name
) {

    override fun mainIteration() {
        // randomly assign a new starting point
        val startPoint = startingPoint()
        solver.startingPoint = startPoint
        logger.info { "Starting a new randomized run at point: ${startPoint.inputValues.joinToString()}" }
        // run the solver until it finds a solution
        solver.runAllIterations()
        // get the best solution from the solver run
        val bestSolution = solver.bestSolution
        logger.info { "Best solution found from the solver run: ${bestSolution.asString()}" }
        // update the current solution if the new solution is better
        currentSolution = minimumSolution(bestSolution, currentSolution)
        logger.info { "Current best: ${currentSolution.asString()}" }
    }

    companion object {
        /**
         * Represents the default maximum number restarts to be executed
         * in a given process or algorithm.
         *
         * The default value is set to 10, but it can be modified based
         * on specific requirements or constraints.
         */
        @JvmStatic
        var defaultMaxRestarts = 10
            set(value) {
                require(value > 0) { "The default maximum number of restarts must be a positive value." }
                field = value
            }
    }
}