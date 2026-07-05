package ksl.examples.general.simopt.study1

import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.simopt.solvers.concurrent.ConfirmationOptions
import ksl.utilities.io.KSL

/**
 *  The Study-1 main run (plan P3): the full 24-problem grid × the nine-case roster at 30
 *  macro-replications and the approved budgets (10,000 for dimension ≤ 3, 20,000 for
 *  dimension = 5), traces on, CRN confirmation (top-5 × 200) and winner verification
 *  (1,000 reps). Results accumulate in `study1.db`; the runner is resumable by experiment
 *  name, so an interrupted run continues where it left off (re-invoke `main`).
 *
 *  After the run, the analysis writes the paper artifacts (true-gap and estimated-gap
 *  CSVs, per-solver success rates and consumption, MCB ranking, anomaly screen).
 *
 *  NOTE ON COST: ISC runs at library defaults and its clean-up correct-selection
 *  guarantee consumes ~(noise/IZ)² replications independent of the benchmark budget, so
 *  ISC dominates the wall time on the flat/high-noise problems (a reported finding). The
 *  rest of the study is cheap.
 */
fun main() {
    val overallStart = System.currentTimeMillis()
    val db = BenchmarkResultsDb("study1.db", KSL.dbDir, deleteIfExists = false)
    val config = Study1Config(
        experimentPrefix = "study1",
        macroReplications = 30,
        smallBudget = 10_000,
        largeBudget = 20_000,
        confirmation = ConfirmationOptions(topK = 5, replicationsPerCandidate = 200),
        verificationReplications = 1_000,
        captureTraces = true
    )

    val expIds = runStudy1(db, config)
    println()
    println("Main-run experiments: $expIds")
    println("Main-run wall clock: ${(System.currentTimeMillis() - overallStart) / 1000} s")
    println()

    // analyze ALL experiments in the database (resumed runs included)
    Study1Analysis.analyze(db, expIds = null, outputDir = KSL.outDir)
    println()
    println("Study-1 database: ${KSL.dbDir.resolve("study1.db")}")
}
