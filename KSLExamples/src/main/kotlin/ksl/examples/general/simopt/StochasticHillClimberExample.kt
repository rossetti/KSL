/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.simopt

import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker

/**
 * Demonstrates `Solver.createStochasticHillClimbingSolver` — greedy,
 * accept-if-better neighbor search. The simplest solver in the package, and
 * a reasonable first one to reach for on a well-behaved response surface.
 *
 * Model: the LK inventory problem ([makeLKInventoryModelProblemDefinition],
 * [BuildLKModel]) — objective `"TotalCost"`, two integer-ordered decision
 * variables bound to model control keys.
 *
 * Note the factory's `maxIterations` default (100) is not the same as the
 * class's own direct-constructor default (1000) — see the guide's gotchas
 * section for why, and pass it explicitly if the difference matters to you.
 *
 * Run `main` to execute.
 */
fun main() {
    val problem: ProblemDefinition = makeLKInventoryModelProblemDefinition()

    val solver = Solver.createStochasticHillClimberSolver(
        problemDefinition = problem,
        modelBuilder = BuildLKModel,
        startingPoint = null,
        maxIterations = 100,
        replicationsPerEvaluation = 50
    )

    ConsoleSolverStateTracker(solver).startTracking()
    solver.runAllIterations()

    println()
    println("Best solution found:")
    println(solver.bestSolution.asString())
    println()
    solver.printResults()
}
