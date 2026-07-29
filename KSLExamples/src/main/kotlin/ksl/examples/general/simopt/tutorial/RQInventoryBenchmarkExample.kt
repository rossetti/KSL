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
 * Tutorial Example 2 -- a small benchmark study on the constrained (r, Q) problem.
 *
 * Because each replication runs a discrete-event simulation, this study takes a
 * few minutes -- keep the grid small while learning. The verification stage
 * re-simulates each winner at a higher replication count so we can check that the
 * fill-rate constraint is actually met, not just met by a lucky estimate.
 *
 * Run `main` to execute.
 */
fun main() {
    val experiment = BenchmarkExperiment(
        name = "RQInventoryTutorial",
        problems = listOf(makeRQProblemCase()),
        solverCases = standardSolverCases(),
        macroReplications = 2,
        replicationBudgetPerRun = 300,
        verificationReplications = 50
    )
    val summary = experiment.run()

    val db = BenchmarkResultsDb("rqInventoryTutorial.db", KSL.dbDir)
    val expId = db.saveSummary(summary)
    println()
    println("Results database: ${KSL.dbDir.resolve("rqInventoryTutorial.db")} (experiment id $expId)")
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
        println("   verification  = ${problemResult.verification?.estimatedObjFnc}")
        println()
    }
}
