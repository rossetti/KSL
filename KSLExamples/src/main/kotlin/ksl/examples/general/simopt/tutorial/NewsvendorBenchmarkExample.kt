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

import ksl.examples.general.simopt.crossEntropyCase
import ksl.examples.general.simopt.simulatedAnnealingCase
import ksl.examples.general.simopt.stochasticHillClimberCase
import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.utilities.io.KSL

/*
 * Tutorial Example 3 -- a small benchmark study on the newsvendor problem.
 *
 * We hand-pick the solver cases instead of using standardSolverCases(): the
 * newsvendor is a 1-DIMENSIONAL problem, and R-SPLINE (part of the standard set)
 * currently has a known issue on 1-D problems, so we leave it out. Matching the
 * solver set to the problem is part of designing a fair study.
 *
 * Run `main` to execute.
 */
fun main() {
    val experiment = BenchmarkExperiment(
        name = "NewsvendorTutorial",
        problems = listOf(makeNewsvendorProblemCase()),
        solverCases = listOf(
            stochasticHillClimberCase(),
            simulatedAnnealingCase(),
            crossEntropyCase()
        ),
        macroReplications = 3,
        replicationBudgetPerRun = 1000,
        verificationReplications = 100
    )
    val summary = experiment.run()

    val db = BenchmarkResultsDb("newsvendorTutorial.db", KSL.dbDir)
    val expId = db.saveSummary(summary)
    println()
    println("Results database: ${KSL.dbDir.resolve("newsvendorTutorial.db")} (experiment id $expId)")
    println("Closed-form optimal order quantity q* = ${newsvendorOptimalOrderQuantity()}")
    println()

    for (problemResult in summary.problemResults) {
        println("Problem '${problemResult.problemName}' (gap basis: ${problemResult.gapType}):")
        for (run in problemResult.runs) {
            println(
                "   ${run.cellLabel}: best = ${run.bestObjective}, gap = ${run.gap}, " +
                    "consumed = ${run.numReplicationsRequested} replications"
            )
        }
        println("   winner inputs = ${problemResult.winner?.inputMap}")
        println()
    }
}
