package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker

/**
 *  Demonstrates the genetic algorithm (GA) solver on the (R,Q) inventory problem: minimize the
 *  ordering-and-holding cost subject to a fill-rate requirement (handled by the framework's penalty).
 *  The GA evolves a population each generation using its default operators (tournament selection,
 *  blend crossover, Gaussian mutation) with elitism, evaluating each generation as a single batch.
 *
 *  To explore a random-restart wrapper instead, replace the factory call with
 *  [Solver.createRandomRestartGeneticAlgorithmSolver].
 */
fun main() {

    val modelIdentifier = "RQInventoryModel"
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val solver = Solver.createGeneticAlgorithmSolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        populationSize = 30,
        startingPoint = null,
        maxIterations = 50,
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
