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

    val solutionEqualityChecker: SolutionEqualityIfc = InputsAndConfidenceIntervalEquality()

    /**
     *  Used to check if the last set of solutions that were captured
     *  are the same.
     */
    val solutionChecker: SolutionChecker = SolutionChecker(solutionEqualityChecker,
        defaultNoImproveThresholdForSHCWithRS)

    /**
     *  The default implementation ensures that the initial point and solution
     *  are input-feasible (feasible with respect to input ranges and deterministic constraints).
     */
    override fun initializeIterations() {
        //super.initializeIterations()
        numOracleCalls = 0
        numReplicationsRequested = 0
        solutionChecker.clear()
    }

    override fun mainIteration() {
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
        // update the current solution if the new solution is better
        currentSolution = minimumSolution(bestSolution, currentSolution)
        logger.info { "Current best: ${currentSolution.asString()}" }
        // capture the last solution
        solutionChecker.captureSolution(currentSolution)
        // clear the evaluator cache between randomized runs, but allow caching during the run itself
        evaluator.cache?.clear()
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: solutionChecker.checkSolutions()
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

        /**
         * This value is used as the default termination threshold for the largest number of iterations, during which no
         * improvement of the best function value is found. By default, set to 3.
         */
        @JvmStatic
        var defaultNoImproveThresholdForSHCWithRS: Int = 3
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }
    }
}