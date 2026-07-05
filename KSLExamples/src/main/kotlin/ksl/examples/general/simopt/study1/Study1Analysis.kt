package ksl.examples.general.simopt.study1

import kotlinx.serialization.json.Json
import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.simopt.benchmark.io.RunTableData
import ksl.utilities.io.KSL
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import ksl.utilities.statistic.Statistic
import java.nio.file.Path

/**
 *  The Study-1 analysis: turns the benchmark database into the paper's artifacts. The
 *  headline is the EXACT (estimation-free) optimality gap — recomputed at each run's
 *  stored recommendation via the synthetic families' true objectives ([Study1TrueGap]) —
 *  reported alongside the recorded (estimated) gap so the estimated-vs-true bias is
 *  visible. Also: per-solver success rates against the indifference-zone threshold,
 *  actual replication consumption (which carries the ISC finding), a multiple-comparison
 *  ranking per problem, and the V&V anomaly screen.
 *
 *  Writes CSVs to the KSL output directory and prints summary tables; the CSVs are the
 *  reproducible inputs to whatever plots/tables the paper uses.
 */
object Study1Analysis {

    private val json = Json { ignoreUnknownKeys = true }

    private data class RunRecord(
        val problem: String,
        val solver: String,
        val rep: Int,
        val trueGap: Double?,
        val estimatedGap: Double?,
        val bestObjective: Double,
        val consumption: Int,
        val iterations: Int?,
        val wallMs: Long?,
        val inputFeasible: Boolean,
        val responseViolation: Double,
        val valid: Boolean,
        val tau: Double
    )

    /**
     *  Runs the full analysis over the given experiments (all in the database when null)
     *  and writes the artifacts under [outputDir].
     */
    fun analyze(db: BenchmarkResultsDb, expIds: Collection<Int>? = null, outputDir: Path = KSL.outDir) {
        val experiments = db.experiments().filter { expIds == null || it.expId in expIds }
        val tauByProblem = experiments.flatMap { db.problems(it.expId) }.associate { p ->
            p.problemName to (parseTags(p.tagsJson)["indifferenceZone"]?.toDoubleOrNull() ?: 0.0)
        }
        val records = experiments.flatMap { db.runs(it.expId) }.map { toRecord(it, tauByProblem) }

        writeRunsCsv(records, outputDir)
        val summary = writeSummaryCsv(records, outputDir)
        printEstimatedVsTrueBias(records)
        printConsumptionBySolver(records)
        printMcbBySolver(summary)
        println()
        Study1Anomaly.report(db, expIds)
        println()
        println("Analysis CSVs written to: $outputDir")
    }

    private fun toRecord(run: RunTableData, tauByProblem: Map<String, Double>): RunRecord {
        val bestInputs = parseInputs(run.bestInputsJson)
        val trueGap = if (run.bestValid) Study1TrueGap.trueGap(run.problemName, bestInputs) else null
        return RunRecord(
            problem = run.problemName,
            solver = run.solverLabel,
            rep = run.repNum,
            trueGap = trueGap,
            estimatedGap = run.gap,
            bestObjective = run.bestObjective,
            consumption = run.numReplicationsRequested,
            iterations = run.totalIterations,
            wallMs = run.wallClockMillis,
            inputFeasible = run.inputFeasible,
            responseViolation = run.responseConstraintViolation,
            valid = run.bestValid,
            tau = tauByProblem[run.problemName] ?: 0.0
        )
    }

    // ── CSV: per run ──────────────────────────────────────────────────────────

    private fun writeRunsCsv(records: List<RunRecord>, outputDir: Path) {
        val file = outputDir.resolve("study1_runs.csv").toFile()
        file.bufferedWriter().use { w ->
            w.appendLine("problem,solver,rep,tau,trueGap,estimatedGap,bestObjective,consumption,iterations,wallMs,inputFeasible,responseViolation,valid")
            for (r in records) {
                w.appendLine(
                    "${r.problem},${r.solver},${r.rep},${r.tau},${r.trueGap ?: ""},${r.estimatedGap ?: ""}," +
                            "${r.bestObjective},${r.consumption},${r.iterations ?: ""},${r.wallMs ?: ""}," +
                            "${r.inputFeasible},${r.responseViolation},${r.valid}"
                )
            }
        }
        println("Per-run records: ${file.name} (${records.size} rows)")
    }

    // ── CSV + return: per (problem, solver) summary ───────────────────────────

    private data class SummaryRow(
        val problem: String,
        val solver: String,
        val n: Int,
        val meanTrueGap: Double,
        val sdTrueGap: Double,
        val successRate: Double,
        val meanConsumption: Double,
        val meanTrueGaps: DoubleArray
    )

    private fun writeSummaryCsv(records: List<RunRecord>, outputDir: Path): List<SummaryRow> {
        val rows = records.groupBy { it.problem to it.solver }.map { (key, runs) ->
            val (problem, solver) = key
            val validGaps = runs.mapNotNull { it.trueGap }
            val tau = runs.first().tau
            val stat = Statistic(validGaps.toDoubleArray())
            val successes = validGaps.count { it <= tau }
            SummaryRow(
                problem = problem,
                solver = solver,
                n = runs.size,
                meanTrueGap = if (validGaps.isEmpty()) Double.NaN else stat.average,
                sdTrueGap = if (validGaps.size < 2) Double.NaN else stat.standardDeviation,
                successRate = if (runs.isEmpty()) Double.NaN else successes.toDouble() / runs.size,
                meanConsumption = Statistic(runs.map { it.consumption.toDouble() }.toDoubleArray()).average,
                meanTrueGaps = validGaps.toDoubleArray()
            )
        }.sortedWith(compareBy({ it.problem }, { it.solver }))
        val file = outputDir.resolve("study1_summary.csv").toFile()
        file.bufferedWriter().use { w ->
            w.appendLine("problem,solver,n,meanTrueGap,sdTrueGap,successRate,meanConsumption")
            for (r in rows) {
                w.appendLine(
                    "${r.problem},${r.solver},${r.n},${fmt(r.meanTrueGap)},${fmt(r.sdTrueGap)}," +
                            "${fmt(r.successRate)},${fmt(r.meanConsumption)}"
                )
            }
        }
        println("Per-(problem,solver) summary: ${file.name} (${rows.size} rows)")
        return rows
    }

    // ── console: estimated vs true bias, pooled per solver ────────────────────

    private fun printEstimatedVsTrueBias(records: List<RunRecord>) {
        println()
        println("ESTIMATED-vs-TRUE GAP (pooled per solver; estimated gaps are winner-selection biased):")
        println("   %-12s %14s %14s".format("solver", "mean est. gap", "mean true gap"))
        for ((solver, runs) in records.groupBy { it.solver }.toSortedMap()) {
            val est = runs.mapNotNull { it.estimatedGap }
            val tru = runs.mapNotNull { it.trueGap }
            val estMean = if (est.isEmpty()) Double.NaN else Statistic(est.toDoubleArray()).average
            val truMean = if (tru.isEmpty()) Double.NaN else Statistic(tru.toDoubleArray()).average
            println("   %-12s %14s %14s".format(solver, fmt(estMean), fmt(truMean)))
        }
    }

    // ── console: actual consumption per solver (the ISC finding) ──────────────

    private fun printConsumptionBySolver(records: List<RunRecord>) {
        println()
        println("ACTUAL CONSUMPTION per solver (replications; ISC is not budget-constrained):")
        println("   %-12s %12s %12s %12s".format("solver", "median", "max", "cells"))
        for ((solver, runs) in records.groupBy { it.solver }.toSortedMap()) {
            val cons = runs.map { it.consumption }.sorted()
            val median = if (cons.isEmpty()) 0 else cons[cons.size / 2]
            println("   %-12s %12d %12d %12d".format(solver, median, cons.maxOrNull() ?: 0, runs.size))
        }
    }

    // ── console: multiple-comparison ranking per problem, by true gap ─────────

    private fun printMcbBySolver(summary: List<SummaryRow>) {
        println()
        println("BEST SOLVER BY MEAN TRUE GAP (per problem; MCB where rep counts are equal):")
        for ((problem, rows) in summary.groupBy { it.problem }.toSortedMap()) {
            val ranked = rows.filter { it.meanTrueGap.isFinite() }.sortedBy { it.meanTrueGap }
            if (ranked.isEmpty()) continue
            val best = ranked.first()
            // MCB feed: solvers with the same (full) number of valid observations
            val n = ranked.maxOf { it.meanTrueGaps.size }
            val mcbData = ranked.filter { it.meanTrueGaps.size == n && n >= 2 }
                .associate { it.solver to it.meanTrueGaps }
            val mcbNote = if (mcbData.size >= 2) {
                val analyzer = MultipleComparisonAnalyzer(mcbData, responseName = problem)
                " | MCB best (min avg true gap): ${analyzer.nameOfMinimumAverageOfData}"
            } else ""
            println("   %-30s best=%-11s meanTrueGap=%s%s".format(problem, best.solver, fmt(best.meanTrueGap), mcbNote))
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun parseInputs(jsonStr: String): Map<String, Double> =
        try { json.decodeFromString<Map<String, Double>>(jsonStr) } catch (e: Exception) { emptyMap() }

    private fun parseTags(jsonStr: String?): Map<String, String> =
        if (jsonStr == null) emptyMap()
        else try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }

    private fun fmt(x: Double): String = if (x.isNaN()) "-" else "%.4g".format(x)
}
