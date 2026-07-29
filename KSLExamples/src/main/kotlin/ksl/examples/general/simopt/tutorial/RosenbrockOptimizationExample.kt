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
 * Tutorial Example 1 -- a single optimization run on a Type 1 (noisy function)
 * problem, with the pipeline assembled by hand so that every layer is visible:
 *
 *     response function  ->  oracle  ->  evaluator  ->  solver
 *
 * Run `main` to execute.
 */
fun main() {
    // 1. The problem: what we minimize and over which variables.
    val problem: ProblemDefinition = makeRosenbrockProblem()

    // 2. The oracle: the thing that can "simulate" our function. For a Type 1
    //    problem this is a ResponseFunctionOracle wrapping our builder. Its model
    //    identifier must match the problem's model identifier.
    val oracle = ResponseFunctionOracle(
        modelIdentifier = ROSENBROCK_ID,
        responseNames = setOf(ROSENBROCK_OBJECTIVE),
        responseFunctionBuilder = NoisyRosenbrockResponseBuilder()
    )

    // 3. The evaluator: turns requests for points into evaluated solutions and
    //    remembers points it has already seen in a cache (so a repeated point is
    //    not re-simulated).
    val evaluator = Evaluator(problem, oracle, MemorySolutionCache())

    // 4. The solver: a simple stochastic hill climber. Each iteration it tries a
    //    random neighbor and keeps it only if it is better. Averaging 50
    //    replications per point smooths out enough noise to compare points fairly.
    val solver = StochasticHillClimber(
        problem,
        evaluator,
        maximumIterations = 100,
        replicationsPerEvaluation = 50
    )

    // Optional: print the solver's progress once per iteration.
    ConsoleSolverStateTracker(solver).startTracking()

    // 5. Run the search to completion. This call blocks until the solver stops.
    solver.runAllIterations()

    println()
    println("Best solution found (true optimum is x1=1, x2=1 with objective 0):")
    println(solver.bestSolution.asString())
}
