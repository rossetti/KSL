package ksl.examples.general.simopt.study1

import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.utilities.io.KSL

/**
 *  The Study-1 smoke phase (plan P2): the full 24-problem grid × nine solver cases at 3
 *  macro-replications and quarter budgets (500 for dimension ≤ 3, 1250 for dimension
 *  = 5). Its purpose is not results but shakedown — exercise every solver × problem
 *  combination once, measure per-solver wall costs (notably BO and ISC) and
 *  generation counts, and run the anomaly screen (failures, budget consumption,
 *  wall-time, iterations) so the main run can be sized with data and any solver defects
 *  caught before it.
 *
 *  Writes to `study1_smoke.db` (deleted fresh each smoke run). The two budget-tier
 *  experiments accumulate under one database; the runner is resumable by experiment name.
 */
fun main() {
    val overallStart = System.currentTimeMillis()
    val db = BenchmarkResultsDb("study1_smoke.db", KSL.dbDir, deleteIfExists = true)
    val config = Study1Config(
        experimentPrefix = "study1Smoke",
        macroReplications = 3,
        smallBudget = 500,
        largeBudget = 1250,
        // keep confirmation/verification light for the smoke — this is a shakedown
        confirmation = ksl.simopt.solvers.concurrent.ConfirmationOptions(topK = 3, replicationsPerCandidate = 50),
        verificationReplications = 100,
        captureTraces = true
    )

    val expIds = runStudy1(db, config)

    println()
    println("Smoke experiments completed: $expIds")
    println("Total smoke wall clock: ${(System.currentTimeMillis() - overallStart) / 1000} s")
    println()

    Study1Anomaly.report(db, expIds.values)

    // quick correctness sanity: every synthetic problem should recover its exact optimum
    println()
    println("WINNER SANITY (should equal the known optimum for synthetics):")
    for (expId in expIds.values) {
        for (problem in db.problems(expId).sortedBy { it.problemName }) {
            println("   ${problem.problemName}: winner obj = ${problem.winnerObjective}, gap basis = ${problem.gapType}")
        }
    }
    println()
    println("Smoke database: ${KSL.dbDir.resolve("study1_smoke.db")}")
}
