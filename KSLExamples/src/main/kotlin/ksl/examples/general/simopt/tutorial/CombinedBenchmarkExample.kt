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

import ksl.examples.general.simopt.standardSolverCases
import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.utilities.io.KSL

/*
 * Tutorial Part IV -- the capstone: all THREE tutorial problems in one grid,
 * compared with the standard solver registry. This mixes both problem types the
 * framework unifies:
 *
 *   - Rosenbrock (Type 1: noisy function, 2-D, known optimum, exact gaps)
 *   - Newsvendor (Type 1: genuine Monte Carlo, 1-D, MAXIMIZE, known optimum)
 *   - (r, Q) inventory (Type 2: constrained DEDS, 2-D, gap vs. best found)
 *
 * Two things to watch for when you run it:
 *   1. The (r, Q) cells are far slower than the synthetic ones -- the discrete-
 *      event simulation is where the time goes. Expect a few minutes.
 *   2. R-SPLINE (part of standardSolverCases) requires 2+ dimensions, so its cells
 *      on the 1-D newsvendor may record status FAILED. That is the harness doing
 *      its job: a failing cell is isolated and recorded, never crashing the study.
 *
 * Run `main` to execute.
 */
fun main() {
    val experiment = BenchmarkExperiment(
        name = "TutorialCombinedStudy",
        problems = listOf(
            makeRosenbrockProblemCase(),
            makeNewsvendorProblemCase(),
            makeRQProblemCase()
        ),
        solverCases = standardSolverCases(),
        macroReplications = 2,
        replicationBudgetPerRun = 250,
        verificationReplications = 50
    )
    val summary = experiment.run()

    val db = BenchmarkResultsDb("tutorialCombined.db", KSL.dbDir)
    val expId = db.saveSummary(summary)
    println()
    println("Results database: ${KSL.dbDir.resolve("tutorialCombined.db")} (experiment id $expId)")
    println()

    for (problemResult in summary.problemResults) {
        println("Problem '${problemResult.problemName}' (gap basis: ${problemResult.gapType}):")
        for (run in problemResult.runs) {
            println(
                "   ${run.cellLabel}: status = ${run.status}, best = ${run.bestObjective}, " +
                    "gap = ${run.gap}, consumed = ${run.numReplicationsRequested} replications"
            )
        }
        println("   winner inputs = ${problemResult.winner?.inputMap}")
        println()
    }

    // A statistical comparison of the solvers on one problem: which solver is best,
    // with statistical support (null if there are too few comparable runs).
    val analyzer = db.mcbAnalyzer(expId, "Rosenbrock2D")
    if (analyzer != null) {
        println("Multiple-comparison analysis for Rosenbrock2D:")
        println(analyzer)
    }
}
