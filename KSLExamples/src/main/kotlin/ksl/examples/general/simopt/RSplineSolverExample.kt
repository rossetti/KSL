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
 * Demonstrates `Solver.createRSplineSolver` — R-SPLINE retrospective search
 * (Wang, Pasupathy &amp; Schmeiser, 2013). Every decision variable must be
 * integer-ordered (`granularity = 1.0`); the solver's piecewise-linear
 * interpolation step is a lattice triangulation and throws at construction
 * time if the problem isn't integer-ordered.
 *
 * Model: the LK inventory problem ([makeLKInventoryModelProblemDefinition],
 * [BuildLKModel]) — both of its decision variables are already
 * integer-ordered, so no changes are needed to use it with R-SPLINE.
 *
 * Run `main` to execute.
 */
fun main() {
    val problem: ProblemDefinition = makeLKInventoryModelProblemDefinition()

    // R-SPLINE has no single "replicationsPerEvaluation" — the sample size at
    // outer iteration k grows geometrically: initialNumReps * (1+sampleSizeGrowthRate)^(k-1),
    // capped at maxNumReplications.
    val solver = Solver.createRSplineSolver(
        problemDefinition = problem,
        modelBuilder = BuildLKModel,
        initialNumReps = 8,
        sampleSizeGrowthRate = 0.1,
        maxNumReplications = 1000,
        maxIterations = 100
    )

    ConsoleSolverStateTracker(solver).startTracking()
    solver.runAllIterations()

    println()
    println("Best solution found:")
    println(solver.bestSolution.asString())
}
