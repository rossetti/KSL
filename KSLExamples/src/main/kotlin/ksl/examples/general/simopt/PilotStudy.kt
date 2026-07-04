package ksl.examples.general.simopt

import ksl.examples.general.models.inventory.twoEchelonProblemCase
import ksl.examples.general.supplychain.multiEchelonNetworkProblemCase
import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.examples.general.simopt.problems.NoiseLevel
import ksl.examples.general.simopt.problems.NoisyRastrigin
import ksl.examples.general.simopt.problems.NoisySphere
import ksl.utilities.io.KSL
import ksl.utilities.io.plotting.MultiSeriesStateVariablePlot
import ksl.utilities.io.plotting.PlotIfc

/**
 *  The benchmark harness pilot study: the validation pass that precedes a paper-scale
 *  study. Four experiments accumulate in ONE results database (pilotStudy.db):
 *
 *  1. the core grid — two synthetics at two noise levels plus the LK and (R,Q)
 *     inventory problems, all five registry cases, 10 macro-replications, budget 3000;
 *  2. the two-echelon add-on (expensive DEDS, 10 macro-replications, budget 3000);
 *  3. the multi-echelon network add-on (most expensive, 5 macro-replications, budget 2000);
 *  4. a TRACE-ENABLED rerun of one problem — exercising the design decision that a
 *     later rerun lands in the same database keyed by fresh run ids.
 *
 *  Post-processing writes the promised inspection artifacts to the KSL output
 *  directory: a multiple-comparison table per problem (pilotMcb_*.txt) and a
 *  convergence (best-so-far vs consumed replications) step plot from the captured
 *  traces (pilotConvergence png).
 */
fun main() {
    val overallStart = System.currentTimeMillis()
    val dbName = "pilotStudy.db"
    val db = BenchmarkResultsDb(dbName, KSL.dbDir, deleteIfExists = true)

    val coreId = runAndReport(
        BenchmarkExperiment(
            name = "pilotCore",
            problems = listOf(
                NoisySphere(2, NoiseLevel.LOW).problemCase(),
                NoisySphere(2, NoiseLevel.MED).problemCase(),
                NoisyRastrigin(2, NoiseLevel.LOW).problemCase(),
                NoisyRastrigin(2, NoiseLevel.MED).problemCase(),
                lkInventoryProblemCase(),
                rqInventoryProblemCase()
            ),
            solverCases = standardSolverCases(),
            macroReplications = 10,
            replicationBudgetPerRun = 3000,
            verificationReplications = 200
        ), db
    )
    runAndReport(
        BenchmarkExperiment(
            name = "pilotTwoEchelon",
            problems = listOf(twoEchelonProblemCase(constrained = true)),
            solverCases = standardSolverCases(),
            macroReplications = 10,
            replicationBudgetPerRun = 3000,
            verificationReplications = 200
        ), db
    )
    runAndReport(
        BenchmarkExperiment(
            name = "pilotMultiEchelon",
            problems = listOf(multiEchelonNetworkProblemCase()),
            solverCases = standardSolverCases(),
            macroReplications = 5,
            replicationBudgetPerRun = 2000,
            verificationReplications = 200
        ), db
    )
    // D3: the trace-enabled rerun appends into the same database under a fresh id
    val tracedId = runAndReport(
        BenchmarkExperiment(
            name = "pilotTraceRerun",
            problems = listOf(NoisySphere(2, NoiseLevel.MED).problemCase()),
            solverCases = standardSolverCases(),
            macroReplications = 10,
            replicationBudgetPerRun = 3000,
            captureIterationTraces = true
        ), db
    )

    // ── analysis artifacts ────────────────────────────────────────────────────
    for (problem in db.problems(coreId)) {
        writeMcbTable(db, coreId, problem.problemName)
    }
    writeConvergencePlot(db, tracedId, "noisySphere_d2_MED")
    val profile = db.performanceProfile(tracedId, tau = 2.0, numPoints = 10)
    println("Performance profile (traced rerun, tau = 2.0):")
    for ((label, points) in profile.groupBy { it.solverLabel }) {
        println("   $label: " + points.joinToString { "%.1f->%.2f".format(it.budgetFraction, it.fractionSolved) })
    }
    println()
    println("Pilot database: ${KSL.dbDir.resolve(dbName)}")
    println("Total pilot wall clock: ${(System.currentTimeMillis() - overallStart) / 1000} s")
}

private fun runAndReport(experiment: BenchmarkExperiment, db: BenchmarkResultsDb): Int {
    val start = System.currentTimeMillis()
    val summary = experiment.run()
    val expId = db.saveSummary(summary)
    val seconds = (System.currentTimeMillis() - start) / 1000
    println()
    println("=== Experiment '${experiment.name}' (id $expId) completed in $seconds s ===")
    for (problemResult in summary.problemResults) {
        val completed = problemResult.runs.count { it.isBestValid }
        println(
            "   ${problemResult.problemName}: $completed/${problemResult.runs.size} valid runs, " +
                    "gap basis = ${problemResult.gapType}, winner = ${problemResult.winner?.inputMap}"
        )
        val failed = problemResult.runs.filter { !it.isBestValid }
        for (run in failed) {
            println("      FAILED ${run.cellLabel}: ${run.errorMessage}")
        }
    }
    return expId
}

private fun writeMcbTable(db: BenchmarkResultsDb, expId: Int, problemName: String) {
    val analyzer = db.mcbAnalyzer(expId, problemName)
    if (analyzer == null) {
        println("MCB table skipped for $problemName (incomplete data)")
        return
    }
    val file = KSL.outDir.resolve("pilotMcb_$problemName.txt").toFile()
    file.writeText(analyzer.toString())
    println("MCB table written: $file")
}

internal fun writeConvergencePlot(db: BenchmarkResultsDb, expId: Int, problemName: String) {
    // one representative trace (macro-rep 1) per solver case: best-so-far penalized
    // objective (y) vs cumulative replications (x). Points recorded before a solver
    // has any valid best carry a Double.MAX_VALUE sentinel — drop them, the axis
    // cannot scale to them.
    val runsById = db.runs(expId).filter { it.problemName == problemName && it.repNum == 1 }
    val tracesByRun = db.traces(expId).groupBy { it.runId }
    val seriesData = mutableMapOf<String, Map<String, DoubleArray>>()
    for (run in runsById) {
        val trace = tracesByRun[run.runId]
            ?.filter { it.bestPenalizedObjective.isFinite() && kotlin.math.abs(it.bestPenalizedObjective) < 1e300 }
            ?.sortedBy { it.iteration }
            ?.takeIf { it.isNotEmpty() } ?: continue
        seriesData[run.solverLabel] = mapOf(
            "times" to trace.map { it.cumulativeReplications.toDouble() }.toDoubleArray(),
            "values" to trace.map { it.bestPenalizedObjective }.toDoubleArray()
        )
    }
    if (seriesData.isEmpty()) {
        println("Convergence plot skipped for $problemName (no traces)")
        return
    }
    val plot = MultiSeriesStateVariablePlot(seriesData, responseName = "best penalized objective")
    plot.xLabel = "cumulative replications"
    plot.yLabel = "best penalized objective"
    val file = plot.saveToFile(
        fileName = "pilotConvergence_$problemName",
        directory = KSL.outDir,
        plotTitle = "Convergence on $problemName (rep 1)",
        extType = PlotIfc.ExtType.PNG
    )
    println("Convergence plot written: $file")
}
