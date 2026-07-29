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
 * Tutorial Example 1 -- the same noisy Rosenbrock problem, now compared across
 * several solvers on an equal footing (equal replication budget per run, common
 * starting points per macro-replication, and a confirmed winner). Results are
 * written to a small SQLite database.
 *
 * Run `main` to execute.
 */
fun main() {
    val experiment = BenchmarkExperiment(
        name = "RosenbrockTutorial",
        problems = listOf(makeRosenbrockProblemCase()),
        solverCases = standardSolverCases(),
        macroReplications = 3,
        replicationBudgetPerRun = 1000,
        verificationReplications = 100
    )
    val summary = experiment.run()

    // Persist everything; the database appends, so repeated runs accumulate.
    val db = BenchmarkResultsDb("rosenbrockTutorial.db", KSL.dbDir)
    val expId = db.saveSummary(summary)
    println()
    println("Results database: ${KSL.dbDir.resolve("rosenbrockTutorial.db")} (experiment id $expId)")
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
