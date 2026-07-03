package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.bo.ExpectedImprovement
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker

/**
 *  Demonstrates the Bayesian optimization (BO) solver on the (R,Q) inventory problem. BO fits a
 *  Gaussian-process surrogate to the observed cost estimates and uses an acquisition function to pick
 *  the next point, so it is deliberately frugal with simulation runs — note the small initial design
 *  and the modest iteration budget compared with the population-based solvers.
 *
 *  The acquisition function here is [ExpectedImprovement]; swap in
 *  `ksl.simopt.solvers.algorithms.bo.LowerConfidenceBound(beta = 2.0)` to favor more exploration.
 *  The default surrogate uses fixed kernel hyperparameters; construct the solver directly with an
 *  `MleHyperparameterFitter` to re-fit the hyperparameters by maximum likelihood each iteration.
 */
fun main() {

    val modelIdentifier = "RQInventoryModel"
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val solver = Solver.createBayesianOptimizationSolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        initialDesignSize = 8,
        acquisition = ExpectedImprovement(),
        startingPoint = null,
        maxIterations = 25,
        replicationsPerEvaluation = 30,
    )
    val tracker = ConsoleSolverStateTracker(solver)
    tracker.startTracking()
    solver.runAllIterations()
    println()
    println(solver)
    println()
    println("Solver Results Summary:")
    solver.printResults()
    println()
    println("Final (Best) Solution Found:")
    println(solver.bestSolution.toString())

}
