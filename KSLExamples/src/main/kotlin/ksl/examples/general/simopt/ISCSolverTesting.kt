package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker

/**
 *  Demonstrates the Industrial Strength COMPASS (ISC) solver on the (R,Q) inventory problem. ISC runs
 *  three phases: a global Niching-GA exploration, a local COMPASS search from each discovered niche,
 *  and a clean-up ranking-and-selection that picks the best local optimum and reports a confidence
 *  interval for it.
 *
 *  The single indifference-zone parameter `deltaC` drives the statistical guarantees:
 *   - `deltaC == 0.0` (the default here, inherited from the problem) runs in degraded mode: ISC still
 *     returns a feasible best and an ordinary confidence interval, but without the +/-deltaC
 *     correct-selection guarantee.
 *   - `deltaC > 0.0` enables the full guarantees (Rinott clean-up selection and a +/-deltaC interval;
 *     with `deltaL > 0` COMPASS also confirms local optimality via Kim's 2005 test). Choose deltaC on
 *     the scale of the objective (cost) you care to distinguish.
 *
 *  The global phase is bounded with a replication budget so the example finishes in reasonable time.
 *  Pass `skipGlobalPhase = true` for the COMPASS-only unimodal shortcut (a single local search).
 */
fun main() {

    val modelIdentifier = "RQInventoryModel"
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val solver = Solver.createISCSolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        // deltaC = 50.0,            // uncomment for full ISC guarantees (scale to the cost objective)
        // skipGlobalPhase = true,   // uncomment for the COMPASS-only unimodal shortcut
        globalBudget = 1500,
        maxIterations = 100,
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
    println()
    println("Number of local optima discovered: ${solver.localOptima.size}")
    println("Reported confidence interval for the best: ${solver.confidenceInterval}")

}
