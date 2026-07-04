package ksl.simopt.benchmark.io

import ksl.utilities.io.dbutil.DbTableData

/**
 *  One row per benchmark experiment. Experiment ids are assigned by the database
 *  (max + 1), so re-running an experiment — for example a trace-enabled rerun of a
 *  problem — appends into the same database under a new id.
 */
data class ExperimentTableData(
    var expId: Int = -1,
    var expName: String = "",
    var startTime: String = "",
    var endTime: String = "",
    var replicationBudgetPerRun: Int = 0,
    var macroReplications: Int = 0,
    var numProblems: Int = 0,
    var numSolverCases: Int = 0,
    var confirmationTopK: Int? = null,
    var confirmationReplications: Int? = null,
    var verificationReplications: Int? = null,
    var tracesCaptured: Boolean = false,
    var kslVersion: String? = null
) : DbTableData("tblExperiment", listOf("expId"))

/**
 *  One row per problem per experiment: identity, shape (dimension, orientation,
 *  constraints), the reference solution when one exists, the gap basis the runs were
 *  measured against, and the problem's winning point.
 */
data class ProblemTableData(
    var expId: Int = -1,
    var problemName: String = "",
    var dimension: Int = 0,
    var optimizationType: String = "",
    var numResponseConstraints: Int = 0,
    var tagsJson: String? = null,
    var referenceType: String? = null,
    var referenceObjective: Double? = null,
    var referenceInputsJson: String? = null,
    var gapType: String? = null,
    var gapBasisObjective: Double? = null,
    var winnerInputsJson: String? = null,
    var winnerObjective: Double? = null
) : DbTableData("tblProblem", listOf("expId", "problemName"))

/**
 *  One row per solver case per experiment; the case's parameters live in
 *  tblSolverCaseParameter.
 */
data class SolverCaseTableData(
    var expId: Int = -1,
    var solverLabel: String = "",
    var description: String = ""
) : DbTableData("tblSolverCase", listOf("expId", "solverLabel"))

/**
 *  The flattened configuration properties of a solver case, read from the first solver
 *  instance the case actually created during the experiment — the configuration that
 *  ran, not what was assumed.
 */
data class SolverCaseParameterTableData(
    var expId: Int = -1,
    var solverLabel: String = "",
    var paramName: String = "",
    var paramValue: String = ""
) : DbTableData("tblSolverCaseParameter", listOf("expId", "solverLabel", "paramName"))

/**
 *  One row per benchmark cell: one solver case run once (one macro-replication) on one
 *  problem under the experiment's budget. numReplicationsRequested is the actual budget
 *  consumption used to normalize cross-algorithm comparisons.
 */
data class RunTableData(
    var runId: Int = -1,
    var expId: Int = -1,
    var problemName: String = "",
    var solverLabel: String = "",
    var repNum: Int = 0,
    var cellLabel: String = "",
    var status: String = "",
    var startingPointJson: String = "",
    var bestInputsJson: String = "",
    var bestObjective: Double = 0.0,
    var bestPenalizedObjective: Double = 0.0,
    var bestValid: Boolean = false,
    var inputFeasible: Boolean = false,
    var responseConstraintViolation: Double = 0.0,
    var numOracleCalls: Int = 0,
    var numReplicationsRequested: Int = 0,
    var totalIterations: Int? = null,
    var wallClockMillis: Long? = null,
    var gap: Double? = null,
    var gapType: String? = null,
    var errorMessage: String? = null
) : DbTableData("tblRun", listOf("runId"))

/**
 *  One row per confirmed finalist of a problem's confirmation stage: the finalist's
 *  point, its CRN-confirmed estimates, and whether it won.
 */
data class ConfirmationTableData(
    var expId: Int = -1,
    var problemName: String = "",
    var candidateNum: Int = 0,
    var inputsJson: String = "",
    var objective: Double = 0.0,
    var penalizedObjective: Double = 0.0,
    var numReplications: Double = 0.0,
    var isWinner: Boolean = false
) : DbTableData("tblConfirmation", listOf("expId", "problemName", "candidateNum"))

/**
 *  One row per captured iteration of a run's trace (opt-in): the cumulative requested
 *  replications and the best penalized objective so far. Iteration 0 is the initialized
 *  state. Keyed by run id, so a trace-enabled rerun lands in the same database next to
 *  earlier trace-free experiments.
 */
data class IterationTraceTableData(
    var runId: Int = -1,
    var iteration: Int = 0,
    var cumulativeReplications: Int = 0,
    var bestPenalizedObjective: Double = 0.0
) : DbTableData("tblIterationTrace", listOf("runId", "iteration"))

/**
 *  One row per response of a problem's verification stage (opt-in): the winning point
 *  re-simulated at the experiment's verification replication count.
 */
data class VerificationTableData(
    var expId: Int = -1,
    var problemName: String = "",
    var responseName: String = "",
    var inputsJson: String = "",
    var average: Double = 0.0,
    var variance: Double = 0.0,
    var count: Double = 0.0
) : DbTableData("tblVerification", listOf("expId", "problemName", "responseName"))
