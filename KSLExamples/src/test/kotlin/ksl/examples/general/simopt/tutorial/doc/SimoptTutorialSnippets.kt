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

package ksl.examples.general.simopt.tutorial.doc

import ksl.examples.general.simopt.tutorial.BuildRQTutorialModel
import ksl.examples.general.simopt.tutorial.makeRQProblem
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule
import ksl.simopt.solvers.ReplicationBudgetStoppingCriterion
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.RSplineSolver

/**
 * Compile-only host for the code snippets in `docs/guides/ksl-simopt-tutorial.md`
 * that are NOT already present in the runnable tutorial example files (package
 * `ksl.examples.general.simopt.tutorial`). The runnable files already prove most
 * of the tutorial's code; this file covers the few alternative-solver and
 * validation snippets shown only in the prose, so compiling it proves they also
 * reference real public APIs.
 *
 * This file is not run as a test -- the build only needs to compile it.
 */
@Suppress("unused", "UNUSED_VARIABLE")
private object SimoptTutorialSnippets {

    // Part II -- swapping the hand-assembled Type 1 pipeline to R-SPLINE.
    fun rSplineSwapType1(problem: ProblemDefinition, evaluator: EvaluatorIfc) {
        val solver = RSplineSolver(
            problem, evaluator,
            replicationsPerEvaluation = FixedGrowthRateReplicationSchedule(initialNumReps = 8)
        )
    }

    // Part III -- swapping the DEDS pipeline to R-SPLINE via the factory.
    fun rSplineSwapType2() {
        val problem = makeRQProblem()
        val solver = Solver.createRSplineSolver(
            problemDefinition = problem,
            modelBuilder = BuildRQTutorialModel
        )
    }

    // Part III -- pinning the input-key / response-name correspondence in a test.
    fun validateRQNaming() {
        val model = BuildRQTutorialModel.build(null, null)
        require(makeRQProblem().validateProblemDefinition(model)) {
            "The problem's input keys / response names do not match the model."
        }
    }

    // Part VI -- stopping a solver on a fixed replication budget.
    fun replicationBudgetStop(solver: Solver) {
        solver.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(replicationBudget = 3000)
    }
}
