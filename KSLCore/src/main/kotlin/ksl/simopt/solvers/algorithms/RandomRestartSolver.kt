package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.InputsAndConfidenceIntervalEquality
import ksl.simopt.evaluator.SolutionChecker
import ksl.simopt.evaluator.SolutionEqualityIfc
import ksl.simopt.solvers.Solver
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom

/**
 * A class that implements the Random Restart optimization algorithm.
 * This algorithm repeatedly runs the solver with a different starting point
 * until it finds a solution.
 * @param restartingSolver The solver to be used for the randomized restarts.
 * @param maxNumRestarts The maximum number of restarts to be performed.
 * @param streamNum The random number stream number to be used for this solver.
 * @param streamProvider The random number stream provider to be used for this solver.
 * @param name Optional name identifier for this instance of the solver.
 */
class RandomRestartSolver(
    val restartingSolver: Solver,
    maxNumRestarts: Int = defaultMaxRestarts,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : StochasticSolver(
    restartingSolver.problemDefinition, restartingSolver.evaluator, maxNumRestarts,
    restartingSolver.replicationsPerEvaluation, streamNum, streamProvider, name
) {

    override fun mainIteration() {
        // clear the evaluator cache between randomized runs, but allow caching during the run itself
        evaluator.cache?.clear()
        // randomly assign a new starting point
        val startPoint = startingPoint()
        restartingSolver.startingPoint = startPoint
        logger.info { "Starting a new randomized run at point: ${startPoint.inputValues.joinToString()}" }
//        println("Starting a new randomized run at point: ${startPoint.inputValues.joinToString()}")
        // run the solver until it finds a solution
        restartingSolver.runAllIterations()
        numOracleCalls = numOracleCalls + restartingSolver.numOracleCalls
        numReplicationsRequested = numReplicationsRequested + restartingSolver.numReplicationsRequested
        // get the best solution from the solver run
        val bestSolution = restartingSolver.bestSolution
        logger.info { "Best solution found from the solver run: ${bestSolution.asString()}" }
//        println("Best solution found from the solver run: ${bestSolution.asString()}")
        currentSolution = bestSolution
//        // update the current solution if the new solution is better
//        if (compare(bestSolution, currentSolution) < 0) {
//            currentSolution = bestSolution
//        }
//        currentSolution = minimumSolution(bestSolution, currentSolution)
        logger.info { "Current best: ${currentSolution.asString()}" }
        // capture the last solution
//        solutionChecker.captureSolution(currentSolution)
    }

//    override fun isStoppingCriteriaSatisfied(): Boolean {
//        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: solutionChecker.checkSolutions()
//    }

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

        /**
         * This value is used as the default termination threshold for the largest number of iterations, during which no
         * improvement of the best function value is found. By default, set to 5.
         */
        @JvmStatic
        var defaultNoImproveThresholdForSHCWithRS: Int = 5
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }
    }
}