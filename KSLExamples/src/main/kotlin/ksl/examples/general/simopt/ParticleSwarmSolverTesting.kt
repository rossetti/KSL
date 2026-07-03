package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker

/**
 *  Demonstrates the global-best particle swarm optimization (PSO) solver on the (R,Q) inventory
 *  problem. Each iteration moves the whole swarm using the inertia-weight schedule and the cognitive
 *  and social pulls toward each particle's best and the global best, clamping positions to the input
 *  bounds; integer decisions follow from the problem's granularity.
 *
 *  PSO evaluates the entire swarm as one batch per iteration, so the factory enables parallel oracle
 *  execution by default. That requires the model builder to return an independent, freshly built
 *  model on each call (the (R,Q) builder does). Pass `parallelOptions = ParallelEvaluationOptions()`
 *  to the factory to force sequential evaluation instead.
 */
fun main() {

    val modelIdentifier = "RQInventoryModel"
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val solver = Solver.createParticleSwarmSolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        swarmSize = 20,
        startingPoint = null,
        maxIterations = 50,
        replicationsPerEvaluation = 30,
        // parallelOptions defaults to enabled = true: the swarm is evaluated in parallel each iteration
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
