package ksl.simopt.benchmark.io

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ksl.simopt.benchmark.BenchmarkResultSink
import ksl.simopt.benchmark.BenchmarkSummary
import ksl.simopt.benchmark.BenchmarkSummaryHeader
import ksl.simopt.benchmark.IterationTracePoint
import ksl.simopt.benchmark.ProblemBenchmarkResult
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
) : SQLiteDb(dbName, dbDirectory, deleteIfExists), BenchmarkResultSink {

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
     *  Persists a complete benchmark summary: the experiment row, its problems, solver cases and
     *  their captured parameters, every cell run with its per-constraint and per-response detail,
     *  confirmation finalists, verification estimates, and (when captured) iteration traces and
     *  solver state keyed by run id.
     *
     *  Routed through this database's own [BenchmarkResultSink] implementation, so a summary saved
     *  in one call and an experiment streamed problem-by-problem travel the same code and cannot
     *  drift apart. A summary is by definition finished, so it never resumes into an unfinished
     *  record of the same name — it always gets a fresh experiment id.
     *
     *  @param summary the summary returned by a benchmark experiment's run()
     *  @param kslVersion an optional KSL version string recorded with the experiment
     *  @return the experiment id assigned to this summary within the database
     */
    fun saveSummary(summary: BenchmarkSummary, kslVersion: String? = null): Int {
        val header = BenchmarkSummaryHeader(
            experimentName = summary.experimentName,
            macroReplications = summary.macroReplications,
            replicationBudgetPerRun = summary.replicationBudgetPerRun,
            confirmationTopK = summary.confirmation?.topK,
            confirmationReplications = summary.confirmation?.replicationsPerCandidate,
            verificationReplications = summary.verificationReplications,
            numProblems = summary.problemResults.size,
            solverCaseDescriptions = summary.solverCaseDescriptions,
            startTime = summary.startTime,
            tracesCaptured = summary.traces.isNotEmpty(),
            solverStateCaptured = summary.traces.values.any { points ->
                points.any { it.solverState.isNotEmpty() }
            }
        )
        val expId = beginExperiment(header, resume = false, kslVersion = kslVersion)
        for (pr in summary.problemResults) {
            val cellLabels = pr.runs.map { it.cellLabel }.toSet()
            problemCompleted(expId, pr, summary.traces.filterKeys { it in cellLabels })
        }
        endExperiment(expId, summary.endTime, summary.solverConfigurations)
        return expId
    }

    // ── BenchmarkResultSink ──────────────────────────────────────────────────

    override fun beginExperiment(header: BenchmarkSummaryHeader, resume: Boolean): Int =
        beginExperiment(header, resume, kslVersion = null)

    /**
     *  As [beginExperiment], additionally recording a KSL version string on a freshly created
     *  experiment row. Resuming leaves the existing row's version untouched: it records the version
     *  the experiment started under, which is the one its earlier problems actually ran on.
     */
    private fun beginExperiment(
        header: BenchmarkSummaryHeader,
        resume: Boolean,
        kslVersion: String?
    ): Int {
        if (resume) {
            val unfinished = unfinishedExperimentId(header.experimentName)
            if (unfinished != null) {
                logger.info {
                    "Resuming benchmark experiment '${header.experimentName}' as expId $unfinished"
                }
                return unfinished
            }
        }
        val expId = nextId("tblExperiment", "expId")
        insertDbDataIntoTable(
            ExperimentTableData(
                expId = expId,
                expName = header.experimentName,
                startTime = header.startTime.toString(),
                // Empty until endExperiment closes the record. That emptiness IS the unfinished
                // marker a resume looks for, so it must not be given a placeholder value.
                endTime = "",
                replicationBudgetPerRun = header.replicationBudgetPerRun,
                macroReplications = header.macroReplications,
                numProblems = header.numProblems,
                numSolverCases = header.solverCaseDescriptions.size,
                confirmationTopK = header.confirmationTopK,
                confirmationReplications = header.confirmationReplications,
                verificationReplications = header.verificationReplications,
                tracesCaptured = header.tracesCaptured,
                solverStateCaptured = header.solverStateCaptured,
                kslVersion = kslVersion
            )
        )
        insertAllDbDataIntoTable(
            header.solverCaseDescriptions.map { (label, description) ->
                SolverCaseTableData(expId, label, description)
            },
            "tblSolverCase"
        )
        return expId
    }

    override fun problemCompleted(
        expId: Int,
        result: ProblemBenchmarkResult,
        traces: Map<String, List<IterationTracePoint>>
    ) {
        saveProblem(expId, result)
        saveProblemConstraints(expId, result)
        val runIdByCell = saveRuns(expId, result)
        saveRunConstraintsAndResponses(runIdByCell, result)
        saveConfirmation(expId, result)
        saveVerification(expId, result)
        saveTraces(runIdByCell, traces)
    }

    override fun endExperiment(
        expId: Int,
        endTime: Instant,
        solverConfigurations: Map<String, Map<String, String>>
    ) {
        insertAllDbDataIntoTable(
            solverConfigurations.flatMap { (label, properties) ->
                properties.map { (paramName, paramValue) ->
                    SolverCaseParameterTableData(expId, label, paramName, paramValue)
                }
            },
            "tblSolverCaseParameter"
        )
        // Stamping the end time is what marks the record finished, so it is the last write.
        executeCommand(
            "UPDATE tblExperiment SET endTime = '${endTime}' WHERE expId = $expId"
        )
    }

    override fun completedProblems(expName: String): Set<String> {
        val expId = unfinishedExperimentId(expName) ?: return emptySet()
        return problems(expId).map { it.problemName }.toSet()
    }

    /**
     *  The id of an unfinished experiment of this name — one whose end time was never stamped,
     *  because its run was interrupted. Null when no such record exists, which includes the ordinary
     *  case of every same-named experiment having completed.
     *
     *  The most recent is chosen when several are unfinished, so a second interruption resumes the
     *  latest attempt rather than an older abandoned one.
     */
    private fun unfinishedExperimentId(expName: String): Int? {
        return experiments()
            .filter { it.expName == expName && it.endTime.isEmpty() }
            .maxByOrNull { it.expId }
            ?.expId
    }

    // ── Per-problem writers ──────────────────────────────────────────────────

    private fun saveProblem(expId: Int, pr: ProblemBenchmarkResult) {
        insertDbDataIntoTable(
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
        )
    }

    private fun saveProblemConstraints(expId: Int, pr: ProblemBenchmarkResult) {
        insertAllDbDataIntoTable(
            pr.responseConstraints.map { rc ->
                ProblemConstraintTableData(
                    expId = expId,
                    problemName = pr.problemName,
                    responseName = rc.responseName,
                    rhsValue = rc.rhsValue,
                    inequalityType = rc.inequalityType.name,
                    target = rc.target,
                    tolerance = rc.tolerance
                )
            },
            "tblProblemConstraint"
        )
    }

    private fun saveRuns(expId: Int, pr: ProblemBenchmarkResult): Map<String, Int> {
        var runId = nextId("tblRun", "runId")
        val runIdByCell = mutableMapOf<String, Int>()
        val rows = mutableListOf<RunTableData>()
        for (run in pr.runs) {
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
                    cpuTimeMillis = run.cpuTimeMillis,
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

    /**
     *  Writes both per-run detail tables in one pass over the cells, since they share the run id
     *  assigned by `saveRuns` and differ only in what they project from the same best solution.
     */
    private fun saveRunConstraintsAndResponses(
        runIdByCell: Map<String, Int>,
        pr: ProblemBenchmarkResult
    ) {
        val constraintRows = mutableListOf<RunConstraintTableData>()
        val responseRows = mutableListOf<RunResponseTableData>()
        for (run in pr.runs) {
            val runId = runIdByCell[run.cellLabel] ?: continue
            for (assessment in run.responseConstraintAssessments) {
                constraintRows.add(
                    RunConstraintTableData(
                        runId = runId,
                        responseName = assessment.responseName,
                        estimate = assessment.estimate,
                        violation = assessment.violation,
                        ciUpperLimit = assessment.ciUpperLimit,
                        feasibleAtCI = assessment.isFeasibleAtCI
                    )
                )
            }
            for ((responseName, estimate) in run.responseEstimates) {
                responseRows.add(
                    RunResponseTableData(
                        runId = runId,
                        responseName = responseName,
                        average = estimate.average,
                        variance = estimate.variance,
                        count = estimate.count
                    )
                )
            }
        }
        insertAllDbDataIntoTable(constraintRows, "tblRunConstraint")
        insertAllDbDataIntoTable(responseRows, "tblRunResponse")
    }

    private fun saveConfirmation(expId: Int, pr: ProblemBenchmarkResult) {
        val outcome = pr.confirmation ?: return
        // Written for every problem whose confirmation stage ran, including one whose
        // finalists collapsed to a single point and produced no candidate rows below.
        insertDbDataIntoTable(
            ConfirmationSummaryTableData(
                expId = expId,
                problemName = pr.problemName,
                numCandidates = outcome.numCandidates,
                numConfidentlyFeasible = outcome.numConfidentlyFeasible,
                selectionDegenerate = outcome.selectionDegenerate,
                numConfidentlyFeasibleAfterScreening = outcome.numConfidentlyFeasibleAfterScreening,
                numOracleCalls = outcome.numOracleCalls,
                numReplicationsRequested = outcome.numReplicationsRequested
            )
        )
        insertAllDbDataIntoTable(
            outcome.confirmedSolutions.mapIndexed { index, solution ->
                ConfirmationTableData(
                    expId = expId,
                    problemName = pr.problemName,
                    candidateNum = index + 1,
                    inputsJson = toJson(solution.inputMap.toMap()),
                    objective = solution.average,
                    penalizedObjective = solution.recordedPenalizedObjFncValue,
                    numReplications = solution.count,
                    isWinner = solution.inputMap == outcome.winner.inputMap
                )
            },
            "tblConfirmation"
        )
    }

    private fun saveVerification(expId: Int, pr: ProblemBenchmarkResult) {
        val verification = pr.verification ?: return
        val inputsJson = toJson(verification.inputMap.toMap())
        val estimates = listOf(verification.estimatedObjFnc) + verification.responseEstimates
        insertAllDbDataIntoTable(
            estimates.map { estimate ->
                VerificationTableData(
                    expId = expId,
                    problemName = pr.problemName,
                    responseName = estimate.name,
                    inputsJson = inputsJson,
                    average = estimate.average,
                    variance = estimate.variance,
                    count = estimate.count
                )
            },
            "tblVerification"
        )
    }

    private fun saveTraces(
        runIdByCell: Map<String, Int>,
        traces: Map<String, List<IterationTracePoint>>
    ) {
        val rows = mutableListOf<IterationTraceTableData>()
        val stateRows = mutableListOf<IterationTraceStateTableData>()
        for ((cellLabel, points) in traces) {
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
                for ((stateName, stateValue) in point.solverState) {
                    // A solver reporting an unmeasurable quantity says so with NaN, which SQLite
                    // stores as null. Keeping the row preserves the fact that the solver was asked
                    // and answered; dropping it would look like the measurement never existed.
                    stateRows.add(
                        IterationTraceStateTableData(
                            runId = runId,
                            iteration = point.iteration,
                            stateName = stateName,
                            stateValue = stateValue
                        )
                    )
                }
            }
        }
        insertAllDbDataIntoTable(rows, "tblIterationTrace")
        insertAllDbDataIntoTable(stateRows, "tblIterationTraceState")
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

    /**
     *  Per-constraint rows for an experiment's cells: what each cell's best achieved against each
     *  response constraint. Restricted to one experiment by joining through its run ids.
     */
    fun runConstraints(expId: Int? = null): List<RunConstraintTableData> {
        val all = selectTableDataIntoDbData(::RunConstraintTableData)
        if (expId == null) {
            return all
        }
        val runIds = runs(expId).map { it.runId }.toSet()
        return all.filter { it.runId in runIds }
    }

    /**
     *  Per-response estimate rows for an experiment's cells — the average, variance and count that
     *  make a selection replayable offline. Restricted to one experiment by joining through its
     *  run ids.
     */
    fun runResponses(expId: Int? = null): List<RunResponseTableData> {
        val all = selectTableDataIntoDbData(::RunResponseTableData)
        if (expId == null) {
            return all
        }
        val runIds = runs(expId).map { it.runId }.toSet()
        return all.filter { it.runId in runIds }
    }

    /** The response constraints of an experiment's problems, as the problems define them. */
    fun problemConstraints(expId: Int? = null): List<ProblemConstraintTableData> {
        return selectTableDataIntoDbData(::ProblemConstraintTableData)
            .filter { expId == null || it.expId == expId }
    }

    /** Confirmation rows, optionally restricted to one experiment. */
    fun confirmations(expId: Int? = null): List<ConfirmationTableData> {
        return selectTableDataIntoDbData(::ConfirmationTableData).filter { expId == null || it.expId == expId }
    }

    /**
     *  Confirmation summary rows, optionally restricted to one experiment. One row per problem
     *  whose confirmation stage ran; `selectionDegenerate` marks a problem whose winner was
     *  chosen by constraint violation alone, with the objective unused.
     */
    fun confirmationSummaries(expId: Int? = null): List<ConfirmationSummaryTableData> {
        return selectTableDataIntoDbData(::ConfirmationSummaryTableData)
            .filter { expId == null || it.expId == expId }
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

    /**
     *  Solver-state rows for an experiment's captured traces, in long format — one row per
     *  (run, iteration, state name). Empty when the experiment did not capture solver state;
     *  `tblExperiment.solverStateCaptured` distinguishes that from a study whose solvers published
     *  nothing.
     */
    fun traceStates(expId: Int? = null): List<IterationTraceStateTableData> {
        val all = selectTableDataIntoDbData(::IterationTraceStateTableData)
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
        return mcbDataMap(listOf(expId), problemName, useGaps)
    }

    /**
     *  The multiple-comparison feed for one problem pooled across several experiments — the
     *  blocks of one study, run as separate experiments so that a long run can be checkpointed
     *  and resumed. Solver case label mapped to the final objective values (or gaps) across the
     *  completed, valid macro-replications of every supplied experiment, ordered by replication
     *  number.
     *
     *  The experiments must be blocks of the SAME study: a (solver, macro-replication) pair may
     *  appear at most once across them. Pooling unrelated experiments, or the same block twice,
     *  would silently double the sample and report intervals far tighter than the data supports,
     *  so it is rejected rather than averaged over.
     *
     *  @param expIds the experiment ids to pool; must not be empty
     *  @param problemName the problem's name
     *  @param useGaps when true the arrays hold the recorded optimality gaps instead of
     *  raw objective values (NaN for runs without a gap)
     */
    fun mcbDataMap(
        expIds: Collection<Int>,
        problemName: String,
        useGaps: Boolean = false
    ): Map<String, DoubleArray> {
        require(expIds.isNotEmpty()) { "At least one experiment id must be supplied" }
        val idSet = expIds.toSet()
        val rows = selectTableDataIntoDbData(::RunTableData).filter {
            it.expId in idSet && it.problemName == problemName && it.status == "COMPLETED" && it.bestValid
        }
        val duplicates = rows.groupBy { it.solverLabel to it.repNum }.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) {
            "Experiments $expIds are not disjoint blocks of one study: problem '$problemName' has " +
                    "repeated (solver, macro-replication) pairs ${duplicates.keys}"
        }
        return rows.groupBy { it.solverLabel }.mapValues { (_, list) ->
            list.sortedBy { it.repNum }
                .map { if (useGaps) it.gap ?: Double.NaN else it.bestObjective }
                .toDoubleArray()
        }
    }

    /**
     *  A `MultipleComparisonAnalyzer` over one problem's final objectives (see
     *  [mcbDataMap]); null when the data cannot support a multiple comparison — fewer than
     *  two solver cases with complete data, unequal numbers of observations across the
     *  cases, or fewer than two observations per case.
     */
    fun mcbAnalyzer(expId: Int, problemName: String, useGaps: Boolean = false): MultipleComparisonAnalyzer? {
        return mcbAnalyzer(listOf(expId), problemName, useGaps)
    }

    /**
     *  A `MultipleComparisonAnalyzer` over one problem's final objectives pooled across the
     *  blocks of one study (see the pooled [mcbDataMap]); null when the data cannot support a
     *  multiple comparison — fewer than two solver cases with complete data, unequal numbers of
     *  observations across the cases, or fewer than two observations per case.
     */
    fun mcbAnalyzer(
        expIds: Collection<Int>,
        problemName: String,
        useGaps: Boolean = false
    ): MultipleComparisonAnalyzer? {
        val dataMap = mcbDataMap(expIds, problemName, useGaps)
        if (dataMap.size < 2) {
            return null
        }
        val sizes = dataMap.values.map { it.size }.toSet()
        if (sizes.size != 1) {
            return null
        }
        // A single macro-replication per solver leaves no degrees of freedom: the analyzer's
        // interval arithmetic uses (number of observations - 1), which StudentT rejects below 1.
        // Decline the analysis rather than throwing from deep inside it, so that a study whose
        // compute has already been spent still reports its runs.
        if (sizes.first() < 2) {
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
        return performanceProfile(listOf(expId), tau, numPoints)
    }

    /**
     *  Performance-profile data pooled across the blocks of one study (see the pooled
     *  [mcbDataMap]). The blocks must share a replication budget, since the profile's x-axis is
     *  the fraction of that budget; pooling experiments with different budgets would put
     *  incomparable cells on one axis.
     *
     *  Each problem needs one solve threshold across the whole pooled set. Problems measured
     *  against a reference solution already carry the same basis in every block and it is used
     *  directly. A problem with no reference is gapped against the best objective found within
     *  its experiment, so the blocks disagree — the basis is then recomputed as the best across
     *  every pooled run of that problem, which is the value a single unblocked run would have
     *  recorded. With one experiment there is nothing to disagree with, so a single-experiment
     *  profile is unchanged.
     *
     *  @param expIds the experiment ids to pool; must not be empty
     *  @param tau the solve tolerance above the gap basis; must be non-negative
     *  @param numPoints the number of budget-fraction grid points in (0, 1]
     */
    fun performanceProfile(
        expIds: Collection<Int>,
        tau: Double,
        numPoints: Int = 20
    ): List<PerformanceProfilePoint> {
        require(expIds.isNotEmpty()) { "At least one experiment id must be supplied" }
        require(tau >= 0.0) { "tau must be >= 0.0" }
        require(numPoints >= 1) { "numPoints must be >= 1" }
        val idSet = expIds.toSet()
        val pooled = experiments().filter { it.expId in idSet }
        if (pooled.isEmpty()) return emptyList()
        val budgets = pooled.map { it.replicationBudgetPerRun }.toSet()
        require(budgets.size == 1) {
            "Experiments $expIds do not share a replication budget (found $budgets); the profile's " +
                    "budget fraction would not be comparable across them"
        }
        val budget = budgets.first().toDouble()
        val allRuns = selectTableDataIntoDbData(::RunTableData).filter { it.expId in idSet }
        val problemRows = selectTableDataIntoDbData(::ProblemTableData).filter { it.expId in idSet }
        val runIds = allRuns.map { it.runId }.toSet()
        val tracesByRun = selectTableDataIntoDbData(::IterationTraceTableData)
            .filter { it.runId in runIds }
            .groupBy { it.runId }

        // one threshold per problem across the whole pooled set
        val orientationByProblem = mutableMapOf<String, Double>()
        val thresholdByProblem = mutableMapOf<String, Double>()
        for ((problemName, rows) in problemRows.groupBy { it.problemName }) {
            val orientation = if (rows.first().optimizationType == "MAXIMIZE") -1.0 else 1.0
            orientationByProblem[problemName] = orientation
            val bases = rows.mapNotNull { it.gapBasisObjective }.toSet()
            val basis = when {
                bases.isEmpty() -> null
                bases.size == 1 -> bases.first()
                else -> {
                    require(rows.all { it.gapType == "BEST_FOUND" }) {
                        "Problem '$problemName' is measured against a reference solution but the " +
                                "pooled experiments $expIds disagree on its basis $bases"
                    }
                    allRuns.filter { it.problemName == problemName && it.status == "COMPLETED" && it.bestValid }
                        .minOfOrNull { orientation * it.bestObjective }
                        ?.let { it * orientation }
                }
            }
            if (basis != null) {
                thresholdByProblem[problemName] = orientation * basis + tau
            }
        }

        val solveFractions = mutableListOf<Pair<String, Double?>>()
        for (run in allRuns) {
            if (run.status != "COMPLETED") continue
            val threshold = thresholdByProblem[run.problemName] ?: continue
            val trace = tracesByRun[run.runId] ?: continue
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
        val logger: KLogger = KotlinLogging.logger {}

        fun tableDefinitions(): Set<ksl.utilities.io.dbutil.DbTableData> {
            return setOf(
                ExperimentTableData(),
                ProblemTableData(),
                SolverCaseTableData(),
                SolverCaseParameterTableData(),
                RunTableData(),
                RunConstraintTableData(),
                RunResponseTableData(),
                ProblemConstraintTableData(),
                ConfirmationTableData(),
                ConfirmationSummaryTableData(),
                IterationTraceTableData(),
                IterationTraceStateTableData(),
                VerificationTableData()
            )
        }
    }
}
