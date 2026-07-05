package ksl.examples.general.simopt.study1

import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.simopt.benchmark.io.RunTableData

/**
 *  The anomaly screen: the study's verification-and-validation output. For a set of
 *  experiments it summarizes, per solver, the things that reveal solver defects or
 *  unfair comparisons — failures, budget consumption vs the nominal budget (overshoot),
 *  wall-time, and iteration (generation) counts — and prints the failed cells with their
 *  error messages. It reads only recorded rows; it runs nothing.
 */
object Study1Anomaly {

    private data class SolverStats(
        val label: String,
        val total: Int,
        val failed: Int,
        val budget: Int,
        val consumptionRatios: List<Double>,
        val wallMillis: List<Long>,
        val iterations: List<Int>
    )

    /**
     *  Prints the anomaly screen for the given experiments (all experiments in the
     *  database when expIds is null).
     */
    fun report(db: BenchmarkResultsDb, expIds: Collection<Int>? = null) {
        val experiments = db.experiments().filter { expIds == null || it.expId in expIds }
        if (experiments.isEmpty()) {
            println("Anomaly screen: no experiments found.")
            return
        }
        val budgetByExp = experiments.associate { it.expId to it.replicationBudgetPerRun }
        val runs = experiments.flatMap { db.runs(it.expId) }
        println("=".repeat(78))
        println("ANOMALY SCREEN — ${experiments.size} experiment(s), ${runs.size} cells")
        println("=".repeat(78))

        // ── 1. failures ─────────────────────────────────────────────────────────
        val failures = runs.filter { it.status != "COMPLETED" || !it.bestValid }
        println()
        println("FAILURES: ${failures.size} of ${runs.size} cells")
        if (failures.isNotEmpty()) {
            for ((solver, list) in failures.groupBy { it.solverLabel }.toSortedMap()) {
                println("   $solver: ${list.size} failed")
                for ((msg, cells) in list.groupBy { it.errorMessage ?: it.status }) {
                    println("      [${cells.size}x] ${cells.first().problemName}: $msg")
                }
            }
        }

        // ── per-solver stats ────────────────────────────────────────────────────
        val statsByLabel = runs.groupBy { it.solverLabel }.toSortedMap().map { (label, list) ->
            solverStats(label, list, budgetByExp)
        }

        // ── 2. budget consumption (overshoot) ───────────────────────────────────
        println()
        println("BUDGET CONSUMPTION (actual replications / nominal budget):")
        println("   %-12s %8s %8s %8s   %s".format("solver", "min", "median", "max", "note"))
        for (s in statsByLabel) {
            val r = s.consumptionRatios
            val note = when {
                r.isEmpty() -> ""
                median(r) > 1.5 -> "<< heavy overshoot"
                median(r) < 0.9 -> "<< under-spends (stops early)"
                else -> ""
            }
            println(
                "   %-12s %8s %8s %8s   %s".format(
                    s.label, fmt(r.minOrNull()), fmt(median(r)), fmt(r.maxOrNull()), note
                )
            )
        }

        // ── 3. wall time ────────────────────────────────────────────────────────
        println()
        println("WALL TIME per cell (ms):")
        println("   %-12s %8s %8s %8s".format("solver", "min", "median", "max"))
        val overallMedianWall = statsByLabel.flatMap { it.wallMillis }.let { medianL(it) }
        for (s in statsByLabel) {
            val w = s.wallMillis
            val note = if (w.isNotEmpty() && medianL(w) > 5 * maxOf(overallMedianWall, 1)) "<< wall-time outlier" else ""
            println(
                "   %-12s %8s %8s %8s   %s".format(
                    s.label, w.minOrNull() ?: 0, medianL(w), w.maxOrNull() ?: 0, note
                )
            )
        }

        // ── 4. iterations (generation count) ────────────────────────────────────
        println()
        println("ITERATIONS per cell (generations for population methods):")
        println("   %-12s %8s %8s %8s".format("solver", "min", "median", "max"))
        for (s in statsByLabel) {
            val it = s.iterations
            println(
                "   %-12s %8s %8s %8s".format(
                    s.label, it.minOrNull() ?: 0, medianI(it), it.maxOrNull() ?: 0
                )
            )
        }
        println()
        println("=".repeat(78))
    }

    private fun solverStats(
        label: String,
        runs: List<RunTableData>,
        budgetByExp: Map<Int, Int>
    ): SolverStats {
        val completed = runs.filter { it.status == "COMPLETED" }
        val ratios = completed.mapNotNull { run ->
            val budget = budgetByExp[run.expId] ?: return@mapNotNull null
            if (budget > 0) run.numReplicationsRequested.toDouble() / budget else null
        }
        return SolverStats(
            label = label,
            total = runs.size,
            failed = runs.count { it.status != "COMPLETED" || !it.bestValid },
            budget = budgetByExp.values.firstOrNull() ?: 0,
            consumptionRatios = ratios,
            wallMillis = completed.mapNotNull { it.wallClockMillis },
            iterations = completed.mapNotNull { it.totalIterations }
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun medianL(values: List<Long>): Long =
        if (values.isEmpty()) 0L else median(values.map { it.toDouble() }).toLong()

    private fun medianI(values: List<Int>): Int =
        if (values.isEmpty()) 0 else median(values.map { it.toDouble() }).toInt()

    private fun fmt(x: Double?): String = if (x == null || x.isNaN()) "-" else "%.2f".format(x)
}
