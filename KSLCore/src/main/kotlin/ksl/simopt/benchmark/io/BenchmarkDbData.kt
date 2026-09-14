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
    var solverStateCaptured: Boolean = false,
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
    var cpuTimeMillis: Long? = null,
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
 *  One row per (run, response constraint): the cell best's estimate for the constrained response,
 *  by how much the constraint is violated, the one-sided upper confidence limit, and whether that
 *  constraint on its own can be declared feasible.
 *
 *  `tblRun.responseConstraintViolation` is retained alongside this and is unchanged; it is the
 *  aggregate, and an aggregate cannot say WHICH constraint bound. That question — do particular
 *  solvers fail specifically on one coupling constraint? — is what these rows answer.
 *
 *  `ciUpperLimit` is null when the estimate carried fewer than two observations, so there was no
 *  sample variance and no interval to report. A `feasibleAtCI` of false on such a row means "not
 *  shown feasible", not "shown infeasible".
 */
data class RunConstraintTableData(
    var runId: Int = -1,
    var responseName: String = "",
    var estimate: Double = 0.0,
    var violation: Double = 0.0,
    var ciUpperLimit: Double? = null,
    var feasibleAtCI: Boolean = false
) : DbTableData("tblRunConstraint", listOf("runId", "responseName"))

/**
 *  One row per (experiment, problem, response constraint): the constraint as the problem defines
 *  it. Without these rows the database records what a run achieved but not what it had to meet, so
 *  answering "was every verified winner feasible?" meant hand-coding the constraint table from the
 *  specification and joining it externally. With them the question is a single join and the archive
 *  is self-describing.
 */
data class ProblemConstraintTableData(
    var expId: Int = -1,
    var problemName: String = "",
    var responseName: String = "",
    var rhsValue: Double = 0.0,
    var inequalityType: String = "",
    var target: Double = 0.0,
    var tolerance: Double = 0.0
) : DbTableData("tblProblemConstraint", listOf("expId", "problemName", "responseName"))

/**
 *  One row per (run, response): the cell best's estimate for every response of the problem, the
 *  objective included.
 *
 *  This is what makes a selection replayable. `tblRun` preserves the best point's INPUTS, which is
 *  enough to re-simulate but not to re-select: ranking a candidate needs its average, variance and
 *  count per response. With those stored, an alternative confirmation rule can be applied to a
 *  finished study offline, and only the finalists it chooses need fresh simulation.
 *
 *  A variance of NaN is what a single-observation estimate legitimately carries.
 */
data class RunResponseTableData(
    var runId: Int = -1,
    var responseName: String = "",
    var average: Double = 0.0,
    var variance: Double = 0.0,
    var count: Double = 0.0
) : DbTableData("tblRunResponse", listOf("runId", "responseName"))

/**
 *  One row per (experiment, problem) confirmation stage: how many candidates were ranked,
 *  how many of them were confidently response-feasible at the confirmation's CI level, and
 *  whether the selection was therefore degenerate.
 *
 *  Separate from `tblConfirmation` rather than extra columns on it, for two reasons. The
 *  candidate table is one row per finalist, so these problem-level values would repeat on
 *  every row; and it gets no rows at all when confirmation is skipped for a single distinct
 *  finalist — which is exactly a case where knowing the selection could not discriminate
 *  still matters. A summary row is written whenever a confirmation stage ran.
 *
 *  A degenerate selection is one in which no candidate could be declared confidently
 *  feasible, so the comparator fell through to ranking by constraint violation alone and the
 *  objective played no part in choosing the winner. Always false for a problem with no
 *  response constraints.
 */
data class ConfirmationSummaryTableData(
    var expId: Int = -1,
    var problemName: String = "",
    var numCandidates: Int = 0,
    var numConfidentlyFeasible: Int = 0,
    var selectionDegenerate: Boolean = false,
    var numOracleCalls: Int = 0,
    var numReplicationsRequested: Int = 0
) : DbTableData("tblConfirmationSummary", listOf("expId", "problemName"))

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
 *  One row per (run, iteration, state name) of a captured trace (opt-in): the cell solver's
 *  algorithm-specific state for that iteration.
 *
 *  Long format deliberately. Solvers publish different state, and a new solver — or a new
 *  measurement on an existing one — would otherwise add a column and change the schema for every
 *  study already in the file. Keyed by run id, matching tblIterationTrace.
 *
 *  Volume is the reason this is gated behind its own flag rather than riding on trace capture: a
 *  solver publishing six values per iteration produces roughly six times the trace row count, so a
 *  study with a couple of hundred thousand trace rows lands on the order of a million here.
 */
data class IterationTraceStateTableData(
    var runId: Int = -1,
    var iteration: Int = 0,
    var stateName: String = "",
    var stateValue: Double = 0.0
) : DbTableData("tblIterationTraceState", listOf("runId", "iteration", "stateName"))

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
