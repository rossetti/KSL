package ksl.examples.general.simopt

import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.examples.general.simopt.problems.NoiseLevel
import ksl.examples.general.simopt.problems.Newsvendor
import ksl.examples.general.simopt.problems.NoisySphere
import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.utilities.io.KSL

/**
 *  Runs a small benchmark experiment end-to-end and shows where everything lands:
 *
 *  problems x solver cases x macro-replications -> SQLite database -> analysis feeds
 *
 *  The grid mixes the two problem kinds the harness unifies — synthetic problems with
 *  known optima (exact gaps) and a DEDS inventory model (gaps relative to the best
 *  found) — and runs the standard solver-case registry under an equal replication
 *  budget per cell, with common starting points per macro-replication, a CRN
 *  confirmation of each problem's finalists, and a verification re-simulation of each
 *  winner at elevated replications.
 *
 *  This is the canonical setup pattern that `ksl.controls.experiments`-style studies
 *  and the benchmarking paper build on; larger studies just pass longer lists and
 *  bigger budgets.
 */
fun main() {
    val experiment = BenchmarkExperiment(
        name = "BenchmarkDemo",
        problems = listOf(
            NoisySphere(dimension = 2, noiseLevel = NoiseLevel.LOW).problemCase(),
            Newsvendor().problemCase(),
            lkInventoryProblemCase()
        ),
        solverCases = standardSolverCases(),
        macroReplications = 3,
        replicationBudgetPerRun = 1000,
        verificationReplications = 100
    )
    val summary = experiment.run()

    // persist everything; the database appends, so repeated demo runs accumulate
    val db = BenchmarkResultsDb("benchmarkDemo.db", KSL.dbDir)
    val expId = db.saveSummary(summary)
    println()
    println("Results database: ${KSL.dbDir.resolve("benchmarkDemo.db")} (experiment id $expId)")
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

    // the multiple-comparison feed for one problem: solver label -> final objectives
    val mcbAnalyzer = db.mcbAnalyzer(expId, "LKInventory")
    if (mcbAnalyzer != null) {
        println("Multiple comparison analysis for LKInventory:")
        println(mcbAnalyzer)
    }
}
