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

import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker

/*
 * Tutorial Example 3 -- a single optimization run on the newsvendor problem. The
 * pipeline is identical in shape to the Rosenbrock example (Type 1), which is the
 * point: the same machinery drives a genuine Monte Carlo model and a MAXIMIZE
 * objective without any special handling on our part.
 *
 * Run `main` to execute.
 */
fun main() {
    val problem: ProblemDefinition = makeNewsvendorProblem()

    val oracle = ResponseFunctionOracle(
        modelIdentifier = NEWSVENDOR_ID,
        responseNames = setOf(NEWSVENDOR_OBJECTIVE),
        responseFunctionBuilder = NewsvendorResponseBuilder()
    )

    val evaluator = Evaluator(problem, oracle, MemorySolutionCache())

    val solver = StochasticHillClimber(
        problem,
        evaluator,
        maximumIterations = 100,
        replicationsPerEvaluation = 50
    )

    ConsoleSolverStateTracker(solver).startTracking()
    solver.runAllIterations()

    println()
    println("Closed-form optimal order quantity q* = ${newsvendorOptimalOrderQuantity()}")
    println("Best solution found (asString prints the raw average profit for a maximize problem):")
    println(solver.bestSolution.asString())
}
