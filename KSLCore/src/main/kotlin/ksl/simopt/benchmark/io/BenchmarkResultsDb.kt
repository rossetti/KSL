package ksl.simopt.benchmark.io

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ksl.simopt.benchmark.BenchmarkSummary
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.SQLiteDb
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import java.nio.file.Path

/**
 *  One point of performance-profile data: the fraction of a solver case's (problem,
 *  macro-replication) cells that reached the solve threshold within the given fraction
 *  of the replication budget.
 */
data class PerformanceProfilePoint(
    val solverLabel: String,
    val budgetFraction: Double,
    val fractionSolved: Double
)

/**
 *  The results database for benchmark experiments: SQLite by default (via the SQLiteDb
 *  base), holding the tables described by the table-data classes of this package.
 *
 *  Append semantics: by default the database is NOT deleted if it exists, and only
 *  missing tables are created — so successive experiments (including a later
 *  trace-enabled rerun of a problem) accumulate in one file under fresh experiment and
 *  run ids. Set deleteIfExists true to start clean.
 *
 *  Capture is post-run and bulk: run the `ksl.simopt.benchmark.BenchmarkExperiment`,
 *  then hand its summary to [saveSummary]. The returned experiment id keys every table.
 *
 *  Post-processing helpers pull typed rows back out (one function per table), feed a
 *  `MultipleComparisonAnalyzer` with per-problem final objectives via [mcbDataMap], and
 *  compute performance-profile data from captured traces via [performanceProfile].
 *
 *  @param dbName the database file name
 *  @param dbDirectory the directory holding the database file
 *  @param deleteIfExists true deletes an existing file first; the default (false)
 *  appends to it
 */
class BenchmarkResultsDb @JvmOverloads constructor(
    dbName: String,
    dbDirectory: Path = KSL.dbDir,
    deleteIfExists: Boolean = false
) : SQLiteDb(dbName, dbDirectory, deleteIfExists) {

    init {
        val missing = tableDefinitions().filterNot { containsTable(it.tableName, null) }.toSet()
        if (missing.isNotEmpty()) {
            createSimpleDbTables(missing)
        }
    }

    private val myJson = Json { encodeDefaults = true }

    private fun toJson(map: Map<String, Double>): String = myJson.encodeToString(map)

    private fun tagsToJson(map: Map<String, String>): String = myJson.encodeToString(map)

    /**
     *  Persists a benchmark summary: the experiment row, its problems, solver cases and
     *  their captured parameters, every cell run, confirmation finalists, verification
     *  estimates, and (when captured) iteration traces keyed by run id.
     *
     *  @param summary the summary returned by a benchmark experiment's run()
     *  @param kslVersion an optional KSL version string recorded with the experiment
     *  @return the experiment id assigned to this summary within the database
     */
    fun saveSummary(summary: BenchmarkSummary, kslVersion: String? = null): Int {
        val expId = nextId("tblExperiment", "expId")
        insertDbDataIntoTable(
            ExperimentTableData(
                expId = expId,
                expName = summary.experimentName,
                startTime = summary.startTime.toString(),
                endTime = summary.endTime.toString(),
                replicationBudgetPerRun = summary.replicationBudgetPerRun,
                macroReplications = summary.macroReplications,
                numProblems = summary.problemResults.size,
                numSolverCases = summary.solverCaseDescriptions.size,
                confirmationTopK = summary.confirmation?.topK,
                confirmationReplications = summary.confirmation?.replicationsPerCandidate,
                verificationReplications = summary.verificationReplications,
                tracesCaptured = summary.traces.isNotEmpty(),
                kslVersion = kslVersion
            )
        )
        saveSolverCases(expId, summary)
        saveProblems(expId, summary)
        val runIdByCell = saveRuns(expId, summary)
        saveConfirmations(expId, summary)
        saveVerifications(expId, summary)
        saveTraces(runIdByCell, summary)
        return expId
    }

    private fun saveSolverCases(expId: Int, summary: BenchmarkSummary) {
        val cases = summary.solverCaseDescriptions.map { (label, description) ->
            SolverCaseTableData(expId, label, description)
        }
        insertAllDbDataIntoTable(cases, "tblSolverCase")
        val parameters = summary.solverConfigurations.flatMap { (label, properties) ->
            properties.map { (paramName, paramValue) ->
                SolverCaseParameterTableData(expId, label, paramName, paramValue)
            }
        }
        insertAllDbDataIntoTable(parameters, "tblSolverCaseParameter")
    }

    private fun saveProblems(expId: Int, summary: BenchmarkSummary) {
        // find the problem's reference data indirectly: reference gaps carry the type
        val rows = summary.problemResults.map { pr ->
            ProblemTableData(
                expId = expId,
                problemName = pr.problemName,
                dimension = pr.dimension,
                optimizationType = pr.optimizationType.name,
                numResponseConstraints = pr.numResponseConstraints,
                tagsJson = if (pr.tags.isEmpty()) null else tagsToJson(pr.tags),
                referenceType = pr.gapType?.takeIf { it != ksl.simopt.benchmark.GapType.BEST_FOUND }?.name,
                referenceObjective = pr.gapBasisObjective?.takeIf {
                    pr.gapType != ksl.simopt.benchmark.GapType.BEST_FOUND
                },
                referenceInputsJson = null,
                gapType = pr.gapType?.name,
                gapBasisObjective = pr.gapBasisObjective,
                winnerInputsJson = pr.winner?.let { toJson(it.inputMap.toMap()) },
                winnerObjective = pr.winner?.average
            )
        }
        insertAllDbDataIntoTable(rows, "tblProblem")
    }

    private fun saveRuns(expId: Int, summary: BenchmarkSummary): Map<String, Int> {
        var runId = nextId("tblRun", "runId")
        val runIdByCell = mutableMapOf<String, Int>()
        val rows = mutableListOf<RunTableData>()
        for (run in summary.allRuns) {
            runIdByCell[run.cellLabel] = runId
            rows.add(
                RunTableData(
                    runId = runId,
                    expId = expId,
                    problemName = run.problemName,
                    solverLabel = run.solverLabel,
                    repNum = run.repNum,
                    cellLabel = run.cellLabel,
                    status = run.status.name,
                    startingPointJson = toJson(run.startingPoint),
                    bestInputsJson = toJson(run.bestInputs),
                    bestObjective = run.bestObjective,
                    bestPenalizedObjective = run.bestPenalizedObjective,
                    bestValid = run.isBestValid,
                    inputFeasible = run.isInputFeasible,
                    responseConstraintViolation = run.responseConstraintViolation,
                    numOracleCalls = run.numOracleCalls,
                    numReplicationsRequested = run.numReplicationsRequested,
                    totalIterations = run.totalIterations,
                    wallClockMillis = run.wallClockMillis,
                    gap = run.gap,
                    gapType = run.gapType?.name,
                    errorMessage = run.errorMessage
                )
            )
            runId++
        }
        insertAllDbDataIntoTable(rows, "tblRun")
        return runIdByCell
    }

    private fun saveConfirmations(expId: Int, summary: BenchmarkSummary) {
        val rows = mutableListOf<ConfirmationTableData>()
        for (pr in summary.problemResults) {
            val outcome = pr.confirmation ?: continue
            for ((index, solution) in outcome.confirmedSolutions.withIndex()) {
                rows.add(
                    ConfirmationTableData(
                        expId = expId,
                        problemName = pr.problemName,
                        candidateNum = index + 1,
                        inputsJson = toJson(solution.inputMap.toMap()),
                        objective = solution.average,
                        penalizedObjective = solution.penalizedObjFncValue,
                        numReplications = solution.count,
                        isWinner = solution.inputMap == outcome.winner.inputMap
                    )
                )
            }
        }
        insertAllDbDataIntoTable(rows, "tblConfirmation")
    }

    private fun saveVerifications(expId: Int, summary: BenchmarkSummary) {
        val rows = mutableListOf<VerificationTableData>()
        for (pr in summary.problemResults) {
            val verification = pr.verification ?: continue
            val inputsJson = toJson(verification.inputMap.toMap())
            val estimates = listOf(verification.estimatedObjFnc) + verification.responseEstimates
            for (estimate in estimates) {
                rows.add(
                    VerificationTableData(
                        expId = expId,
                        problemName = pr.problemName,
                        responseName = estimate.name,
                        inputsJson = inputsJson,
                        average = estimate.average,
                        variance = estimate.variance,
                        count = estimate.count
                    )
                )
            }
        }
        insertAllDbDataIntoTable(rows, "tblVerification")
    }

    private fun saveTraces(runIdByCell: Map<String, Int>, summary: BenchmarkSummary) {
        val rows = mutableListOf<IterationTraceTableData>()
        for ((cellLabel, points) in summary.traces) {
            val runId = runIdByCell[cellLabel] ?: continue
            for (point in points) {
                rows.add(
                    IterationTraceTableData(
                        runId = runId,
                        iteration = point.iteration,
                        cumulativeReplications = point.cumulativeReplications,
                        bestPenalizedObjective = point.bestPenalizedObjective
                    )
                )
            }
        }
        insertAllDbDataIntoTable(rows, "tblIterationTrace")
    }

    // ── Typed extraction, one per table ──────────────────────────────────────

    /** All experiment rows. */
    fun experiments(): List<ExperimentTableData> {
        return selectTableDataIntoDbData(::ExperimentTableData)
    }

    /** Problem rows, optionally restricted to one experiment. */
    fun problems(expId: Int? = null): List<ProblemTableData> {
        return selectTableDataIntoDbData(::ProblemTableData).filter { expId == null || it.expId == expId }
    }

    /** Solver case rows, optionally restricted to one experiment. */
    fun solverCases(expId: Int? = null): List<SolverCaseTableData> {
        return selectTableDataIntoDbData(::SolverCaseTableData).filter { expId == null || it.expId == expId }
    }

    /** Solver case parameter rows, optionally restricted to one experiment. */
    fun solverCaseParameters(expId: Int? = null): List<SolverCaseParameterTableData> {
        return selectTableDataIntoDbData(::SolverCaseParameterTableData).filter { expId == null || it.expId == expId }
    }

    /** Run rows, optionally restricted to one experiment. */
    fun runs(expId: Int? = null): List<RunTableData> {
        return selectTableDataIntoDbData(::RunTableData).filter { expId == null || it.expId == expId }
    }

    /** Confirmation rows, optionally restricted to one experiment. */
    fun confirmations(expId: Int? = null): List<ConfirmationTableData> {
        return selectTableDataIntoDbData(::ConfirmationTableData).filter { expId == null || it.expId == expId }
    }

    /** Verification rows, optionally restricted to one experiment. */
    fun verifications(expId: Int? = null): List<VerificationTableData> {
        return selectTableDataIntoDbData(::VerificationTableData).filter { expId == null || it.expId == expId }
    }

    /** Iteration-trace rows, optionally restricted to one experiment's runs. */
    fun traces(expId: Int? = null): List<IterationTraceTableData> {
        val all = selectTableDataIntoDbData(::IterationTraceTableData)
        if (expId == null) {
            return all
        }
        val runIds = runs(expId).map { it.runId }.toSet()
        return all.filter { it.runId in runIds }
    }

    // ── Post-processing feeds ────────────────────────────────────────────────

    /**
     *  The multiple-comparison feed for one problem of one experiment: solver case
     *  label mapped to the final objective values (or gaps) across the completed,
     *  valid macro-replications, ordered by replication number.
     *
     *  A `MultipleComparisonAnalyzer` requires the same number of observations per
     *  alternative; when cells failed, filter or trim the returned arrays before
     *  constructing the analyzer.
     *
     *  @param expId the experiment id
     *  @param problemName the problem's name
     *  @param useGaps when true the arrays hold the recorded optimality gaps instead of
     *  raw objective values (NaN for runs without a gap)
     */
    fun mcbDataMap(expId: Int, problemName: String, useGaps: Boolean = false): Map<String, DoubleArray> {
        val rows = runs(expId).filter {
            it.problemName == problemName && it.status == "COMPLETED" && it.bestValid
        }
        return rows.groupBy { it.solverLabel }.mapValues { (_, list) ->
            list.sortedBy { it.repNum }
                .map { if (useGaps) it.gap ?: Double.NaN else it.bestObjective }
                .toDoubleArray()
        }
    }

    /**
     *  A `MultipleComparisonAnalyzer` over one problem's final objectives (see
     *  [mcbDataMap]); null when fewer than two solver cases have complete data.
     */
    fun mcbAnalyzer(expId: Int, problemName: String, useGaps: Boolean = false): MultipleComparisonAnalyzer? {
        val dataMap = mcbDataMap(expId, problemName, useGaps)
        if (dataMap.size < 2) {
            return null
        }
        val sizes = dataMap.values.map { it.size }.toSet()
        if (sizes.size != 1) {
            return null
        }
        return MultipleComparisonAnalyzer(dataMap, responseName = problemName)
    }

    /**
     *  Performance-profile data across an experiment's traced runs: for each solver
     *  case and each budget fraction on a grid, the fraction of (problem, macro-rep)
     *  cells whose best penalized objective reached the problem's gap basis plus tau
     *  within that fraction of the replication budget.
     *
     *  Requires captured iteration traces and problems with a gap basis; runs without
     *  either are excluded. The solve test compares the (minimization-oriented)
     *  penalized objective against the oriented basis plus tau, which equals the raw
     *  objective comparison for problems without response constraints.
     *
     *  @param expId the experiment id
     *  @param tau the solve tolerance above the gap basis; must be non-negative
     *  @param numPoints the number of budget-fraction grid points in (0, 1]
     */
    fun performanceProfile(expId: Int, tau: Double, numPoints: Int = 20): List<PerformanceProfilePoint> {
        require(tau >= 0.0) { "tau must be >= 0.0" }
        require(numPoints >= 1) { "numPoints must be >= 1" }
        val experiment = experiments().firstOrNull { it.expId == expId } ?: return emptyList()
        val budget = experiment.replicationBudgetPerRun.toDouble()
        val problemByName = problems(expId).associateBy { it.problemName }
        val tracesByRun = traces(expId).groupBy { it.runId }
        val solveFractions = mutableListOf<Pair<String, Double?>>()
        for (run in runs(expId)) {
            if (run.status != "COMPLETED") continue
            val problem = problemByName[run.problemName] ?: continue
            val basis = problem.gapBasisObjective ?: continue
            val trace = tracesByRun[run.runId] ?: continue
            val orientation = if (problem.optimizationType == "MAXIMIZE") -1.0 else 1.0
            val threshold = orientation * basis + tau
            val solvedAt = trace.sortedBy { it.iteration }
                .firstOrNull { it.bestPenalizedObjective <= threshold }
                ?.cumulativeReplications
            solveFractions.add(run.solverLabel to solvedAt?.let { it / budget })
        }
        val profile = mutableListOf<PerformanceProfilePoint>()
        for ((label, cells) in solveFractions.groupBy({ it.first }, { it.second })) {
            for (k in 1..numPoints) {
                val fraction = k.toDouble() / numPoints
                val solved = cells.count { it != null && it <= fraction }
                profile.add(PerformanceProfilePoint(label, fraction, solved.toDouble() / cells.size))
            }
        }
        return profile
    }

    private fun nextId(tableName: String, columnName: String): Int {
        val rowSet = fetchCachedRowSet("SELECT MAX($columnName) FROM $tableName") ?: return 1
        return if (rowSet.next()) rowSet.getInt(1) + 1 else 1
    }

    companion object {

        /** Fresh table-definition prototypes for the benchmark schema. */
        fun tableDefinitions(): Set<ksl.utilities.io.dbutil.DbTableData> {
            return setOf(
                ExperimentTableData(),
                ProblemTableData(),
                SolverCaseTableData(),
                SolverCaseParameterTableData(),
                RunTableData(),
                ConfirmationTableData(),
                IterationTraceTableData(),
                VerificationTableData()
            )
        }
    }
}
