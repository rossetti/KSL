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

package ksl.examples.general.simopt.tutorial

import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker

/*
 * Tutorial Example 2 -- a single optimization run on a Type 2 (DEDS) problem.
 *
 * For a simulation model we do not assemble the oracle/evaluator by hand. The
 * solver factory does it for us from just the problem and a model builder:
 *
 *     Solver.createStochasticHillClimberSolver(problem, modelBuilder, ...)
 *
 * Because each point requires running a discrete-event simulation, this run takes
 * noticeably longer than the instant Type 1 examples -- that difference is the
 * whole reason simulation optimization tries to be frugal with evaluations.
 *
 * Run `main` to execute.
 */
fun main() {
    val problem: ProblemDefinition = makeRQProblem()

    // The factory builds the evaluator (with a solution cache) over a fresh model
    // and binds a stochastic hill climber to it. A null starting point means "pick
    // a random feasible starting point for me."
    val solver = Solver.createStochasticHillClimberSolver(
        problemDefinition = problem,
        modelBuilder = BuildRQTutorialModel,
        startingPoint = null,
        maxIterations = 30,
        replicationsPerEvaluation = 30
    )

    ConsoleSolverStateTracker(solver).startTracking()
    solver.runAllIterations()

    println()
    println("Best solution found (reorder quantity and reorder point):")
    // asString() reports the estimated objective and whether the response
    // constraint (fill rate >= 0.95) is satisfied.
    println(solver.bestSolution.asString())
    println()
    solver.printResults()
}
